package com.adobe.dmrefs.sites.core.model;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/**
 * One page as sent to Assets: the full reference set of ONE state (replace semantics). Assets leaves the other
 * state untouched. An empty set makes Assets drop that state's rows for the page. A deleted page clears both.
 */
public final class PageUpdate {

    /** AUTHOR is the draft set (current author content); PUBLISHED is what is live. */
    public enum State { AUTHOR, PUBLISHED }

    private final String pagePath;
    private final long seq;
    private final boolean deleted;
    private final boolean partial;
    private final State state;
    private final Set<Ref> refs;

    private PageUpdate(String pagePath, long seq, boolean deleted, boolean partial, State state, Set<Ref> refs) {
        this.pagePath = pagePath;
        this.seq = seq;
        this.deleted = deleted;
        this.partial = partial;
        this.state = state;
        this.refs = Collections.unmodifiableSet(new TreeSet<>(refs));
    }

    /** The live set. Empty means the page is not live. */
    public static PageUpdate published(String pagePath, long seq, boolean partial, Set<Ref> refs) {
        return new PageUpdate(pagePath, seq, false, partial, State.PUBLISHED, refs);
    }

    /** The draft set, i.e. what the page on author references now. */
    public static PageUpdate draft(String pagePath, long seq, boolean partial, Set<Ref> refs) {
        return new PageUpdate(pagePath, seq, false, partial, State.AUTHOR, refs);
    }

    /** The page no longer exists on author: Assets clears both states. */
    public static PageUpdate deleted(String pagePath, long seq) {
        return new PageUpdate(pagePath, seq, true, false, State.PUBLISHED, Collections.<Ref>emptySet());
    }

    public String getPagePath() {
        return pagePath;
    }

    public long getSeq() {
        return seq;
    }

    public boolean isDeleted() {
        return deleted;
    }

    /** The scan was cut short: Assets must only add references, never remove any. */
    public boolean isPartial() {
        return partial;
    }

    public State getState() {
        return state;
    }

    public Set<Ref> getRefs() {
        return refs;
    }
}
