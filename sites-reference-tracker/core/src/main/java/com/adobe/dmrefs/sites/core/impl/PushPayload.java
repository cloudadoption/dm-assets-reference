package com.adobe.dmrefs.sites.core.impl;

import com.adobe.dmrefs.sites.core.model.Ref;
import com.adobe.dmrefs.sites.core.model.Snapshot;
import com.adobe.dmrefs.sites.core.util.JsonWriter;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Builds the ingest JSON. Each page carries its FULL author/published sets (replace semantics),
 * so pushes are idempotent and order-safe given {@code seq}.
 *
 * <pre>
 * {"schemaVersion":1,"instanceId":"acme-prod","authorUrl":"https://author.acme.com","pages":[
 *   {"pagePath":"/content/acme/en","seq":1730700000000,"deleted":false,
 *    "author":[{"type":"uuid","ref":"urn:aaid:aem:..."},{"type":"vanity","ref":"summer-hero"}],
 *    "published":[...]}]}
 * </pre>
 */
public final class PushPayload {

    public static final int SCHEMA_VERSION = 1;

    private PushPayload() {
    }

    /** @param authorUrl normalized base URL of this author instance, or null to omit it */
    public static String build(String instanceId, String authorUrl, List<Snapshot> snapshots) {
        JsonWriter w = new JsonWriter();
        w.beginObject()
                .name("schemaVersion").value(SCHEMA_VERSION)
                .name("instanceId").value(instanceId);
        if (authorUrl != null) {
            w.name("authorUrl").value(authorUrl);
        }
        w.name("pages").beginArray();
        for (Snapshot s : snapshots) {
            w.beginObject()
                    .name("pagePath").value(s.pagePath)
                    .name("seq").value(s.seq)
                    .name("deleted").value(s.deleted);
            refs(w.name("author"), s.authorRefs);
            refs(w.name("published"), s.publishedRefs);
            w.endObject();
        }
        return w.endArray().endObject().toString();
    }

    private static void refs(JsonWriter w, Set<Ref> refs) {
        w.beginArray();
        for (Ref r : refs) {
            w.beginObject()
                    .name("type").value(r.getType().name().toLowerCase(Locale.ROOT))
                    .name("ref").value(r.getValue())
                    .endObject();
        }
        w.endArray();
    }
}
