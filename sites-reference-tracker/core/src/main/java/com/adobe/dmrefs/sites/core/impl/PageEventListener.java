package com.adobe.dmrefs.sites.core.impl;

import com.adobe.dmrefs.sites.core.util.Hashes;
import com.day.cq.wcm.api.PageEvent;
import com.day.cq.wcm.api.PageModification;
import org.apache.sling.event.jobs.JobManager;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventConstants;
import org.osgi.service.event.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Iterator;

/** Author-side page lifecycle events -> page jobs. Does no repository work itself. */
@Component(service = EventHandler.class,
        property = EventConstants.EVENT_TOPIC + "=" + PageEvent.EVENT_TOPIC)
public class PageEventListener implements EventHandler {

    private static final Logger LOG = LoggerFactory.getLogger(PageEventListener.class);

    @Reference
    private JobManager jobManager;

    @Reference
    private TrackerSettings settings;

    @Override
    public void handleEvent(Event event) {
        if (!settings.isEnabled()) {
            LOG.debug("DM References: page event ignored; tracker is disabled.");
            return;
        }
        PageEvent pe = PageEvent.fromEvent(event);
        if (pe == null) {
            LOG.debug("DM References: page event ignored; not convertible to PageEvent.");
            return;
        }
        // Only the cluster node where the change happened enqueues; Sling jobs are cluster-wide.
        if (!pe.isLocal()) {
            LOG.debug("DM References: page event ignored; not local to this node.");
            return;
        }
        String[] roots = settings.contentRoots();
        LOG.debug("DM References: page event received, content roots {}", Arrays.toString(roots));
        Iterator<PageModification> it = pe.getModifications();
        while (it.hasNext()) {
            PageModification m = it.next();
            String path = m.getPath();
            LOG.debug("DM References: page modification {} {}", m.getType(), path);
            switch (m.getType()) {
                case CREATED:
                case MODIFIED:
                case RESTORED:
                    enqueue(path, Jobs.Action.AUTHOR_CHANGED, roots);
                    break;
                case DELETED:
                    enqueue(path, Jobs.Action.DELETED, roots);
                    break;
                case MOVED:
                    enqueue(path, Jobs.Action.DELETED, roots);
                    enqueue(m.getDestination(), Jobs.Action.AUTHOR_CHANGED, roots);
                    break;
                default:
                    break;
            }
        }
    }

    private void enqueue(String path, Jobs.Action action, String[] roots) {
        if (Hashes.isValidContentPath(path) && Hashes.isUnderAnyRoot(path, roots)) {
            Jobs.enqueue(jobManager, path, action);
        } else {
            LOG.debug("DM References: skipped {} for {}; invalid path or outside content roots.", action, path);
        }
    }
}
