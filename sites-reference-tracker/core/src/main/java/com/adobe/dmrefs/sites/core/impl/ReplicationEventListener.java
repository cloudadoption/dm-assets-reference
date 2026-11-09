package com.adobe.dmrefs.sites.core.impl;

import com.adobe.dmrefs.sites.core.util.Hashes;
import com.day.cq.replication.ReplicationAction;
import org.apache.sling.event.jobs.JobManager;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventConstants;
import org.osgi.service.event.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Publish / unpublish / delete on author -> delayed publish jobs. Fires when replication is requested, not when
 * delivered. The event only says which paths changed; the job reads the page's current replication status.
 */
@Component(service = EventHandler.class,
        property = EventConstants.EVENT_TOPIC + "=" + ReplicationAction.EVENT_TOPIC)
public class ReplicationEventListener implements EventHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ReplicationEventListener.class);

    /** Set by Sling on events distributed from other cluster nodes. */
    private static final String EVENT_APPLICATION = "event.application";

    @Reference
    private JobManager jobManager;

    @Reference
    private TrackerSettings settings;

    @Override
    public void handleEvent(Event event) {
        if (!settings.canPush() || event.getProperty(EVENT_APPLICATION) != null) {
            LOG.debug("DM References: replication event ignored; tracker not ready or event from another node.");
            return;
        }
        ReplicationAction action = ReplicationAction.fromEvent(event);
        if (action == null || action.getType() == null) {
            LOG.debug("DM References: replication event ignored; no replication action or type.");
            return;
        }
        switch (action.getType()) {
            case ACTIVATE:
            case DEACTIVATE:
            case DELETE:
                break;
            default:
                LOG.debug("DM References: replication type {} not tracked.", action.getType());
                return;
        }
        String[] roots = settings.contentRoots();
        String[] paths = action.getPaths();
        if (paths == null) {
            paths = new String[] {action.getPath()};
        }
        List<String> valid = new ArrayList<>();
        for (String p : paths) {
            // Non-page paths (assets, XFs under other roots) are filtered here or ignored by the consumer.
            if (Hashes.isValidContentPath(p) && Hashes.isUnderAnyRoot(p, roots)) {
                valid.add(p);
            }
        }
        int chunk = settings.pushBatchSize();
        for (int i = 0; i < valid.size(); i += chunk) {
            List<String> part = valid.subList(i, Math.min(valid.size(), i + chunk));
            Jobs.enqueuePublish(jobManager, part.toArray(new String[0]), settings.jobDelaySeconds());
        }
        LOG.debug("DM References: replication {} -> {} path(s) queued", action.getType(), valid.size());
    }
}
