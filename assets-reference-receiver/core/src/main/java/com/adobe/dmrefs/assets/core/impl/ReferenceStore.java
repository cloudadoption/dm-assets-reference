package com.adobe.dmrefs.assets.core.impl;

import com.adobe.dmrefs.assets.core.util.Paths;
import com.adobe.dmrefs.assets.core.validate.PayloadValidator.RefType;
import com.adobe.dmrefs.assets.core.validate.PayloadValidator.ValidPage;
import com.adobe.dmrefs.assets.core.validate.PayloadValidator.ValidRef;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import javax.jcr.Session;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Hot reference store under /var/dm-references on Assets author. One node per (asset, instance, page);
 * the asset node in /content/dam is never touched.
 *
 * <pre>
 * /var/dm-references
 *   assets/&lt;u0u1&gt;/&lt;u2u3&gt;/&lt;uuid&gt;/&lt;instanceId&gt;/&lt;sha256(pagePath)&gt;
 *       instanceId, pagePath, assetId, author, published, referencedAs[], authorUrl (optional), updatedAt
 *   unresolved/&lt;v0v1&gt;/&lt;sha256(vanity)&gt;/&lt;instanceId&gt;/&lt;sha256(pagePath)&gt;
 *       instanceId, pagePath, vanity, author, published, updatedAt
 *   pages/&lt;instanceId&gt;/&lt;p0p1&gt;/&lt;sha256(pagePath)&gt;
 *       instanceId, pagePath, seq, authorSeq, publishedSeq, deleted, keys[] (relative paths of the entries above), updatedAt
 * </pre>
 *
 * Writes for a page replace that page's previous entries (computed from the page index), so the store
 * converges regardless of how many times a page is re-sent. The draft (author) and published sets are
 * independent: a set that is absent from an update is left unchanged, and each has its own seq. An entry is
 * deleted only when both of its flags are false. A partial page only adds: nothing is deleted and
 * flags are never cleared. A page the store has never seen, sent with no references, writes nothing.
 * Nodes are oak:Unstructured because popular assets collect very large child lists, which are slow on
 * orderable node types. Callers own the resolver and must commit.
 */
@Component(service = ReferenceStore.class)
public class ReferenceStore {

    public static final String ROOT = "/var/dm-references";

    @Reference
    private VanityResolver vanityResolver;

    public enum Outcome { APPLIED, STALE }

    public static final class Applied {
        public final Outcome outcome;
        public final int unresolved;

        Applied(Outcome outcome, int unresolved) {
            this.outcome = outcome;
            this.unresolved = unresolved;
        }
    }

    /** One reference row, as returned by the read endpoint. */
    public static final class Entry {
        public String instanceId;
        public String pagePath;
        public boolean author;
        public boolean published;
        public Set<String> referencedAs = new TreeSet<>();
        public String authorUrl;
        public Calendar updatedAt;
        public boolean resolved = true;
    }

    private static final class Pending {
        final Map<String, Object> props = new HashMap<>();
        final Set<String> referencedAs = new TreeSet<>();
        boolean author;
        boolean published;
    }

