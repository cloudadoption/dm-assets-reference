package com.adobe.dmrefs.sites.core.impl;

import com.adobe.dmrefs.sites.core.util.Coalescer;
import com.adobe.dmrefs.sites.core.util.Hashes;
import com.day.cq.wcm.api.PageEvent;
import com.day.cq.wcm.api.PageModification;
import org.apache.sling.event.jobs.JobManager;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventConstants;
import org.osgi.service.event.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.List;

/**
 * Draft tracking, in two halves that share one in-memory list of edited pages.
 *
 * <p>As an {@link EventHandler} it notes each edited page. It runs on every edit, so it does no JCR access and
 * logs nothing above DEBUG.
 *
 * <p>As a {@link Runnable} (every 15 s) it queues the pages whose editing has settled, one draft job per chunk.
 * With nothing due it does nothing: no JCR read and no HTTP call. It runs on every cluster node, because each node
 * keeps its own list.
 */
@Component(service = {EventHandler.class, Runnable.class}, immediate = true, property = {
        EventConstants.EVENT_TOPIC + "=" + PageEvent.EVENT_TOPIC,
        "scheduler.period:Long=15",
        "scheduler.concurrent:Boolean=false"
})
public class DraftTracker implements EventHandler, Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(DraftTracker.class);

    @Reference
    private TrackerSettings settings;

    @Reference
    private JobManager jobManager;

    private volatile Coalescer edited;

    @Activate
    protected void activate() {
        edited = new Coalescer(System::currentTimeMillis, settings.draftQuietMs(), settings.draftMaxWaitMs(),
                settings.draftMaxEntries());
    }

    @Override
    public void handleEvent(Event event) {
        if (!settings.isDraftEnabled()) {
            return;
        }
        PageEvent pageEvent = PageEvent.fromEvent(event);
        if (pageEvent == null || !pageEvent.isLocal()) {
            return;
        }
        String[] roots = settings.contentRoots();
        String[] ignored = settings.draftIgnoredUsers();
        Iterator<PageModification> it = pageEvent.getModifications();
        while (it.hasNext()) {
            PageModification m = it.next();
            if (LOG.isDebugEnabled()) {
                LOG.debug("DM References: page {} {} by user '{}'", m.getType(), m.getPath(), m.getUserId());
            }
            if (Coalescer.matchesUser(m.getUserId(), ignored)) {
                continue;
            }
            switch (m.getType()) {
                case CREATED:
                case MODIFIED:
                case RESTORED:
                case DELETED:
                    touch(m.getPath(), roots);
                    break;
                case MOVED:
                    touch(m.getDestination(), roots);
                    touch(m.getPath(), roots);
                    break;
                default:
                    break;
            }
        }
    }

    private void touch(String path, String[] roots) {
        if (Hashes.isValidContentPath(path) && Hashes.isUnderAnyRoot(path, roots)) {
            edited.touch(path);
        }
    }

    @Override
    public void run() {
        if (!settings.isDraftEnabled()) {
            return;
        }
        edited.configure(settings.draftQuietMs(), settings.draftMaxWaitMs(), settings.draftMaxEntries());
        List<String> due = edited.drainDue();
        if (due.isEmpty()) {
            return;
        }
        int chunk = settings.pushBatchSize();
        for (int i = 0; i < due.size(); i += chunk) {
            Jobs.enqueueDraft(jobManager, due.subList(i, Math.min(due.size(), i + chunk)).toArray(new String[0]));
        }
        LOG.debug("DM References: {} edited page(s) settled and queued", due.size());
    }
}
