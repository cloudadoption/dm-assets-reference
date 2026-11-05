package com.adobe.dmrefs.sites.core.impl;

import com.adobe.dmrefs.sites.core.model.Snapshot;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Drains /var/dm-references/pending in batches and POSTs them to Assets. Runs on one cluster node.
 * A page stays pending until Assets acknowledges the exact seq that was sent, so failures retry automatically.
 */
@Component(service = Runnable.class, property = {
        "scheduler.concurrent:Boolean=false",
        "scheduler.runOn=SINGLE"
})
@Designate(ocd = ReferencePushTask.Config.class)
public class ReferencePushTask implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(ReferencePushTask.class);
    /** Bound the work per run so one slow run doesn't block the scheduler thread for long. */
    private static final int MAX_BATCHES_PER_RUN = 10;

    @ObjectClassDefinition(name = "DM References - Push task")
    public @interface Config {
        @AttributeDefinition(name = "Cron expression", description = "Quartz cron. Default: every 30 seconds.")
        String scheduler_expression() default "0/30 * * * * ?";
    }

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Reference
    private TrackerSettings settings;

    @Reference
    private AssetsClient client;

    @Override
    public void run() {
        if (!settings.canPush()) {
            LOG.debug("DM References: push skipped; not ready ({})", settings.describe());
            return;
        }
        try (ResourceResolver rr = ServiceResolvers.open(resolverFactory)) {
            for (int i = 0; i < MAX_BATCHES_PER_RUN; i++) {
                if (!pushOneBatch(rr)) {
                    return;
                }
            }
        } catch (LoginException e) {
            LOG.error("DM References: service user login failed; check repoinit and service user mapping.");
        }
    }

    /** @return true if a full batch was pushed successfully and more may be waiting. */
    private boolean pushOneBatch(ResourceResolver rr) {
        int batchSize = settings.pushBatchSize();
        List<String> paths = SnapshotStore.pending(rr, batchSize);
        if (paths.isEmpty()) {
            LOG.trace("DM References: push run; nothing pending.");
            return false;
        }
        LOG.debug("DM References: {} page(s) pending (batch size {})", paths.size(), batchSize);
        List<Snapshot> batch = new ArrayList<>();
        try {
            for (String p : paths) {
                Snapshot s = SnapshotStore.read(rr, p);
                if (s == null || s.seq <= s.pushedSeq) {
                    SnapshotStore.clearPending(rr, p); // stale marker
                } else {
                    batch.add(s);
                }
            }
            rr.commit();
        } catch (PersistenceException e) {
            rr.revert();
            LOG.warn("DM References: could not clear stale pending markers: {}", e.getMessage());
        }
        if (batch.isEmpty()) {
            return paths.size() == batchSize;
        }

        int status = client.post(PushPayload.build(settings.instanceId(), settings.authorUrl(), batch));
        if (status < 200 || status >= 300) {
            if (status == 401 || status == 403) {
                LOG.error("DM References: Assets rejected credentials (HTTP {}). Check bearer.token and the "
                        + "user's membership in dm-reference-writers.", status);
            } else {
                LOG.warn("DM References: push of {} pages failed (HTTP {}); will retry.", batch.size(), status);
            }
            return false;
        }

        acknowledge(rr, batch);
        LOG.info("DM References: pushed {} page(s).", batch.size());
        return paths.size() == batchSize;
    }

    private void acknowledge(ResourceResolver rr, List<Snapshot> sent) {
        try {
            rr.refresh();
            for (Snapshot s : sent) {
                Snapshot current = SnapshotStore.read(rr, s.pagePath);
                if (current == null || current.seq != s.seq) {
                    continue; // changed since we read it; stays pending, next run sends the newer seq
                }
                SnapshotStore.clearPending(rr, s.pagePath);
                if (current.deleted) {
                    SnapshotStore.delete(rr, s.pagePath);
                } else {
                    current.pushedSeq = s.seq;
                    SnapshotStore.write(rr, current);
                }
            }
            rr.commit();
        } catch (PersistenceException e) {
            rr.revert();
            // Safe: pages stay pending and are re-sent; Assets ignores the already-seen seq.
            LOG.warn("DM References: could not record push acknowledgement: {}", e.getMessage());
        }
    }
}