    /** @param authorUrl normalized base URL of the sending author instance, or null if it sent none */
    public Applied apply(ResourceResolver rr, String instanceId, String authorUrl, ValidPage page,
                         Map<String, String> vanityCache) throws PersistenceException {
        String pageHash = Paths.sha256(page.pagePath);
        String indexPath = ROOT + "/pages/" + instanceId + "/" + pageHash.substring(0, 2) + "/" + pageHash;
        Resource index = rr.getResource(indexPath);
        Set<String> oldKeys = new HashSet<>();
        long storedAuthorSeq = 0L;
        long storedPublishedSeq = 0L;
        if (index != null) {
            ValueMap ivm = index.getValueMap();
            long legacySeq = ivm.get("seq", 0L);
            storedAuthorSeq = ivm.get("authorSeq", legacySeq);
            storedPublishedSeq = ivm.get("publishedSeq", legacySeq);
            oldKeys.addAll(Arrays.asList(ivm.get("keys", new String[0])));
        }
        boolean doAuthor = touchesAuthor(page) && page.seq > storedAuthorSeq;
        boolean doPublished = touchesPublished(page) && page.seq > storedPublishedSeq;
        if (!doAuthor && !doPublished) {
            return new Applied(Outcome.STALE, 0);
        }
        if (isNoOp(index != null, page)) {
            return new Applied(Outcome.APPLIED, 0);
        }

        Session session = rr.adaptTo(Session.class);
        Map<String, Pending> next = new LinkedHashMap<>();
        int unresolved = 0;
        if (doAuthor) {
            unresolved += collect(page.author, true, instanceId, page.pagePath, pageHash, session, vanityCache, next);
        }
        if (doPublished) {
            unresolved += collect(page.published, false, instanceId, page.pagePath, pageHash, session, vanityCache, next);
        }

        Calendar now = Calendar.getInstance();
        Set<String> keep = new TreeSet<>();
        Set<String> all = new TreeSet<>(oldKeys);
        all.addAll(next.keySet());
        for (String key : all) {
            Pending p = next.get(key);
            Resource existing = rr.getResource(ROOT + "/" + key);
            ValueMap ev = existing == null ? null : existing.getValueMap();
            boolean[] f = resolveFlags(doAuthor, doPublished, page.partial,
                    ev != null && ev.get("author", false), ev != null && ev.get("published", false),
                    p != null && p.author, p != null && p.published);
            if (!f[0] && !f[1]) {
                if (existing != null) {
                    deleteAndPrune(rr, ROOT + "/" + key);
                }
                continue;
            }
            keep.add(key);
            Resource r = existing != null ? existing : ensure(rr, ROOT + "/" + key);
            ModifiableValueMap mvm = r.adaptTo(ModifiableValueMap.class);
            if (mvm == null) {
                throw new PersistenceException("Not modifiable: " + r.getPath());
            }
            if (p != null) {
                mvm.putAll(p.props);
                Set<String> referencedAs = new TreeSet<>(p.referencedAs);
                boolean otherStateKeepsIt = (!doAuthor && f[0]) || (!doPublished && f[1]);
                if (ev != null && (page.partial || otherStateKeepsIt)) {
                    referencedAs.addAll(Arrays.asList(ev.get("referencedAs", new String[0])));
                }
                if (!referencedAs.isEmpty()) {
                    mvm.put("referencedAs", referencedAs.toArray(new String[0]));
                }
            }
            if (authorUrl != null) {
                mvm.put("authorUrl", authorUrl);
            } else {
                mvm.remove("authorUrl");
            }
            mvm.put("author", f[0]);
            mvm.put("published", f[1]);
            mvm.put("updatedAt", now);
        }

        Resource idx = ensure(rr, indexPath);
        ModifiableValueMap ivm = idx.adaptTo(ModifiableValueMap.class);
        if (ivm == null) {
            throw new PersistenceException("Not modifiable: " + indexPath);
        }
        long authorSeq = doAuthor ? page.seq : storedAuthorSeq;
        long publishedSeq = doPublished ? page.seq : storedPublishedSeq;
        ivm.put("instanceId", instanceId);
        ivm.put("pagePath", page.pagePath);
        ivm.put("authorSeq", authorSeq);
        ivm.put("publishedSeq", publishedSeq);
        ivm.put("seq", Math.max(authorSeq, publishedSeq));
        ivm.put("deleted", page.deleted);
        ivm.put("keys", keep.toArray(new String[0]));
        ivm.put("updatedAt", now);
        return new Applied(Outcome.APPLIED, unresolved);
    }

    /** Never-seen page with nothing to record: skip, so every unpublish of an untracked page leaves no node. */
    public static boolean isNoOp(boolean indexExists, ValidPage page) {
        return !indexExists && page.author.isEmpty() && page.published.isEmpty();
    }

    /** The update replaces the draft set (a deleted page clears both sets). */
    public static boolean touchesAuthor(ValidPage page) {
        return page.deleted || page.authorPresent;
    }

    /** The update replaces the published set (a deleted page clears both sets). */
    public static boolean touchesPublished(ValidPage page) {
        return page.deleted || page.publishedPresent;
    }

    /**
     * New {author, published} flags of one entry. A state that is not being updated keeps its flag; an updated
     * state takes the new set, or only gains flags when the scan was partial. The entry is deleted when both are false.
     */
    public static boolean[] resolveFlags(boolean updateAuthor, boolean updatePublished, boolean partial,
                                         boolean existingAuthor, boolean existingPublished,
                                         boolean inAuthor, boolean inPublished) {
        boolean author = updateAuthor ? (partial ? existingAuthor || inAuthor : inAuthor) : existingAuthor;
        boolean published = updatePublished ? (partial ? existingPublished || inPublished : inPublished)
                : existingPublished;
        return new boolean[] {author, published};
    }

