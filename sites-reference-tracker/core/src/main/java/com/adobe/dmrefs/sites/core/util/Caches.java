package com.adobe.dmrefs.sites.core.util;

import com.adobe.dmrefs.sites.core.model.Ref;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.LongSupplier;

/** The in-memory caches. All are memory only, so losing them costs one extra scan, never correctness. */
public final class Caches {

    private Caches() {
    }

    /** Thread-safe least-recently-used map. Synchronize on the instance for compound operations. */
    public static final class Lru<K, V> {

        private final LinkedHashMap<K, V> map;

        public Lru(final int maxEntries) {
            this.map = new LinkedHashMap<K, V>(16, 0.75f, true) {
                private static final long serialVersionUID = 1L;

                @Override
                protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                    return size() > maxEntries;
                }
            };
        }

        public synchronized V get(K key) {
            return map.get(key);
        }

        public synchronized void put(K key, V value) {
            map.put(key, value);
        }

        public synchronized void remove(K key) {
            map.remove(key);
        }
    }

    /**
     * Per page: when its state was last read and then acknowledged by Assets. A queued job enqueued before that
     * time is already covered and can be skipped. Record the time the state was READ, and only after a 2xx, so a
     * failed send never makes a retry skip the page.
     */
    public static final class DeliveryCache {

        private final Lru<String, Long> readAt;

        public DeliveryCache(int maxEntries) {
            this.readAt = new Lru<>(maxEntries);
        }

        public void record(String path, long stateReadAt) {
            synchronized (readAt) {
                Long prev = readAt.get(path);
                if (prev == null || prev < stateReadAt) {
                    readAt.put(path, stateReadAt);
                }
            }
        }

        /** True if the state of {@code path} was read and delivered after a job was enqueued at {@code enqueuedAt}. */
        public boolean covers(String path, long enqueuedAt) {
            Long t = readAt.get(path);
            return t != null && t > enqueuedAt;
        }
    }

    /**
     * Per page: the fingerprint of the draft reference set last delivered, so an edit that changed no DM reference
     * needs no HTTP call. Record only after Assets answered 2xx.
     */
    public static final class FingerprintCache {

        private final Lru<String, String> byPath;

        public FingerprintCache(int maxEntries) {
            this.byPath = new Lru<>(maxEntries);
        }

        /** Does not depend on the order of the references. A partial scan never equals a full one. */
        public static String fingerprint(Set<Ref> refs, boolean partial) {
            StringBuilder sb = new StringBuilder(partial ? "partial\n" : "full\n");
            for (Ref r : new TreeSet<>(refs)) {
                sb.append(r.encode()).append('\n');
            }
            return Hashes.sha256(sb.toString());
        }

        public boolean matches(String path, String fingerprint) {
            return fingerprint.equals(byPath.get(path));
        }

        public void record(String path, String fingerprint) {
            byPath.put(path, fingerprint);
        }

        public void forget(String path) {
            byPath.remove(path);
        }
    }

    /**
     * Template-structure scan results. An entry stays valid while every recorded node still has the same
     * last-modified stamp (the structure itself plus each fragment it embeds) and it is younger than the TTL.
     */
    public static final class TemplateCache<V> {

        /** Reads the current last-modified stamp of a node (0 if it has none). */
        public interface StampReader {
            long read(String path);
        }

        private static final class Cached<V> {
            final Map<String, Long> stamps;
            final V value;
            final long storedAt;

            Cached(Map<String, Long> stamps, V value, long storedAt) {
                this.stamps = stamps;
                this.value = value;
                this.storedAt = storedAt;
            }
        }

        private final Lru<String, Cached<V>> entries;
        private final long ttlMs;
        private final LongSupplier clock;

        public TemplateCache(int maxEntries, long ttlMs, LongSupplier clock) {
            this.entries = new Lru<>(maxEntries);
            this.ttlMs = ttlMs;
            this.clock = clock;
        }

        /** @return the cached value, or null if absent, expired or any recorded node has changed. */
        public V get(String key, StampReader reader) {
            Cached<V> e = entries.get(key);
            if (e == null) {
                return null;
            }
            boolean valid = clock.getAsLong() - e.storedAt <= ttlMs;
            for (Map.Entry<String, Long> s : e.stamps.entrySet()) {
                if (valid && reader.read(s.getKey()) != s.getValue()) {
                    valid = false;
                }
            }
            if (!valid) {
                entries.remove(key);
                return null;
            }
            return e.value;
        }

        public void put(String key, Map<String, Long> stamps, V value) {
            entries.put(key, new Cached<V>(Collections.unmodifiableMap(new HashMap<>(stamps)), value,
                    clock.getAsLong()));
        }
    }

    /** One access token, kept until shortly before it expires; one synchronized refresh for all callers. */
    public static final class TokenCache {

        public static final class Fetched {
            private final String token;
            private final long expiresInSeconds;

            public Fetched(String token, long expiresInSeconds) {
                this.token = token;
                this.expiresInSeconds = expiresInSeconds;
            }

            public String token() {
                return token;
            }

            public long expiresInSeconds() {
                return expiresInSeconds;
            }
        }

        public interface Fetcher {
            Fetched fetch() throws IOException;
        }

        private final LongSupplier clock;
        private final long skewMs;
        private String token;
        private long expiresAt;

        public TokenCache(LongSupplier clock, long skewMs) {
            this.clock = clock;
            this.skewMs = skewMs;
        }

        public synchronized String get(Fetcher fetcher) throws IOException {
            if (token == null || clock.getAsLong() >= expiresAt - skewMs) {
                Fetched f = fetcher.fetch();
                token = f.token;
                expiresAt = clock.getAsLong() + f.expiresInSeconds * 1000L;
            }
            return token;
        }

        /** Forces the next {@link #get} to fetch a new token, e.g. after a 401. */
        public synchronized void invalidate() {
            token = null;
        }
    }
}
