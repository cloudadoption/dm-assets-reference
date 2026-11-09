package com.adobe.dmrefs.sites.core;

import com.adobe.dmrefs.sites.core.model.Ref;
import com.adobe.dmrefs.sites.core.util.Coalescer;
import com.adobe.dmrefs.sites.core.util.Caches.FingerprintCache;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DraftLogicTest {

    @Test
    void pageIsHandedOutOnlyAfterTheQuietWindow() {
        AtomicLong now = new AtomicLong(0);
        Coalescer c = new Coalescer(now::get, 60_000, 300_000, 100);
        c.touch("/content/a");
        now.set(59_000);
        assertTrue(c.drainDue().isEmpty());
        now.set(60_000);
        assertEquals(Collections.singletonList("/content/a"), c.drainDue());
        assertEquals(0, c.size());
    }

    @Test
    void repeatedEditsKeepPushingTheQuietWindowBackUntilMaxWait() {
        AtomicLong now = new AtomicLong(0);
        Coalescer c = new Coalescer(now::get, 60_000, 300_000, 100);
        c.touch("/content/a");
        for (long t = 30_000; t < 300_000; t += 30_000) { // an edit every 30 s
            now.set(t);
            c.touch("/content/a");
            assertTrue(c.drainDue().isEmpty(), "still being edited at " + t);
        }
        now.set(300_000);
        c.touch("/content/a");
        assertEquals(Collections.singletonList("/content/a"), c.drainDue()); // max wait reached
    }

    @Test
    void editAfterTheSweepIsKeptForTheNextOne() {
        AtomicLong now = new AtomicLong(0);
        Coalescer c = new Coalescer(now::get, 10, 1000, 100);
        c.touch("/content/a");
        now.set(10);
        assertEquals(1, c.drainDue().size());
        c.touch("/content/a");
        assertEquals(1, c.size());
        now.set(20);
        assertEquals(1, c.drainDue().size());
    }

    @Test
    void overflowHandsOutTheOldestTenthEarly() {
        AtomicLong now = new AtomicLong(0);
        Coalescer c = new Coalescer(now::get, 60_000, 300_000, 20);
        for (int i = 0; i < 21; i++) {
            now.set(i);
            c.touch("/content/p" + i);
        }
        List<String> early = c.drainDue();
        assertEquals(2, early.size());
        assertTrue(early.contains("/content/p0"));
        assertEquals(19, c.size());
    }

    @Test
    void noEditIsLostWhileSweepingConcurrently() throws Exception {
        final Coalescer c = new Coalescer(System::currentTimeMillis, 0, 0, 100_000);
        final Set<String> seen = ConcurrentHashMap.newKeySet();
        final int threads = 4;
        final int perThread = 5000;
        final CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            final int id = t;
            new Thread(() -> {
                for (int i = 0; i < perThread; i++) {
                    c.touch("/content/t" + id + "/p" + (i % 500));
                }
                done.countDown();
            }).start();
        }
        while (done.getCount() > 0) {
            seen.addAll(c.drainDue());
        }
        seen.addAll(c.drainDue());
        assertEquals(threads * 500, seen.size());
        assertEquals(0, c.size());
    }

    @Test
    void fingerprintIgnoresOrderAndSeparatesPartialFromFull() {
        Ref a = new Ref(Ref.Type.UUID, "urn:aaid:aem:3f2a1b4c-5d6e-4f70-8a9b-0c1d2e3f4a5b");
        Ref b = new Ref(Ref.Type.VANITY, "summer-hero");
        Set<Ref> one = new TreeSet<>(Arrays.asList(a, b));
        Set<Ref> two = new HashSet<>(Arrays.asList(b, a));
        assertEquals(FingerprintCache.fingerprint(one, false), FingerprintCache.fingerprint(two, false));
        assertNotEquals(FingerprintCache.fingerprint(one, false), FingerprintCache.fingerprint(one, true));
        assertNotEquals(FingerprintCache.fingerprint(one, false),
                FingerprintCache.fingerprint(Collections.singleton(a), false));
    }

    @Test
    void fingerprintCacheMatchesOnlyAfterRecordAndEvictsOldest() {
        FingerprintCache c = new FingerprintCache(2);
        assertFalse(c.matches("/content/a", "x"));
        c.record("/content/a", "x");
        c.record("/content/b", "y");
        assertTrue(c.matches("/content/a", "x"));
        c.record("/content/c", "z"); // evicts the least recently used: b
        assertFalse(c.matches("/content/b", "y"));
        c.forget("/content/a");
        assertFalse(c.matches("/content/a", "x"));
    }

    @Test
    void userFilterMatchesExactNamesAndPrefixes() {
        String[] patterns = {"replication-service", "svc-*"};
        assertTrue(Coalescer.matchesUser("replication-service", patterns));
        assertTrue(Coalescer.matchesUser("svc-bulk", patterns));
        assertFalse(Coalescer.matchesUser("replication-service2", patterns));
        assertFalse(Coalescer.matchesUser("admin", patterns));
        assertFalse(Coalescer.matchesUser(null, patterns));
        assertFalse(Coalescer.matchesUser("admin", null));
    }
}
