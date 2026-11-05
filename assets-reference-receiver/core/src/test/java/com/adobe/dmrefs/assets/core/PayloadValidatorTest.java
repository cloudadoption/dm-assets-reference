package com.adobe.dmrefs.assets.core;

import com.adobe.dmrefs.assets.core.dto.IngestRequest;
import com.adobe.dmrefs.assets.core.dto.IngestRequest.PageUpdate;
import com.adobe.dmrefs.assets.core.dto.IngestRequest.RefDto;
import com.adobe.dmrefs.assets.core.util.Paths;
import com.adobe.dmrefs.assets.core.validate.PayloadValidator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PayloadValidatorTest {

    private static final String UUID = "3f2a1b4c-5d6e-4f70-8a9b-0c1d2e3f4a5b";
    private final PayloadValidator v = new PayloadValidator(2, 3);

    private static IngestRequest req(PageUpdate... pages) {
        IngestRequest r = new IngestRequest();
        r.schemaVersion = 1;
        r.instanceId = "acme-prod";
        r.pages = new ArrayList<>(Arrays.asList(pages));
        return r;
    }

    private static PageUpdate page(String path, long seq, RefDto... author) {
        PageUpdate p = new PageUpdate();
        p.pagePath = path;
        p.seq = seq;
        p.author = new ArrayList<>(Arrays.asList(author));
        return p;
    }

    @Test
    void acceptsValidRequestAndNormalisesUuid() {
        PayloadValidator.Result r = v.validate(req(page("/content/acme/en", 5,
                new RefDto("uuid", "URN:AAID:AEM:" + UUID.toUpperCase()), new RefDto("vanity", "summer-hero"))));
        assertTrue(r.ok(), r.errors.toString());
        assertEquals(1, r.pages.size());
        assertEquals(2, r.pages.get(0).author.size());
        assertTrue(r.pages.get(0).author.iterator().next().value.equals(UUID));
        assertTrue(r.pages.get(0).published.isEmpty());
    }

    @Test
    void rejectsBadInstanceSchemaAndPaths() {
        IngestRequest r = req(page("/etc/passwd", 1), page("/content/a/../../b", 1));
        r.instanceId = "..";
        r.schemaVersion = 2;
        PayloadValidator.Result res = v.validate(r);
        assertFalse(res.ok());
        assertEquals(4, res.errors.size(), res.errors.toString());
        assertTrue(res.pages.isEmpty());
    }

    @Test
    void enforcesLimits() {
        assertFalse(v.validate(req(page("/content/a", 1), page("/content/b", 1), page("/content/c", 1))).ok());
        assertFalse(v.validate(req(page("/content/a", 1, new RefDto("vanity", "a"), new RefDto("vanity", "b"),
                new RefDto("vanity", "c"), new RefDto("vanity", "d")))).ok());
    }

    @Test
    void rejectsBadRefsDuplicatesAndDeletedWithRefs() {
        assertFalse(v.validate(req(page("/content/a", 1, new RefDto("uuid", "urn:aaid:aem:nope")))).ok());
        assertFalse(v.validate(req(page("/content/a", 1, new RefDto("vanity", "has space")))).ok());
        assertFalse(v.validate(req(page("/content/a", 1, new RefDto("other", "x")))).ok());
        assertFalse(v.validate(req(page("/content/a", 1), page("/content/a", 2))).ok());
        PageUpdate d = page("/content/a", 1, new RefDto("vanity", "x"));
        d.deleted = true;
        assertFalse(v.validate(req(d)).ok());
        assertFalse(v.validate(req(page("/content/a", 0))).ok());
    }

    @Test
    void acceptsDeletedPageWithoutRefs() {
        PageUpdate d = page("/content/a", 9);
        d.deleted = true;
        d.published = Collections.emptyList();
        assertTrue(v.validate(req(d)).ok());
    }

    @Test
    void validatesAndNormalisesAuthorUrl() {
        IngestRequest ok = req(page("/content/a", 1));
        ok.authorUrl = " HTTPS://Author.Acme.com/ ";
        PayloadValidator.Result r = v.validate(ok);
        assertTrue(r.ok(), r.errors.toString());
        assertEquals("https://author.acme.com", r.authorUrl);

        assertNull(v.validate(req(page("/content/a", 1))).authorUrl);
        for (String bad : new String[] {"http://author.acme.com", "https://u:p@h.com", "https://h.com?x=1",
                "https://h.com/#f", "javascript:alert(1)", "https://h.com/a b"}) {
            IngestRequest b = req(page("/content/a", 1));
            b.authorUrl = bad;
            assertFalse(v.validate(b).ok(), bad);
        }
    }

    @Test
    void buildsPageUrlOnlyFromValidInput() {
        assertEquals("https://author.acme.com/content/acme/en/about%20us.html",
                Paths.pageUrl("https://author.acme.com/", "/content/acme/en/about us"));
        assertEquals("https://a.b/aem/content/x.html", Paths.pageUrl("https://a.b/aem", "/content/x"));
        assertNull(Paths.pageUrl("http://author.acme.com", "/content/x"));
        assertNull(Paths.pageUrl("https://author.acme.com", "/etc/x"));
        assertNull(Paths.pageUrl(null, "/content/x"));
    }

    @Test
    void urnParsing() {
        assertEquals(UUID, Paths.uuidFromUrn("urn:aaid:aem:" + UUID));
        assertNull(Paths.uuidFromUrn("urn:aaid:aem:" + UUID + "x"));
        assertNull(Paths.uuidFromUrn(null));
        assertFalse(Paths.INSTANCE_ID.matcher("..").matches());
        assertTrue(Paths.isValidDamPath("/content/dam/acme/a.jpg"));
        assertFalse(Paths.isValidDamPath("/content/acme/a"));
    }
}
