package com.adobe.dmrefs.sites.core.impl;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.event.jobs.JobManager;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.query.Query;
import javax.jcr.query.QueryManager;
import javax.jcr.query.Row;
import javax.jcr.query.RowIterator;
import java.util.Calendar;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Safety net for missed events. Enqueues RECONCILE jobs for pages modified or replicated since the last run,
 * and DELETED jobs for snapshots whose page no longer exists.
 *
 * <p>Full rebuild: remove the {@code lastReconcile} property from /var/dm-references/state.
 * The next run then reconciles every page under the content roots.</p>
 */
@Component(service = Runnable.class, property = {
        "scheduler.concurrent:Boolean=false",
        "scheduler.runOn=SINGLE"
})
@Designate(ocd = ReconcileTask.Config.class)
public class ReconcileTask implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(ReconcileTask.class);

    @ObjectClassDefinition(name = "DM References - Reconcile task")
    public @interface Config {
        @AttributeDefinition(name = "Cron expression", description = "Quartz cron. Default: daily 02:30.")
        String scheduler_expression() default "0 30 2 * * ?";
    }

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Reference
    private JobManager jobManager;

    @Reference
    private TrackerSettings settings;

    @Override
    public void run() {
        if (!settings.isEnabled()) {
            return;
        }
        Calendar started = Calendar.getInstance();
        try (ResourceResolver rr = ServiceResolvers.open(resolverFactory)) {
            Calendar since = SnapshotStore.lastReconcile(rr);
            Session session = rr.adaptTo(Session.class);
            if (session == null) {
                LOG.error("DM References: reconcile needs a JCR-backed resolver.");
                return;
            }
            int pages = 0;
            for (String root : settings.contentRoots()) {
                pages += enqueueChangedPages(session, root, since);
            }
            final AtomicInteger orphans = new AtomicInteger();
            SnapshotStore.forEachSnapshot(rr, s -> {
                if (!s.deleted && rr.getResource(s.pagePath) == null) {
                    Jobs.enqueue(jobManager, s.pagePath, Jobs.Action.DELETED);
                    orphans.incrementAndGet();
                }
            });
            SnapshotStore.setLastReconcile(rr, started);
            rr.commit();
            LOG.info("DM References: reconcile ({}) queued {} page(s), {} orphan(s).",
                    since == null ? "full" : "incremental", pages, orphans.get());
        } catch (LoginException e) {
            LOG.error("DM References: service user login failed; check repoinit and service user mapping.");
        } catch (RepositoryException | PersistenceException e) {
            LOG.error("DM References: reconcile failed: {}", e.getMessage());
        }
    }

    private int enqueueChangedPages(Session session, String root, Calendar since) throws RepositoryException {
        // root was validated in TrackerSettings (no quotes/brackets); date is bound, not concatenated.
        // Queries cq:Page with relative jcr:content properties so the OOTB cq:Page index can serve it.
        String stmt = "SELECT [jcr:path] FROM [cq:Page] AS p WHERE ISDESCENDANTNODE(p, [" + root + "])";
        if (since != null) {
            stmt += " AND (p.[jcr:content/cq:lastModified] > $since OR p.[jcr:content/cq:lastReplicated] > $since)";
        }
        QueryManager qm = session.getWorkspace().getQueryManager();
        Query q = qm.createQuery(stmt, Query.JCR_SQL2);
        if (since != null) {
            q.bindValue("since", session.getValueFactory().createValue(since));
        }
        int n = 0;
        RowIterator rows = q.execute().getRows();
        while (rows.hasNext()) {
            Row row = rows.nextRow();
            Jobs.enqueue(jobManager, row.getPath(), Jobs.Action.RECONCILE);
            n++;
        }
        return n;
    }
}
