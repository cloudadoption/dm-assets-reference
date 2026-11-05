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

/**
 * Publish / unpublish on author -> page jobs. Fires when replication is triggered (not when delivered);
 * the reconcile task corrects drift using the page's replication status.
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
        if (!settings.isEnabled() || event.getProperty(EVENT_APPLICATION) != null) {
            LOG.debug("DM References: replication event ignored; tracker disabled or event from another node.");
            return;
        }
        ReplicationAction action = ReplicationAction.fromEvent(event);
        if (action == null || action.getType() == null) {
            LOG.debug("DM References: replication event ignored; no replication action or type.");
            return;
        }
        LOG.debug("DM References: replication event {} for {}", action.getType(), action.getPath());
        Jobs.Action jobAction;
        switch (action.getType()) {
            case ACTIVATE:
                jobAction = Jobs.Action.PUBLISHED;
                break;
            case DEACTIVATE:
            case DELETE:
                jobAction = Jobs.Action.UNPUBLISHED;
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
        for (String p : paths) {
            // Non-page paths (assets, XFs under other roots) are filtered here or ignored by the consumer.
            if (Hashes.isValidContentPath(p) && Hashes.isUnderAnyRoot(p, roots)) {
                Jobs.enqueue(jobManager, p, jobAction);
            } else {
                LOG.debug("DM References: skipped {} for {}; invalid path or outside content roots.", jobAction, p);
            }
        }
    }
}
