package com.adobe.dmrefs.sites.core;

import com.adobe.dmrefs.sites.core.extract.ReferenceExtractor;
import com.adobe.dmrefs.sites.core.impl.PushPayload;
import com.adobe.dmrefs.sites.core.model.Ref;
import com.adobe.dmrefs.sites.core.model.Snapshot;
import com.adobe.dmrefs.sites.core.util.Hashes;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtractorAndPayloadTest {

    private static final String UUID = "3f2a1b4c-5d6e-4f70-8a9b-0c1d2e3f4a5b";

    private final ReferenceExtractor ex = new ReferenceExtractor(
            new String[] {
                "urn:aaid:aem:(?<uuid>[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})",
                "dm-aid--(?<uuid>[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})"
            },
            new String[] {"/adobe/assets/(?<vanity>(?!urn:)[A-Za-z0-9._~-]{1,200})/as/"});

    private Set<Ref> run(String s) {
        Set<Ref> out = new TreeSet<>();
        ex.extract(s, out);
        return out;
    }

    @Test
    void extractsOpenApiDeliveryUrlAndNormalisesCase() {
        Set<Ref> r = run("https://delivery-p1-e2.adobeaemcloud.com/adobe/assets/urn:aaid:aem:"
                + UUID.toUpperCase() + "/as/hero.jpg?width=800");
        assertEquals(Collections.singleton(new Ref(Ref.Type.UUID, "urn:aaid:aem:" + UUID)), r);
    }

    @Test
    void extractsLegacyDmAidAndRichText() {
        Set<Ref> r = run("<p><img src=\"/adobe/dynamicmedia/deliver/dm-aid--" + UUID + "/x.jpg\"></p>");
        assertTrue(r.contains(new Ref(Ref.Type.UUID, "urn:aaid:aem:" + UUID)));
    }

    @Test
    void extractsVanityButNotUrnInVanitySlot() {
        Set<Ref> r = run("https://d.example.com/adobe/assets/summer-hero/as/summer.webp");
        assertEquals(Collections.singleton(new Ref(Ref.Type.VANITY, "summer-hero")), r);
        Set<Ref> u = run("https://d.example.com/adobe/assets/urn:aaid:aem:" + UUID + "/as/x.jpg");
        assertEquals(1, u.size());
        assertEquals(Ref.Type.UUID, u.iterator().next().getType());
    }

    @Test
    void ignoresMalformedAndUnrelated() {
        assertTrue(run("/content/dam/local/image.jpg").isEmpty());
        assertTrue(run("urn:aaid:aem:not-a-uuid").isEmpty());
        assertTrue(run(null).isEmpty());
    }

    @Test
    void rejectsPatternWithoutNamedGroup() {
        assertThrows(IllegalArgumentException.class,
                () -> new ReferenceExtractor(new String[] {"urn:aaid:aem:(.*)"}, null));
    }

    @Test
    void refRoundTrip() {
        Ref r = new Ref(Ref.Type.VANITY, "a|b");
        assertEquals(r, Ref.decode(r.encode()));
        assertEquals(null, Ref.decode("bogus"));
    }

    @Test
    void contentPathValidation() {
        assertTrue(Hashes.isValidContentPath("/content/acme/en/home"));
        assertFalse(Hashes.isValidContentPath("/content/acme/../../etc"));
        assertFalse(Hashes.isValidContentPath("/apps/x"));
        assertFalse(Hashes.isValidContentPath("/content/a]b"));
        assertTrue(Hashes.isUnderAnyRoot("/content/acme/en", new String[] {"/content/acme"}));
        assertFalse(Hashes.isUnderAnyRoot("/content/acme-other", new String[] {"/content/acme"}));
    }

    @Test
    void payloadIsValidAndEscaped() {
        Snapshot s = new Snapshot("/content/acme/\"quoted\"");
        s.seq = 42;
        s.authorRefs.add(new Ref(Ref.Type.UUID, "urn:aaid:aem:" + UUID));
        s.publishedRefs.add(new Ref(Ref.Type.VANITY, "summer-hero"));
        String json = PushPayload.build("acme-prod", null, Collections.singletonList(s));
        assertEquals("{\"schemaVersion\":1,\"instanceId\":\"acme-prod\",\"pages\":[{\"pagePath\":"
                + "\"/content/acme/\\\"quoted\\\"\",\"seq\":42,\"deleted\":false,"
                + "\"author\":[{\"type\":\"uuid\",\"ref\":\"urn:aaid:aem:" + UUID + "\"}],"
                + "\"published\":[{\"type\":\"vanity\",\"ref\":\"summer-hero\"}]}]}", json);
    }

    @Test
    void payloadIncludesAuthorUrlWhenSet() {
        String json = PushPayload.build("acme-prod", "https://author.acme.com", Collections.<Snapshot>emptyList());
        assertEquals("{\"schemaVersion\":1,\"instanceId\":\"acme-prod\","
                + "\"authorUrl\":\"https://author.acme.com\",\"pages\":[]}", json);
    }

    @Test
    void authorUrlNormalisationAcceptsOnlySafeHttpsUrls() {
        assertEquals("https://author.acme.com", Hashes.normalizeAuthorUrl(" HTTPS://Author.Acme.com/ "));
        assertEquals("https://a.b:4502/aem", Hashes.normalizeAuthorUrl("https://a.b:4502/aem/"));
        assertEquals(null, Hashes.normalizeAuthorUrl("http://author.acme.com"));
        assertEquals(null, Hashes.normalizeAuthorUrl("https://user:pw@author.acme.com"));
        assertEquals(null, Hashes.normalizeAuthorUrl("https://author.acme.com?x=1"));
        assertEquals(null, Hashes.normalizeAuthorUrl("https://author.acme.com/#x"));
        assertEquals(null, Hashes.normalizeAuthorUrl("javascript:alert(1)"));
        assertEquals(null, Hashes.normalizeAuthorUrl(""));
        assertEquals(null, Hashes.normalizeAuthorUrl(null));
    }
}
