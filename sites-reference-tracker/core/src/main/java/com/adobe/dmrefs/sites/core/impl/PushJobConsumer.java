package com.adobe.dmrefs.sites.core.impl;

import com.adobe.dmrefs.sites.core.model.PageUpdate;
import com.adobe.dmrefs.sites.core.model.Ref;
import com.adobe.dmrefs.sites.core.model.ScanResult;
import com.adobe.dmrefs.sites.core.util.BatchPusher;
import com.adobe.dmrefs.sites.core.util.Caches.DeliveryCache;
import com.adobe.dmrefs.sites.core.util.Caches.FingerprintCache;
import com.adobe.dmrefs.sites.core.util.Hashes;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.event.jobs.Job;
import org.apache.sling.event.jobs.consumer.JobConsumer;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads each page's CURRENT state (ignoring what the event said) and pushes it to Assets. Two kinds of job, in
 * separate queues:
 *
 * <ul>
 *   <li><b>publish</b> (replication events, backfill): a page whose last replication action is Activate sends its
 *       published set; anything else sends an empty published set. A missing page is sent as deleted.</li>
 *   <li><b>draft</b> (settled page edits, best effort): sends the draft set, unless its fingerprint equals the last
 *       one delivered, which costs one scan and no HTTP call.</li>
 * </ul>
 *
 * Idempotent, and safe when jobs run out of order or a retry runs late.
 */
@Component(service = JobConsumer.class, property = {
        JobConsumer.PROPERTY_TOPICS + "=" + Jobs.TOPIC_PUBLISH,
        JobConsumer.PROPERTY_TOPICS + "=" + Jobs.TOPIC_DRAFT
})
public class PushJobConsumer implements JobConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(PushJobConsumer.class);

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Reference
    private PageReferenceScanner scanner;

    @Reference
    private TrackerSettings settings;

    @Reference
    private AssetsClient client;

    private volatile DeliveryCache delivered;
    private volatile FingerprintCache fingerprints;

    @Activate
    protected void activate() {
        delivered = new DeliveryCache(settings.scanCacheSize());
        fingerprints = new FingerprintCache(settings.fingerprintCacheSize());
    }

    @Override
    public JobResult process(Job job) {
        boolean draft = Jobs.TOPIC_DRAFT.equals(job.getTopic());
        String kind = draft ? "draft" : "publish";
        String[] paths = job.getProperty(Jobs.P_PATHS, String[].class);
        if (paths == null || paths.length == 0) {
            LOG.warn("DM References: {} job cancelled; no paths.", kind);
            return JobResult.CANCEL;
        }
        if (draft && !settings.isDraftEnabled()) {
            return JobResult.CANCEL;
        }
        if (!draft && !settings.canPush()) {
            LOG.warn("DM References: publish job will retry; tracker is not fully configured ({}).",
                    settings.describe());
            return JobResult.FAILED;
        }
        Long enqueued = job.getProperty(Jobs.P_ENQUEUED_AT, Long.class);
        long enqueuedAt = enqueued == null ? 0L : enqueued;

        List<PageUpdate> updates = new ArrayList<>();
        Map<String, Long> readAt = new HashMap<>();
        Map<String, String> fingerprintOf = new HashMap<>();
        int skipped = 0;
        try (ResourceResolver rr = ServiceResolvers.open(resolverFactory)) {
            String[] roots = settings.contentRoots();
            for (String path : paths) {
                if (!Hashes.isValidContentPath(path) || !Hashes.isUnderAnyRoot(path, roots)) {
                    skipped++;
                    continue;
                }
                if (!draft && delivered.covers(path, enqueuedAt)) {
                    LOG.debug("DM References: skipped {}; already delivered after this job was queued.", path);
                    skipped++;
                    continue;
                }
                long now = System.currentTimeMillis();
                PageUpdate update = draft ? readDraft(rr, path, now, fingerprintOf) : readPublished(rr, path, now);
                if (update == null) {
                    skipped++;
                } else {
                    updates.add(update);
                    readAt.put(path, now);
                }
            }
        } catch (LoginException e) {
            LOG.error("DM References: service user login failed; check repoinit and service user mapping.");
            return JobResult.FAILED;
        }
        if (updates.isEmpty()) {
            LOG.debug("DM References: {} job: {} page(s) skipped, nothing to push.", kind, skipped);
            return JobResult.OK;
        }

        BatchPusher.Result r = client.push(updates);
        for (PageUpdate p : r.delivered) {
            String path = p.getPagePath();
            if (!draft) {
                delivered.record(path, readAt.get(path));
            } else if (fingerprintOf.containsKey(path)) {
                fingerprints.record(path, fingerprintOf.get(path));
            } else {
                fingerprints.forget(path);
            }
        }
        for (PageUpdate p : r.dropped) {
            LOG.error("DM References: dropped {} {}; Assets rejected it (HTTP {}).", kind, p.getPagePath(),
                    r.lastStatus);
        }
        if (r.retry) {
            if (r.lastStatus == 401 || r.lastStatus == 403) {
                LOG.error("DM References: Assets rejected the credentials (HTTP {}). Check the IMS client and the "
                        + "technical account's membership in dm-reference-writers.", r.lastStatus);
            } else {
                LOG.warn("DM References: {} push failed (HTTP {}); {} page(s) delivered, job will retry.",
                        kind, r.lastStatus, r.delivered.size());
            }
            return JobResult.FAILED;
        }
        LOG.info("DM References: pushed {} {} page(s), skipped {}, dropped {}.", r.delivered.size(), kind, skipped,
                r.dropped.size());
        return JobResult.OK;
    }

    private PageUpdate readPublished(ResourceResolver rr, String path, long now) {
        Resource page = rr.getResource(path);
        if (page == null) {
            return PageUpdate.deleted(path, now);
        }
        Resource content = page.getChild("jcr:content");
        String action = content == null ? null : content.getValueMap().get("cq:lastReplicationAction", String.class);
        if (isPage(page) && "Activate".equals(action)) {
            ScanResult scan = scanner.scan(rr, path);
            if (scan != null) {
                return PageUpdate.published(path, now, scan.partial, scan.refs);
            }
        }
        return PageUpdate.published(path, now, false, Collections.<Ref>emptySet());
    }

    /** @return null if there is nothing to send: not a page, or its references are unchanged since the last push. */
    private PageUpdate readDraft(ResourceResolver rr, String path, long now, Map<String, String> fingerprintOf) {
        Resource page = rr.getResource(path);
        if (page == null) {
            return PageUpdate.deleted(path, now);
        }
        if (!isPage(page)) {
            return null;
        }
        ScanResult scan = scanner.scan(rr, path);
        if (scan == null) {
            return PageUpdate.draft(path, now, false, Collections.<Ref>emptySet());
        }
        String fp = FingerprintCache.fingerprint(scan.refs, scan.partial);
        if (fingerprints.matches(path, fp)) {
            return null;
        }
        fingerprintOf.put(path, fp);
        return PageUpdate.draft(path, now, scan.partial, scan.refs);
    }

    private static boolean isPage(Resource r) {
        return "cq:Page".equals(r.getValueMap().get("jcr:primaryType", String.class));
    }
}
