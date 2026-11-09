package com.adobe.dmrefs.sites.core.impl;

/** JMX operations for the manual backfill: {@code com.adobe.dmrefs:type=Backfill}. No HTTP surface on Sites. */
public interface BackfillMBean {

    /** Queues every published page under {@code root} (a content path) for a push. Re-run after a restart. */
    String start(String root);

    String status();

    String cancel();
}
