package com.adobe.dmrefs.sites.core.impl;

import com.adobe.dmrefs.sites.core.model.Ref;
import com.adobe.dmrefs.sites.core.model.Snapshot;
import com.adobe.dmrefs.sites.core.util.Hashes;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.event.jobs.Job;
import org.apache.sling.event.jobs.consumer.JobConsumer;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Calendar;
import java.util.Set;
import java.util.TreeSet;

/**
 * Recomputes a page's author/published reference sets, updates the /var snapshot and, if anything changed,
 * queues the page for push. Idempotent: running the same job twice is harmless.
 */
@Component(service = JobConsumer.class, property = JobConsumer.PROPERTY_TOPICS + "=" + Jobs.TOPIC)
public class PageReferenceJobConsumer implements JobConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(PageReferenceJobConsumer.class);

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Reference
    private PageReferenceScanner scanner;

    @Override
    public JobResult process(Job job) {
        String path = job.getProperty(Jobs.P_PATH, String.class);
        String actionName = job.getProperty(Jobs.P_ACTION, String.class);
        LOG.debug("DM References: job started {} {}", actionName, path);
        if (!Hashes.isValidContentPath(path) || actionName == null) {
            LOG.warn("DM References: job cancelled; invalid path or missing action ({} {})", actionName, path);
            return JobResult.CANCEL;
        }
        Jobs.Action action;
        try {
            action = Jobs.Action.valueOf(actionName);
        } catch (IllegalArgumentException e) {
            LOG.warn("DM References: job cancelled; unknown action {}", actionName);
            return JobResult.CANCEL;
        }

        try (ResourceResolver rr = ServiceResolvers.open(resolverFactory)) {
            Snapshot prev = SnapshotStore.read(rr, path);
            Snapshot next = prev == null ? new Snapshot(path) : prev.copy();

            if (!apply(rr, path, action, next)) {
                LOG.debug("DM References: {} {} skipped; not a tracked page.", action, path);
                return JobResult.OK; // not a page we track
            }
            if (prev == null && next.isEmpty()) {
                LOG.debug("DM References: {} {} has no DM references and no snapshot; nothing stored.", action, path);
                return JobResult.OK; // never referenced DM assets; don't store anything
            }
            if (next.sameContentAs(prev)) {
                LOG.debug("DM References: {} {} unchanged; nothing to push.", action, path);
                return JobResult.OK; // nothing changed
            }
            next.seq = Snapshot.nextSeq(prev == null ? 0L : prev.seq);
            SnapshotStore.write(rr, next);
            SnapshotStore.markPending(rr, path);
            rr.commit();
            LOG.debug("DM References: {} {} -> author={} published={} seq={}, queued for push", action, path,
                    next.authorRefs.size(), next.publishedRefs.size(), next.seq);
            return JobResult.OK;
        } catch (LoginException e) {
            LOG.error("DM References: service user login failed; check repoinit and service user mapping.");
            return JobResult.FAILED;
        } catch (PersistenceException e) {
            LOG.warn("DM References: could not persist snapshot for {} (will retry): {}", path, e.getMessage());
            return JobResult.FAILED;
        }
    }

    /** @return false if the path is not a page we should track. */
    private boolean apply(ResourceResolver rr, String path, Jobs.Action action, Snapshot s) {
        switch (action) {
            case AUTHOR_CHANGED: {
                Set<Ref> refs = scanPageOnly(rr, path);
                if (refs == null) {
                    return markDeleted(s);
                }
                s.authorRefs = refs;
                s.deleted = false;
                return true;
            }
            case PUBLISHED: {
                Set<Ref> refs = scanPageOnly(rr, path);
                if (refs == null) {
                    return false;
                }
                // Content at activation time == what was sent to publish.
                s.authorRefs = refs;
                s.publishedRefs = new TreeSet<>(refs);
                s.publishedAt = Calendar.getInstance();
                s.deleted = false;
                return true;
            }
            case UNPUBLISHED:
                s.publishedRefs = new TreeSet<>();
                s.publishedAt = null;
                return true;
            case DELETED:
                return markDeleted(s);
            case RECONCILE:
                return reconcile(rr, path, s);
            default:
                return false;
        }
    }

    private boolean markDeleted(Snapshot s) {
        s.authorRefs = new TreeSet<>();
        s.publishedRefs = new TreeSet<>();
        s.publishedAt = null;
        s.deleted = true;
        return true;
    }

    private boolean reconcile(ResourceResolver rr, String path, Snapshot s) {
        Set<Ref> refs = scanPageOnly(rr, path);
        if (refs == null) {
            return markDeleted(s);
        }
        s.authorRefs = refs;
        s.deleted = false;
        ValueMap content = rr.getResource(path + "/jcr:content").getValueMap();
        String lastAction = content.get("cq:lastReplicationAction", String.class);
        Calendar lastReplicated = content.get("cq:lastReplicated", Calendar.class);
        if ("Activate".equals(lastAction)) {
            boolean stale = s.publishedAt == null
                    || (lastReplicated != null && lastReplicated.after(s.publishedAt));
            if (stale) {
                // Approximation: we only have current author content. Exact when the page wasn't
                // edited after its last activation.
                s.publishedRefs = new TreeSet<>(refs);
                s.publishedAt = lastReplicated != null ? lastReplicated : Calendar.getInstance();
            }
        } else {
            s.publishedRefs = new TreeSet<>();
            s.publishedAt = null;
        }
        return true;
    }

    /** Scans only cq:Page resources; returns null if the path doesn't exist or isn't a page. */
    private Set<Ref> scanPageOnly(ResourceResolver rr, String path) {
        Resource r = rr.getResource(path);
        if (r == null) {
            LOG.debug("DM References: {} not visible to service user '{}' (missing or no read access).",
                    path, rr.getUserID());
            return null;
        }
        String type = r.getValueMap().get("jcr:primaryType", String.class);
        if (!"cq:Page".equals(type)) {
            LOG.debug("DM References: {} is {} not cq:Page; skipping scan.", path, type);
            return null;
        }
        return scanner.scan(rr, path);
    }
}
