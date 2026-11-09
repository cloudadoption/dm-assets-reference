package com.adobe.dmrefs.assets.core;

import com.adobe.dmrefs.assets.core.dto.IngestRequest;
import com.adobe.dmrefs.assets.core.dto.IngestRequest.PageUpdate;
import com.adobe.dmrefs.assets.core.dto.IngestRequest.RefDto;
import com.adobe.dmrefs.assets.core.impl.ReferenceStore;
import com.adobe.dmrefs.assets.core.validate.PayloadValidator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReferenceStoreLogicTest {

    private static final String UUID = "3f2a1b4c-5d6e-4f70-8a9b-0c1d2e3f4a5b";
    private final PayloadValidator v = new PayloadValidator(10, 10);

    private PayloadValidator.ValidPage page(Boolean partial, Boolean deleted, RefDto... published) {
        PageUpdate p = new PageUpdate();
        p.pagePath = "/content/acme/en";
        p.seq = 1L;
        p.partial = partial;
        p.deleted = deleted;
        p.published = new ArrayList<>(Arrays.asList(published));
        IngestRequest r = new IngestRequest();
        r.schemaVersion = 1;
        r.instanceId = "acme";
        r.pages = Collections.singletonList(p);
        PayloadValidator.Result res = v.validate(r);
        assertTrue(res.ok(), res.errors.toString());
        return res.pages.get(0);
    }

    private static Set<String> set(String... s) {
        return new HashSet<>(Arrays.asList(s));
    }

    @Test
    void noOpOnlyForUnseenPageWithNoRefs() {
        assertTrue(ReferenceStore.isNoOp(false, page(null, null)));
        assertTrue(ReferenceStore.isNoOp(false, page(null, true)));
        assertFalse(ReferenceStore.isNoOp(true, page(null, null)));
        assertFalse(ReferenceStore.isNoOp(false, page(null, null, new RefDto("uuid", "urn:aaid:aem:" + UUID))));
    }

    @Test
    void updatingOneStateLeavesTheOtherAlone() {
        // draft update (author set replaced) on an entry that is also published
        boolean[] f = ReferenceStore.resolveFlags(true, false, false, true, true, false, false);
        assertFalse(f[0]);
        assertTrue(f[1]);
        // publish update on an entry that is also in the draft
        f = ReferenceStore.resolveFlags(false, true, false, true, true, false, false);
        assertTrue(f[0]);
        assertFalse(f[1]);
    }

    @Test
    void entryIsRemovedOnlyWhenBothFlagsAreFalse() {
        boolean[] both = ReferenceStore.resolveFlags(true, true, false, true, true, false, false);
        assertFalse(both[0] || both[1]);
        boolean[] draftOnly = ReferenceStore.resolveFlags(false, true, false, true, false, false, false);
        assertTrue(draftOnly[0]);
        assertFalse(draftOnly[1]);
    }

    @Test
    void newReferenceGainsOnlyTheUpdatedStateFlag() {
        boolean[] f = ReferenceStore.resolveFlags(true, false, false, false, false, true, false);
        assertTrue(f[0]);
        assertFalse(f[1]);
    }

    @Test
    void partialUpdateOnlyAddsFlags() {
        boolean[] f = ReferenceStore.resolveFlags(true, true, true, true, true, false, false);
        assertTrue(f[0]);
        assertTrue(f[1]);
        f = ReferenceStore.resolveFlags(true, false, true, false, true, true, false);
        assertTrue(f[0]);
        assertTrue(f[1]);
    }

    @Test
    void deletedPageTouchesBothStatesAndPresenceDecidesTheRest() {
        PayloadValidator.ValidPage deleted = page(null, true);
        assertTrue(ReferenceStore.touchesAuthor(deleted));
        assertTrue(ReferenceStore.touchesPublished(deleted));
        PayloadValidator.ValidPage publishedOnly = page(null, null);
        assertFalse(ReferenceStore.touchesAuthor(publishedOnly));
        assertTrue(ReferenceStore.touchesPublished(publishedOnly));
    }

    @Test
    void pageEntryNeedsAtLeastOneSetUnlessDeleted() {
        PageUpdate p = new PageUpdate();
        p.pagePath = "/content/acme/en";
        p.seq = 1L;
        IngestRequest r = new IngestRequest();
        r.schemaVersion = 1;
        r.instanceId = "acme";
        r.pages = Collections.singletonList(p);
        assertFalse(v.validate(r).ok());
        p.author = new ArrayList<>();
        assertTrue(v.validate(r).ok());
        assertTrue(v.validate(r).pages.get(0).authorPresent);
        assertFalse(v.validate(r).pages.get(0).publishedPresent);
    }

    @Test
    void validatorReadsPartialFlag() {
        assertTrue(page(true, null).partial);
        assertFalse(page(null, null).partial);
    }

    @Test
    void validatorRejectsDeletedPartialPage() {
        PageUpdate p = new PageUpdate();
        p.pagePath = "/content/acme/en";
        p.seq = 1L;
        p.deleted = true;
        p.partial = true;
        IngestRequest r = new IngestRequest();
        r.schemaVersion = 1;
        r.instanceId = "acme";
        r.pages = Collections.singletonList(p);
        assertFalse(v.validate(r).ok());
    }
}
