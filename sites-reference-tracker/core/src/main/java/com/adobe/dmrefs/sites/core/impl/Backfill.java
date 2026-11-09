package com.adobe.dmrefs.sites.core.impl;

import com.adobe.dmrefs.sites.core.util.Hashes;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.event.jobs.Job;
import org.apache.sling.event.jobs.JobManager;
import org.apache.sling.event.jobs.consumer.JobExecutionContext;
import org.apache.sling.event.jobs.consumer.JobExecutionResult;
import org.apache.sling.event.jobs.consumer.JobExecutor;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Manual backfill of pages that were already published before the tracker was installed.
 *
 * <p>The JMX operations validate the root and queue one job. The job walks the tree under the root and queues every
 * page whose last replication action is Activate as normal (undelayed) publish jobs, one chunk at a time with a
 * pause between chunks. Not resumable: run it again after a restart; Assets applies by seq, so repeats are
 * harmless. Progress and the cancel flag live in memory.
 */
@Component(service = {BackfillMBean.class, JobExecutor.class}, immediate = true, property = {
        "jmx.objectname=com.adobe.dmrefs:type=Backfill",
        JobExecutor.PROPERTY_TOPICS + "=" + Jobs.TOPIC_BACKFILL
})
public class Backfill implements BackfillMBean, JobExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(Backfill.class);

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Reference
    private JobManager jobManager;

    @Reference
    private TrackerSettings settings;

    private String runId;
    private String root;
    private String current = "";
    private long startedAt;
    private long pagesQueued;
    private boolean running;
    private boolean cancelled;

    @Override
    public String start(String requestedRoot) {
        if (!settings.canPush()) {
            return "Not started: the tracker is not fully configured.";
        }
        if (!Hashes.isValidContentPath(requestedRoot)
                || !Hashes.isUnderAnyRoot(requestedRoot, settings.contentRoots())) {
            return "Not started: root must be a content path under the configured content roots.";
        }
        String id = begin(requestedRoot);
        if (id == null) {
            return "Not started: a backfill is already running. " + status();
        }
        Map<String, Object> props = new HashMap<>();
        props.put(Jobs.P_ROOT, requestedRoot);
        props.put(Jobs.P_RUN_ID, id);
        if (jobManager.addJob(Jobs.TOPIC_BACKFILL, props) == null) {
            finish();
            return "Not started: could not queue the backfill job.";
        }
        return "Started backfill of " + requestedRoot + " (run " + id + ").";
    }

    @Override
    public synchronized String status() {
        if (runId == null) {
            return "no backfill has run";
        }
        return (running ? (cancelled ? "cancelling" : "running") : "finished") + " root=" + root + " runId=" + runId
                + " pagesQueued=" + pagesQueued + " current=" + current + " startedAt=" + startedAt;
    }

    @Override
    public synchronized String cancel() {
        if (running) {
            cancelled = true;
        }
        return status();
    }

    @Override
    public JobExecutionResult process(Job job, JobExecutionContext ctx) {
        String jobRoot = job.getProperty(Jobs.P_ROOT, String.class);
        String id = job.getProperty(Jobs.P_RUN_ID, String.class);
        if (jobRoot == null || id == null) {
            return ctx.result().message("missing root or runId").cancelled();
        }
        try (ResourceResolver rr = ServiceResolvers.open(resolverFactory)) {
            Resource start = rr.getResource(jobRoot);
            if (start == null) {
                finish();
                return ctx.result().message("root not found").cancelled();
            }
            boolean completed = walk(start, id, ctx);
            finish();
            if (!completed) {
                return ctx.result().message("cancelled").cancelled();
            }
            LOG.info("DM References: backfill of {} finished: {}", jobRoot, status());
            return ctx.result().message(status()).succeeded();
        } catch (LoginException e) {
            LOG.error("DM References: service user login failed; check repoinit and service user mapping.");
            finish();
            return ctx.result().message("login failed").cancelled();
        }
    }

    /** @return false if the run was cancelled or interrupted. */
    private boolean walk(Resource start, String id, JobExecutionContext ctx) {
        int chunk = settings.pushBatchSize();
        List<String> batch = new ArrayList<>();
        Deque<Iterator<Resource>> stack = new ArrayDeque<>();
        consider(start, batch);
        stack.push(start.listChildren());
        while (!stack.isEmpty()) {
            if (ctx.isStopped() || isCancelled(id)) {
                return false;
            }
            Iterator<Resource> it = stack.peek();
            if (!it.hasNext()) {
                stack.pop();
                continue;
            }
            Resource child = it.next();
            if ("jcr:content".equals(child.getName())) {
                continue;
            }
            consider(child, batch);
            stack.push(child.listChildren());
            if (batch.size() >= chunk && !flush(batch, child.getPath())) {
                return false;
            }
        }
        return batch.isEmpty() || flush(batch, "");
    }

    private static void consider(Resource r, List<String> batch) {
        if (!"cq:Page".equals(r.getValueMap().get("jcr:primaryType", String.class))) {
            return;
        }
        Resource content = r.getChild("jcr:content");
        if (content != null && "Activate".equals(content.getValueMap().get("cq:lastReplicationAction", String.class))) {
            batch.add(r.getPath());
        }
    }

    private boolean flush(List<String> batch, String currentPath) {
        Jobs.enqueuePublish(jobManager, batch.toArray(new String[0]), 0);
        progress(currentPath, batch.size());
        batch.clear();
        try {
            Thread.sleep(settings.backfillPauseMs());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** @return the new run ID, or null if a run is already active. */
    private synchronized String begin(String newRoot) {
        if (running) {
            return null;
        }
        runId = Long.toHexString(System.nanoTime());
        root = newRoot;
        current = "";
        startedAt = System.currentTimeMillis();
        pagesQueued = 0;
        cancelled = false;
        running = true;
        return runId;
    }

    private synchronized void progress(String path, int queuedMore) {
        current = path;
        pagesQueued += queuedMore;
    }

    private synchronized void finish() {
        running = false;
    }

    private synchronized boolean isCancelled(String id) {
        return cancelled || !running || !id.equals(runId);
    }
}
