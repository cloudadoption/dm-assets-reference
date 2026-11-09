package com.adobe.dmrefs.sites.core.impl;

import org.apache.sling.event.jobs.Job;
import org.apache.sling.event.jobs.JobManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/** Job topics and property names shared by the listener, backfill and consumers. */
public final class Jobs {

    private static final Logger LOG = LoggerFactory.getLogger(Jobs.class);

    public static final String TOPIC_PUBLISH = "com/adobe/dmrefs/publish";
    public static final String TOPIC_DRAFT = "com/adobe/dmrefs/draft";
    public static final String TOPIC_BACKFILL = "com/adobe/dmrefs/backfill";
    public static final String P_PATHS = "paths";
    public static final String P_ENQUEUED_AT = "enqueuedAt";
    public static final String P_ROOT = "root";
    public static final String P_RUN_ID = "runId";

    private Jobs() {
    }

    /** Queues one draft job for these paths, to run right away (the sweeper already waited for the edits to settle). */
    public static void enqueueDraft(JobManager jm, String[] paths) {
        Map<String, Object> props = new HashMap<>();
        props.put(P_PATHS, paths);
        props.put(P_ENQUEUED_AT, System.currentTimeMillis());
        if (jm.addJob(TOPIC_DRAFT, props) == null) {
            LOG.warn("DM References: could not enqueue draft job for {} path(s)", paths.length);
        }
    }

    /**
     * Queues one publish job for these paths, run {@code delaySeconds} from now (0 = immediately).
     * Scheduled jobs are persisted by Sling, so they survive a restart.
     */
    public static void enqueuePublish(JobManager jm, String[] paths, int delaySeconds) {
        long now = System.currentTimeMillis();
        Map<String, Object> props = new HashMap<>();
        props.put(P_PATHS, paths);
        props.put(P_ENQUEUED_AT, now);
        if (delaySeconds <= 0) {
            Job job = jm.addJob(TOPIC_PUBLISH, props);
            if (job == null) {
                LOG.warn("DM References: could not enqueue publish job for {} path(s)", paths.length);
            }
            return;
        }
        Object scheduled = jm.createJob(TOPIC_PUBLISH).properties(props)
                .schedule().at(new Date(now + delaySeconds * 1000L)).add();
        if (scheduled == null) {
            LOG.warn("DM References: could not schedule publish job for {} path(s)", paths.length);
        }
    }
}
