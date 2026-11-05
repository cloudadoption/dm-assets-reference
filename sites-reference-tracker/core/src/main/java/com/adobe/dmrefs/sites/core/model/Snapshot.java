package com.adobe.dmrefs.sites.core.model;

import java.util.Calendar;
import java.util.Set;
import java.util.TreeSet;

/** Per-page reference snapshot kept under /var/dm-references on Sites author. */
public final class Snapshot {

    public String pagePath;
    public Set<Ref> authorRefs = new TreeSet<>();
    public Set<Ref> publishedRefs = new TreeSet<>();
    public Calendar publishedAt;
    public boolean deleted;
    /** Monotonic version of this snapshot; the Assets side ignores pushes with a seq it has already seen. */
    public long seq;
    /** Last seq acknowledged by Assets. */
    public long pushedSeq;

    public Snapshot(String pagePath) {
        this.pagePath = pagePath;
    }

    public boolean isEmpty() {
        return authorRefs.isEmpty() && publishedRefs.isEmpty();
    }

    public boolean sameContentAs(Snapshot o) {
        return o != null && deleted == o.deleted && authorRefs.equals(o.authorRefs)
                && publishedRefs.equals(o.publishedRefs);
    }

    public Snapshot copy() {
        Snapshot s = new Snapshot(pagePath);
        s.authorRefs = new TreeSet<>(authorRefs);
        s.publishedRefs = new TreeSet<>(publishedRefs);
        s.publishedAt = publishedAt == null ? null : (Calendar) publishedAt.clone();
        s.deleted = deleted;
        s.seq = seq;
        s.pushedSeq = pushedSeq;
        return s;
    }

    /** Next seq: wall-clock millis, but always strictly greater than the previous one. */
    public static long nextSeq(long previous) {
        return Math.max(System.currentTimeMillis(), previous + 1);
    }
}
