package com.adobe.dmrefs.sites.core.impl;

import org.apache.sling.event.jobs.Job;
import org.apache.sling.event.jobs.JobManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/** Job topic and actions shared by the listeners, reconcile task and consumer. */
public final class Jobs {

    private static final Logger LOG = LoggerFactory.getLogger(Jobs.class);

    public static final String TOPIC = "com/adobe/dmrefs/page";
    public static final String P_PATH = "pagePath";
    public static final String P_ACTION = "action";

    public enum Action {
        /** Author content changed (create/modify/restore/move-destination). */
        AUTHOR_CHANGED,
        /** Page activated: snapshot current refs as the published set. */
        PUBLISHED,
        /** Page deactivated / deleted from publish. */
        UNPUBLISHED,
        /** Page deleted on author (or move source). */
        DELETED,
        /** Recompute from scratch, using replication status for the published set. */
        RECONCILE
    }

    private Jobs() {
    }

    public static void enqueue(JobManager jm, String pagePath, Action action) {
        Map<String, Object> props = new HashMap<>();
        props.put(P_PATH, pagePath);
        props.put(P_ACTION, action.name());
        Job job = jm.addJob(TOPIC, props);
        if (job == null) {
            LOG.warn("DM References: could not enqueue {} job for {}", action, pagePath);
        } else {
            LOG.debug("DM References: enqueued {} for {} (job {})", action, pagePath, job.getId());
        }
    }
}
