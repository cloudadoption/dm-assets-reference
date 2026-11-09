package com.adobe.dmrefs.sites.core;

import com.adobe.dmrefs.sites.core.model.PageUpdate;
import com.adobe.dmrefs.sites.core.model.Ref;
import com.adobe.dmrefs.sites.core.util.BatchPusher;
import com.adobe.dmrefs.sites.core.util.Caches.DeliveryCache;
import com.adobe.dmrefs.sites.core.util.Ims;
import com.adobe.dmrefs.sites.core.util.Caches.TemplateCache;
import com.adobe.dmrefs.sites.core.util.Caches.TokenCache;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CachesAndPushTest {

    private static PageUpdate page(String path) {
        return PageUpdate.published(path, 1, false, Collections.<Ref>emptySet());
    }

    @Test
    void deliveryCacheCoversOnlyJobsEnqueuedBeforeTheRead() {
        DeliveryCache c = new DeliveryCache(10);
        assertFalse(c.covers("/content/a", 100));
        c.record("/content/a", 200);
        assertTrue(c.covers("/content/a", 100));
        assertFalse(c.covers("/content/a", 200));
        assertFalse(c.covers("/content/a", 300));
        c.record("/content/a", 150); // never moves backwards
        assertTrue(c.covers("/content/a", 199));
    }

    @Test
    void deliveryCacheEvictsLeastRecentlyUsed() {
        DeliveryCache c = new DeliveryCache(2);
        c.record("/content/a", 10);
        c.record("/content/b", 10);
        c.covers("/content/a", 0); // touch a
        c.record("/content/c", 10);
        assertTrue(c.covers("/content/a", 0));
        assertFalse(c.covers("/content/b", 0));
        assertTrue(c.covers("/content/c", 0));
    }

    @Test
    void templateCacheInvalidatesOnStampChangeAndTtl() {
        AtomicLong now = new AtomicLong(0);
        TemplateCache<String> c = new TemplateCache<>(5, 1000, now::get);
        Map<String, Long> stamps = new HashMap<>();
        stamps.put("/conf/t/structure", 5L);
        stamps.put("/content/experience-fragments/x/master", 7L);
        c.put("/conf/t", stamps, "refs");

        Map<String, Long> live = new HashMap<>(stamps);
        assertEquals("refs", c.get("/conf/t", live::get));

        live.put("/content/experience-fragments/x/master", 8L); // an embedded XF changed
        assertNull(c.get("/conf/t", live::get));

        c.put("/conf/t", stamps, "refs");
        now.set(1001); // expired
        assertNull(c.get("/conf/t", stamps::get));
    }

    @Test
    void templateCacheEvictsBeyondCapacity() {
        TemplateCache<String> c = new TemplateCache<>(1, 1000, () -> 0L);
        c.put("/conf/a", Collections.<String, Long>emptyMap(), "a");
        c.put("/conf/b", Collections.<String, Long>emptyMap(), "b");
        assertNull(c.get("/conf/a", p -> 0L));
        assertEquals("b", c.get("/conf/b", p -> 0L));
    }

    @Test
    void batchDeliveredOn2xx() {
        List<PageUpdate> in = java.util.Arrays.asList(page("/content/a"), page("/content/b"));
        BatchPusher.Result r = BatchPusher.push(in, pages -> 200);
        assertEquals(2, r.delivered.size());
        assertFalse(r.retry);
        assertTrue(r.dropped.isEmpty());
    }

    @Test
    void poisonPageIsDroppedAndTheRestDelivered() {
        List<PageUpdate> in = java.util.Arrays.asList(page("/content/a"), page("/content/bad"), page("/content/c"));
        AtomicInteger calls = new AtomicInteger();
        BatchPusher.Result r = BatchPusher.push(in, pages -> {
            calls.incrementAndGet();
            for (PageUpdate p : pages) {
                if (p.getPagePath().equals("/content/bad")) {
                    return 400;
                }
            }
            return 200;
        });
        assertEquals(4, calls.get()); // whole batch, then each page on its own
        assertEquals(2, r.delivered.size());
        assertEquals(1, r.dropped.size());
        assertEquals("/content/bad", r.dropped.get(0).getPagePath());
        assertFalse(r.retry);
    }

    @Test
    void singleRejectedPageIsDroppedWithoutRetry() {
        BatchPusher.Result r = BatchPusher.push(Collections.singletonList(page("/content/a")), pages -> 413);
        assertEquals(1, r.dropped.size());
        assertFalse(r.retry);
    }

    @Test
    void serverErrorsAndAuthFailuresAskForRetry() {
        for (int status : new int[] {500, 503, -1, 401, 403}) {
            BatchPusher.Result r = BatchPusher.push(Collections.singletonList(page("/content/a")), pages -> status);
            assertTrue(r.retry, "status " + status);
            assertTrue(r.delivered.isEmpty());
            assertTrue(r.dropped.isEmpty());
        }
    }

    @Test
    void retryDuringIsolationKeepsAlreadyDeliveredPages() {
        List<PageUpdate> in = java.util.Arrays.asList(page("/content/a"), page("/content/b"), page("/content/c"));
        List<String> sent = new ArrayList<>();
        BatchPusher.Result r = BatchPusher.push(in, pages -> {
            if (pages.size() > 1) {
                return 400;
            }
            sent.add(pages.get(0).getPagePath());
            return pages.get(0).getPagePath().equals("/content/b") ? 503 : 200;
        });
        assertTrue(r.retry);
        assertEquals(1, r.delivered.size());
        assertEquals(java.util.Arrays.asList("/content/a", "/content/b"), sent);
    }

    @Test
    void tokenCacheRefreshesBeforeExpiryOnly() throws IOException {
        AtomicLong now = new AtomicLong(0);
        AtomicInteger fetches = new AtomicInteger();
        TokenCache c = new TokenCache(now::get, 300_000);
        TokenCache.Fetcher f = () -> new TokenCache.Fetched("t" + fetches.incrementAndGet(), 3600);
        assertEquals("t1", c.get(f));
        now.set(3_000_000); // 50 min: still valid (expires at 60 min minus 5 min skew)
        assertEquals("t1", c.get(f));
        now.set(3_300_000); // 55 min: inside the skew window
        assertEquals("t2", c.get(f));
        c.invalidate();
        assertEquals("t3", c.get(f));
    }

    @Test
    void tokenFetchFailureLeavesNoToken() {
        TokenCache c = new TokenCache(() -> 0L, 0);
        assertThrows(IOException.class, () -> c.get(() -> {
            throw new IOException("down");
        }));
    }

    @Test
    void imsTokenUrlAllowlist() {
        assertTrue(Ims.isAllowedTokenUrl("https://ims-na1.adobelogin.com/ims/token/v3"));
        assertTrue(Ims.isAllowedTokenUrl("https://ims-eu1.adobelogin.com/ims/token/v3"));
        assertFalse(Ims.isAllowedTokenUrl("http://ims-na1.adobelogin.com/ims/token/v3"));
        assertFalse(Ims.isAllowedTokenUrl("https://evil.example.com/ims/token/v3"));
        assertFalse(Ims.isAllowedTokenUrl("https://ims-na1.adobelogin.com.evil.com/ims/token/v3"));
        assertFalse(Ims.isAllowedTokenUrl("https://user@ims-na1.adobelogin.com/ims/token/v3"));
        assertFalse(Ims.isAllowedTokenUrl("https://ims-na1.adobelogin.com/other"));
        assertFalse(Ims.isAllowedTokenUrl(null));
    }

    @Test
    void parsesImsTokenResponse() {
        TokenCache.Fetched f = Ims.parseTokenResponse(
                "{\"access_token\":\"abc.def-ghi\",\"token_type\":\"bearer\",\"expires_in\":86399}");
        assertEquals("abc.def-ghi", f.token());
        assertEquals(86399L, f.expiresInSeconds());
        assertNull(Ims.parseTokenResponse("{\"error\":\"invalid_client\"}"));
        assertNull(Ims.parseTokenResponse(null));
    }
}