    private int collect(Set<ValidRef> refs, boolean isAuthor, String instanceId, String pagePath, String pageHash,
                        Session session, Map<String, String> vanityCache, Map<String, Pending> out) {
        int unresolved = 0;
        for (ValidRef ref : refs) {
            String key;
            Pending p;
            if (ref.type == RefType.UUID) {
                key = assetKey(ref.value, instanceId, pageHash);
                p = out.computeIfAbsent(key, k -> new Pending());
                p.props.put("assetId", Paths.URN_PREFIX + ref.value);
                p.referencedAs.add(Paths.URN_PREFIX + ref.value);
            } else {
                String uuid = session == null ? null : vanityResolver.resolve(session, ref.value, vanityCache);
                if (uuid != null) {
                    key = assetKey(uuid, instanceId, pageHash);
                    p = out.computeIfAbsent(key, k -> new Pending());
                    p.props.put("assetId", Paths.URN_PREFIX + uuid);
                } else {
                    String vh = Paths.sha256(ref.value);
                    key = "unresolved/" + vh.substring(0, 2) + "/" + vh + "/" + instanceId + "/" + pageHash;
                    p = out.computeIfAbsent(key, k -> new Pending());
                    p.props.put("vanity", ref.value);
                    unresolved++;
                }
                p.referencedAs.add("vanity:" + ref.value);
            }
            p.props.put("instanceId", instanceId);
            p.props.put("pagePath", pagePath);
            if (isAuthor) {
                p.author = true;
            } else {
                p.published = true;
            }
        }
        return unresolved;
    }

    static String assetKey(String uuid, String instanceId, String pageHash) {
        return "assets/" + uuid.substring(0, 2) + "/" + uuid.substring(2, 4) + "/" + uuid + "/"
                + instanceId + "/" + pageHash;
    }

    /** All references to the asset with this bare UUID. */
    public List<Entry> forAsset(ResourceResolver rr, String uuid, int max) {
        return read(rr.getResource(ROOT + "/assets/" + uuid.substring(0, 2) + "/" + uuid.substring(2, 4)
                + "/" + uuid), true, max);
    }

    /** References that used this vanity ID but could not be resolved at ingest time. */
    public List<Entry> forUnresolvedVanity(ResourceResolver rr, String vanity, int max) {
        String vh = Paths.sha256(vanity);
        return read(rr.getResource(ROOT + "/unresolved/" + vh.substring(0, 2) + "/" + vh), false, max);
    }

    private List<Entry> read(Resource assetNode, boolean resolved, int max) {
        List<Entry> out = new ArrayList<>();
        if (assetNode == null) {
            return out;
        }
        for (Resource inst : assetNode.getChildren()) {
            for (Resource ref : inst.getChildren()) {
                if (out.size() >= max) {
                    return out;
                }
                ValueMap vm = ref.getValueMap();
                Entry e = new Entry();
                e.instanceId = vm.get("instanceId", String.class);
                e.pagePath = vm.get("pagePath", String.class);
                e.author = vm.get("author", false);
                e.published = vm.get("published", false);
                e.referencedAs.addAll(Arrays.asList(vm.get("referencedAs", new String[0])));
                e.updatedAt = vm.get("updatedAt", Calendar.class);
                e.authorUrl = vm.get("authorUrl", String.class);
                e.resolved = resolved;
                out.add(e);
            }
        }
        return out;
    }

    /** Deletes the entry node, then its instance and asset/vanity parents if they became empty. */
    private static void deleteAndPrune(ResourceResolver rr, String path) throws PersistenceException {
        Resource r = rr.getResource(path);
        if (r == null) {
            return;
        }
        Resource inst = r.getParent();
        rr.delete(r);
        if (inst != null && !inst.hasChildren()) {
            Resource owner = inst.getParent();
            rr.delete(inst);
            if (owner != null && !owner.hasChildren()) {
                rr.delete(owner);
            }
        }
    }

    private static Resource ensure(ResourceResolver rr, String path) throws PersistenceException {
        Resource existing = rr.getResource(path);
        if (existing != null) {
            return existing;
        }
        int idx = path.lastIndexOf('/');
        Resource parent = ensure(rr, path.substring(0, idx));
        Map<String, Object> props = new HashMap<>();
        props.put("jcr:primaryType", "oak:Unstructured");
        return rr.create(parent, path.substring(idx + 1), props);
    }
}
