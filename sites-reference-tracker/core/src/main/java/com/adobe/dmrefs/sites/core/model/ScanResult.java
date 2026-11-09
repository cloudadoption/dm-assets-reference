package com.adobe.dmrefs.sites.core.model;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/** What a scan found for one page: its references, and whether the scan was cut short. */
public final class ScanResult {

    public final Set<Ref> refs;
    public final boolean partial;

    public ScanResult(Set<Ref> refs, boolean partial) {
        this.refs = Collections.unmodifiableSet(new TreeSet<>(refs));
        this.partial = partial;
    }
}
