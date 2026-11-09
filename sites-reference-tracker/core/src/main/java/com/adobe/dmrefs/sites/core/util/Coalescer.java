package com.adobe.dmrefs.sites.core.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * Remembers which pages were edited and hands each one out once the editing has settled: after a quiet period,
 * or after a maximum wait since its first edit. Memory only, no JCR access, cheap enough to call on every edit.
 * When the map grows past its limit, the oldest tenth is handed out early.
 */
public final class Coalescer {

    private static final class Seen {
        final long first;
        final long last;
        final boolean forced;

        Seen(long first, long last, boolean forced) {
            this.first = first;
            this.last = last;
            this.forced = forced;
        }
    }

    private final ConcurrentHashMap<String, Seen> map = new ConcurrentHashMap<>();
    private final AtomicBoolean overflowHandled = new AtomicBoolean();
    private final LongSupplier clock;
    private volatile long quietMs;
    private volatile long maxWaitMs;
    private volatile int maxEntries;

    public Coalescer(LongSupplier clock, long quietMs, long maxWaitMs, int maxEntries) {
        this.clock = clock;
        configure(quietMs, maxWaitMs, maxEntries);
    }

    /** Applies new limits to entries already waiting and to later edits. */
    public void configure(long quietMs, long maxWaitMs, int maxEntries) {
        this.quietMs = quietMs;
        this.maxWaitMs = maxWaitMs;
        this.maxEntries = maxEntries;
    }

    /** Records an edit of {@code path}. */
    public void touch(String path) {
        final long now = clock.getAsLong();
        map.compute(path, (p, s) -> s == null ? new Seen(now, now, false) : new Seen(s.first, now, s.forced));
        if (map.size() > maxEntries && overflowHandled.compareAndSet(false, true)) {
            markOldestDue();
        }
    }

    public int size() {
        return map.size();
    }

    /** True if {@code userId} equals one of the patterns, or starts with one that ends in {@code *}. */
    public static boolean matchesUser(String userId, String[] patterns) {
        if (userId == null || patterns == null) {
            return false;
        }
        for (String p : patterns) {
            if (p == null || p.isEmpty()) {
                continue;
            }
            if (p.endsWith("*") ? userId.startsWith(p.substring(0, p.length() - 1)) : userId.equals(p)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Removes and returns the pages whose editing has settled. An entry is removed only if it was not edited
     * again in the meantime, so an edit that arrives during a sweep is kept for the next one.
     */
    public List<String> drainDue() {
        long now = clock.getAsLong();
        List<String> due = new ArrayList<>();
        for (Map.Entry<String, Seen> e : map.entrySet()) {
            Seen s = e.getValue();
            boolean isDue = s.forced || now - s.last >= quietMs || now - s.first >= maxWaitMs;
            if (isDue && map.remove(e.getKey(), s)) {
                due.add(e.getKey());
            }
        }
        overflowHandled.set(false);
        return due;
    }

    private void markOldestDue() {
        List<Map.Entry<String, Seen>> all = new ArrayList<>(map.entrySet());
        Collections.sort(all, new Comparator<Map.Entry<String, Seen>>() {
            @Override
            public int compare(Map.Entry<String, Seen> a, Map.Entry<String, Seen> b) {
                return Long.compare(a.getValue().first, b.getValue().first);
            }
        });
        int n = Math.max(1, all.size() / 10);
        for (int i = 0; i < n && i < all.size(); i++) {
            map.computeIfPresent(all.get(i).getKey(), (p, s) -> new Seen(s.first, s.last, true));
        }
    }
}
