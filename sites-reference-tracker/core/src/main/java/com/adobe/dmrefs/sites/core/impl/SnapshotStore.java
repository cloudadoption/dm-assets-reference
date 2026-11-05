package com.adobe.dmrefs.sites.core.impl;

import com.adobe.dmrefs.sites.core.model.Ref;
import com.adobe.dmrefs.sites.core.model.Snapshot;
import com.adobe.dmrefs.sites.core.util.Hashes;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Persistence for snapshots and the pending-push queue.
 *
 * <pre>
 * /var/dm-references
 *   pages/&lt;h0h1&gt;/&lt;sha256(pagePath)&gt;   pagePath, authorRefs[], publishedRefs[], publishedAt, deleted, seq, pushedSeq
 *   pending/&lt;sha256(pagePath)&gt;           pagePath           (marker: snapshot changed, needs push)
 *   state                                lastReconcile
 * </pre>
 *
 * Callers own the resolver and must commit.
 */
public final class SnapshotStore {

    public static final String ROOT = "/var/dm-references";
    public static final String PAGES = ROOT + "/pages";
    public static final String PENDING = ROOT + "/pending";
    public static final String STATE = ROOT + "/state";

    static final String P_PAGE_PATH = "pagePath";
    static final String P_AUTHOR = "authorRefs";
    static final String P_PUBLISHED = "publishedRefs";
    static final String P_PUBLISHED_AT = "publishedAt";
    static final String P_DELETED = "deleted";
    static final String P_SEQ = "seq";
    static final String P_PUSHED_SEQ = "pushedSeq";
    static final String P_LAST_RECONCILE = "lastReconcile";

    private SnapshotStore() {
    }

    static String snapshotPath(String pagePath) {
        String h = Hashes.sha256(pagePath);
        return PAGES + "/" + h.substring(0, 2) + "/" + h;
    }

    static String pendingPath(String pagePath) {
        return PENDING + "/" + Hashes.sha256(pagePath);
    }

    public static Snapshot read(ResourceResolver rr, String pagePath) {
        Resource r = rr.getResource(snapshotPath(pagePath));
        return r == null ? null : fromResource(r);
    }

    static Snapshot fromResource(Resource r) {
        ValueMap vm = r.getValueMap();
        String path = vm.get(P_PAGE_PATH, String.class);
        if (path == null) {
            return null;
        }
        Snapshot s = new Snapshot(path);
        s.authorRefs = decode(vm.get(P_AUTHOR, new String[0]));
        s.publishedRefs = decode(vm.get(P_PUBLISHED, new String[0]));
        s.publishedAt = vm.get(P_PUBLISHED_AT, Calendar.class);
        s.deleted = vm.get(P_DELETED, false);
        s.seq = vm.get(P_SEQ, 0L);
        s.pushedSeq = vm.get(P_PUSHED_SEQ, 0L);
        return s;
    }

    public static void write(ResourceResolver rr, Snapshot s) throws PersistenceException {
        Resource r = ensure(rr, snapshotPath(s.pagePath));
        ModifiableValueMap mvm = r.adaptTo(ModifiableValueMap.class);
        if (mvm == null) {
            throw new PersistenceException("Snapshot node not modifiable: " + r.getPath());
        }
        mvm.put(P_PAGE_PATH, s.pagePath);
        mvm.put(P_AUTHOR, encode(s.authorRefs));
        mvm.put(P_PUBLISHED, encode(s.publishedRefs));
        if (s.publishedAt != null) {
            mvm.put(P_PUBLISHED_AT, s.publishedAt);
        } else {
            mvm.remove(P_PUBLISHED_AT);
        }
        mvm.put(P_DELETED, s.deleted);
        mvm.put(P_SEQ, s.seq);
        mvm.put(P_PUSHED_SEQ, s.pushedSeq);
    }

    public static void delete(ResourceResolver rr, String pagePath) throws PersistenceException {
        Resource r = rr.getResource(snapshotPath(pagePath));
        if (r != null) {
            rr.delete(r);
        }
    }

    public static void markPending(ResourceResolver rr, String pagePath) throws PersistenceException {
        Resource r = ensure(rr, pendingPath(pagePath));
        ModifiableValueMap mvm = r.adaptTo(ModifiableValueMap.class);
        if (mvm != null) {
            mvm.put(P_PAGE_PATH, pagePath);
        }
    }

    public static void clearPending(ResourceResolver rr, String pagePath) throws PersistenceException {
        Resource r = rr.getResource(pendingPath(pagePath));
        if (r != null) {
            rr.delete(r);
        }
    }

    /** Up to {@code max} page paths waiting to be pushed. */
    public static List<String> pending(ResourceResolver rr, int max) {
        Resource root = rr.getResource(PENDING);
        if (root == null) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>();
        Iterator<Resource> it = root.listChildren();
        while (it.hasNext() && out.size() < max) {
            String p = it.next().getValueMap().get(P_PAGE_PATH, String.class);
            if (p != null) {
                out.add(p);
            }
        }
        return out;
    }

    /** Iterates every stored snapshot (used by reconcile to detect pages deleted without an event). */
    public static void forEachSnapshot(ResourceResolver rr, java.util.function.Consumer<Snapshot> fn) {
        Resource pages = rr.getResource(PAGES);
        if (pages == null) {
            return;
        }
        for (Resource shard : pages.getChildren()) {
            for (Resource snap : shard.getChildren()) {
                Snapshot s = fromResource(snap);
                if (s != null) {
                    fn.accept(s);
                }
            }
        }
    }

    public static Calendar lastReconcile(ResourceResolver rr) {
        Resource r = rr.getResource(STATE);
        return r == null ? null : r.getValueMap().get(P_LAST_RECONCILE, Calendar.class);
    }

    public static void setLastReconcile(ResourceResolver rr, Calendar when) throws PersistenceException {
        Resource r = ensure(rr, STATE);
        ModifiableValueMap mvm = r.adaptTo(ModifiableValueMap.class);
        if (mvm != null) {
            mvm.put(P_LAST_RECONCILE, when);
        }
    }

    private static String[] encode(Set<Ref> refs) {
        String[] out = new String[refs.size()];
        int i = 0;
        for (Ref r : refs) {
            out[i++] = r.encode();
        }
        return out;
    }

    private static Set<Ref> decode(String[] vals) {
        Set<Ref> out = new TreeSet<>();
        for (String v : vals) {
            Ref r = Ref.decode(v);
            if (r != null) {
                out.add(r);
            }
        }
        return out;
    }

    /** Creates missing nt:unstructured nodes below ROOT (ROOT itself is created by repoinit). */
    private static Resource ensure(ResourceResolver rr, String path) throws PersistenceException {
        Resource existing = rr.getResource(path);
        if (existing != null) {
            return existing;
        }
        int idx = path.lastIndexOf('/');
        Resource parent = ensure(rr, path.substring(0, idx));
        Map<String, Object> props = new HashMap<>();
        props.put("jcr:primaryType", "nt:unstructured");
        return rr.create(parent, path.substring(idx + 1), props);
    }
}
