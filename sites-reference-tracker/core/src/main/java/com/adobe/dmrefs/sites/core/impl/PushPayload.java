package com.adobe.dmrefs.sites.core.impl;

import com.adobe.dmrefs.sites.core.model.PageUpdate;
import com.adobe.dmrefs.sites.core.model.Ref;
import com.adobe.dmrefs.sites.core.util.JsonWriter;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Builds the ingest JSON (schemaVersion 1). Each page carries the FULL reference set of ONE state (replace
 * semantics), so pushes are idempotent and order-safe given {@code seq}. Assets leaves the other state untouched:
 * a publish update carries only {@code published}, a draft update only {@code author}, and a deleted page neither.
 * {@code partial} is only written when true.
 *
 * <pre>
 * {"schemaVersion":1,"instanceId":"acme-prod","authorUrl":"https://author.acme.com","pages":[
 *   {"pagePath":"/content/acme/en","seq":1730700000000,"deleted":false,
 *    "published":[{"type":"uuid","ref":"urn:aaid:aem:..."},{"type":"vanity","ref":"summer-hero"}]}]}
 * </pre>
 */
public final class PushPayload {

    public static final int SCHEMA_VERSION = 1;

    private PushPayload() {
    }

    /** @param authorUrl normalized base URL of this author instance, or null to omit it */
    public static String build(String instanceId, String authorUrl, List<PageUpdate> pages) {
        JsonWriter w = new JsonWriter();
        w.beginObject()
                .name("schemaVersion").value(SCHEMA_VERSION)
                .name("instanceId").value(instanceId);
        if (authorUrl != null) {
            w.name("authorUrl").value(authorUrl);
        }
        w.name("pages").beginArray();
        for (PageUpdate p : pages) {
            w.beginObject()
                    .name("pagePath").value(p.getPagePath())
                    .name("seq").value(p.getSeq())
                    .name("deleted").value(p.isDeleted());
            if (p.isPartial()) {
                w.name("partial").value(true);
            }
            if (!p.isDeleted()) {
                refs(w.name(p.getState() == PageUpdate.State.AUTHOR ? "author" : "published"), p.getRefs());
            }
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
