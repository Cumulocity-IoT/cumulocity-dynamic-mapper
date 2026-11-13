/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *  @authors Christof Strack, Stefan Witschel
 *
 */
package dynamic.mapper.processor.inbound;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * Detects repeated inbound messages inside a short window (issue #692).
 *
 * <p>Fingerprints are held in memory, per tenant and mapping. They do not survive a restart and are not
 * shared between service instances. The window starts when a fingerprint is first seen and does not slide.</p>
 *
 * <p>The check is a single atomic {@link ConcurrentHashMap#compute}, so two concurrent copies of one message
 * cannot both pass. When a mapping reaches {@link #MAX_ENTRIES_PER_MAPPING}, the check fails open: the message
 * is processed and not recorded.</p>
 */
@Service
@Slf4j
public class MessageDeduplicationService {

    public static final int MIN_WINDOW_SECONDS = 1;
    public static final int MAX_WINDOW_SECONDS = 3600;
    public static final int MAX_ENTRIES_PER_MAPPING = 100_000;
    /** All mappings of all tenants together, about 150 MB at most. Many mappings cannot add up to an OOM. */
    public static final int MAX_ENTRIES_TOTAL = 1_000_000;

    /** A full mapping scans for expired keys at most this often, so a flood does not cost a scan per message. */
    static final long FULL_STORE_EVICTION_INTERVAL_MILLIS = 1_000;

    /** A full mapping logs its warning at most this often, so a flood does not flood the log. */
    static final long FULL_STORE_WARNING_INTERVAL_MILLIS = 60_000;

    /** The fingerprints of one mapping: fingerprint to expiry in epoch millis. */
    private static final class Entries {
        final ConcurrentHashMap<String, Long> map = new ConcurrentHashMap<>();
        volatile long lastFullEvictionMillis = Long.MIN_VALUE;
        volatile long lastFullWarningMillis = Long.MIN_VALUE;
        /** Messages passed unchecked because the mapping was full, since the last warning. */
        final java.util.concurrent.atomic.LongAdder uncheckedSinceWarning = new java.util.concurrent.atomic.LongAdder();
    }

    // <tenant, <mappingIdentifier, entries>>
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Entries>> store = new ConcurrentHashMap<>();

    /**
     * Fingerprints in all mappings, approximately: counted up when a key is recorded and set to the real sum at
     * most once per {@link #FULL_STORE_EVICTION_INTERVAL_MILLIS}. Removals (eviction, release, clear) are not
     * counted one by one, so the recount corrects the drift.
     */
    private final java.util.concurrent.atomic.AtomicLong approximateTotal = new java.util.concurrent.atomic.AtomicLong();
    private volatile long lastRecountMillis = Long.MIN_VALUE;
    private volatile long lastTotalWarningMillis = Long.MIN_VALUE;
    private final java.util.concurrent.atomic.LongAdder uncheckedSinceTotalWarning = new java.util.concurrent.atomic.LongAdder();
    private final int maxEntriesTotal;

    private final Clock clock;

    public MessageDeduplicationService() {
        this(Clock.systemUTC(), MAX_ENTRIES_TOTAL);
    }

    // for tests, which need to move time
    MessageDeduplicationService(Clock clock) {
        this(clock, MAX_ENTRIES_TOTAL);
    }

    // for tests, which need a small global limit
    MessageDeduplicationService(Clock clock, int maxEntriesTotal) {
        this.clock = clock;
        this.maxEntriesTotal = maxEntriesTotal;
    }

    /** True for a whole number of seconds from {@link #MIN_WINDOW_SECONDS} to {@link #MAX_WINDOW_SECONDS}. */
    public static boolean isValidWindow(long seconds) {
        return seconds >= MIN_WINDOW_SECONDS && seconds <= MAX_WINDOW_SECONDS;
    }

    /**
     * A fingerprint this call recorded for a message that was not a repeat. The caller gives it back with
     * {@link #release()} if the message then fails or produces nothing, so a retry of that message is not dropped.
     * Only the recording itself is undone: a later copy that was already dropped stays dropped.
     */
    public final class Claim {
        private final String tenant;
        private final String mappingIdentifier;
        private final String fingerprint;
        private final long expiry;

        private Claim(String tenant, String mappingIdentifier, String fingerprint, long expiry) {
            this.tenant = tenant;
            this.mappingIdentifier = mappingIdentifier;
            this.fingerprint = fingerprint;
            this.expiry = expiry;
        }

        /** Removes the fingerprint, unless it was already replaced by a newer recording. */
        public void release() {
            var perTenant = store.get(tenant);
            var holder = perTenant == null ? null : perTenant.get(mappingIdentifier);
            if (holder != null) {
                holder.map.remove(fingerprint, expiry);
            }
        }
    }

    /**
     * @param duplicate true if the same fingerprint was recorded less than the window ago
     * @param claim     the recording made for this message; null for a repeat and when the check failed open
     */
    public record Result(boolean duplicate, Claim claim) {
        static final Result NOT_CHECKED = new Result(false, null);
    }

    /**
     * Records the fingerprint, or reports it as a repeat.
     *
     * @return true if the same fingerprint was recorded for this mapping less than {@code windowSeconds} ago
     */
    public boolean isDuplicate(String tenant, String mappingIdentifier, String fingerprint, int windowSeconds) {
        return check(tenant, mappingIdentifier, fingerprint, windowSeconds).duplicate();
    }

    /** Like {@link #isDuplicate}, and returns the recording so the caller can release it if the message fails. */
    public Result check(String tenant, String mappingIdentifier, String fingerprint, int windowSeconds) {
        if (!isValidWindow(windowSeconds) || fingerprint == null) {
            return Result.NOT_CHECKED; // fails open: an out-of-range window never drops a message
        }
        int window = windowSeconds;
        long now = clock.millis();
        while (true) {
            var perTenant = store.computeIfAbsent(tenant, k -> new ConcurrentHashMap<>());
            Entries holder = perTenant.computeIfAbsent(mappingIdentifier, k -> new Entries());
            Result result = check(tenant, mappingIdentifier, holder, fingerprint, window, now);
            // The cleanup job can remove an empty mapping map, and a mapping save or delete clears it, between
            // our lookup and our write. A key written into a map that is no longer in the store would be lost,
            // so look again and write into the current map. The first map held no live key, so retrying is exact.
            if (store.get(tenant) == perTenant && perTenant.get(mappingIdentifier) == holder) {
                return result;
            }
        }
    }

    private Result check(String tenant, String mappingIdentifier, Entries holder, String fingerprint, int window,
            long now) {
        ConcurrentHashMap<String, Long> entries = holder.map;
        if (entries.size() >= MAX_ENTRIES_PER_MAPPING) {
            if (holder.lastFullEvictionMillis == Long.MIN_VALUE
                    || now - holder.lastFullEvictionMillis >= FULL_STORE_EVICTION_INTERVAL_MILLIS) {
                holder.lastFullEvictionMillis = now;
                evictExpired(entries, now);
            }
            if (entries.size() >= MAX_ENTRIES_PER_MAPPING && !entries.containsKey(fingerprint)) {
                warnStoreFull(tenant, mappingIdentifier, holder, now);
                return Result.NOT_CHECKED;
            }
        }

        if (approximateTotal.get() >= maxEntriesTotal && !entries.containsKey(fingerprint)
                && totalIsFull(now)) {
            warnTotalFull(tenant, mappingIdentifier, now);
            return Result.NOT_CHECKED;
        }

        boolean[] duplicate = { false };
        boolean[] added = { false };
        long newExpiry = now + window * 1000L;
        entries.compute(fingerprint, (key, expiry) -> {
            if (expiry != null && expiry > now) {
                duplicate[0] = true;
                return expiry; // keep the original expiry, the window does not slide
            }
            added[0] = expiry == null;
            return newExpiry;
        });
        if (added[0]) {
            approximateTotal.incrementAndGet();
        }
        return duplicate[0] ? new Result(true, null)
                : new Result(false, new Claim(tenant, mappingIdentifier, fingerprint, newExpiry));
    }

    /** True if all mappings together hold the limit. Recounts first, at most once per interval. */
    private boolean totalIsFull(long now) {
        long last = lastRecountMillis;
        if (last == Long.MIN_VALUE || now - last >= FULL_STORE_EVICTION_INTERVAL_MILLIS) {
            lastRecountMillis = now;
            long total = 0;
            for (var perTenant : store.values()) {
                for (Entries holder : perTenant.values()) {
                    evictExpired(holder.map, now); // expired keys must not count against the limit
                    total += holder.map.size();
                }
            }
            approximateTotal.set(total);
        }
        return approximateTotal.get() >= maxEntriesTotal;
    }

    private void warnTotalFull(String tenant, String mappingIdentifier, long now) {
        uncheckedSinceTotalWarning.increment();
        long last = lastTotalWarningMillis;
        if (last == Long.MIN_VALUE || now - last >= FULL_STORE_WARNING_INTERVAL_MILLIS) {
            lastTotalWarningMillis = now;
            log.warn("{} - Mapping {}: the de-duplication stores of all mappings together hold {} fingerprints, the limit. "
                    + "{} message(s) were processed without a duplicate check since the last warning. "
                    + "Use shorter windows or keys with fewer values.",
                    tenant, mappingIdentifier, maxEntriesTotal, uncheckedSinceTotalWarning.sumThenReset());
        }
    }

    private void warnStoreFull(String tenant, String mappingIdentifier, Entries holder, long now) {
        holder.uncheckedSinceWarning.increment();
        long last = holder.lastFullWarningMillis;
        if (last == Long.MIN_VALUE || now - last >= FULL_STORE_WARNING_INTERVAL_MILLIS) {
            holder.lastFullWarningMillis = now; // a rare double log from two racing threads is harmless
            log.warn("{} - Mapping {}: de-duplication store is full ({} fingerprints). {} message(s) were processed "
                    + "without a duplicate check since the last warning. Use a shorter window or a key with fewer values.",
                    tenant, mappingIdentifier, holder.map.size(), holder.uncheckedSinceWarning.sumThenReset());
        }
    }

    /** Number of fingerprints currently held for the mapping, including expired ones not yet removed. */
    public int size(String tenant, String mappingIdentifier) {
        var perTenant = store.get(tenant);
        if (perTenant == null) {
            return 0;
        }
        var entries = perTenant.get(mappingIdentifier);
        return entries == null ? 0 : entries.map.size();
    }

    /** Removes expired fingerprints of one tenant. Called once a minute. */
    public void evictExpired(String tenant) {
        var perTenant = store.get(tenant);
        if (perTenant == null) {
            return;
        }
        long now = clock.millis();
        for (String mappingIdentifier : perTenant.keySet()) {
            // computeIfPresent is atomic per mapping; isDuplicate looks again if its map was removed meanwhile
            perTenant.computeIfPresent(mappingIdentifier, (id, holder) -> {
                evictExpired(holder.map, now);
                return holder.map.isEmpty() ? null : holder; // a deleted or idle mapping leaves an empty map
            });
        }
    }

    private static void evictExpired(ConcurrentHashMap<String, Long> entries, long now) {
        entries.values().removeIf(expiry -> expiry <= now);
    }

    public void clearMapping(String tenant, String mappingIdentifier) {
        var perTenant = store.get(tenant);
        if (perTenant != null) {
            var removed = perTenant.remove(mappingIdentifier);
            if (removed != null) {
                approximateTotal.addAndGet(-removed.map.size());
            }
        }
    }

    public void removeTenant(String tenant) {
        var removed = store.remove(tenant);
        if (removed != null) {
            long size = 0;
            for (Entries holder : removed.values()) {
                size += holder.map.size();
            }
            approximateTotal.addAndGet(-size);
        }
    }

    /**
     * The first 128 bits of the SHA-256 of a key, as 32 lower-case hex characters. 128 bits make a collision
     * practically impossible and halve the memory of a full 256-bit hex string.
     */
    public static String fingerprint(String key) {
        byte[] hash = sha256().digest(key.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash, 0, 16);
    }

    /**
     * The text of a de-duplication key. The filter function and the Smart Function call this, so one key counts
     * once wherever it was built. Text is used as it is; numbers, booleans, objects, and arrays are written the same
     * way in both runtimes: a whole number has no decimal point ({@code 5.0} is {@code 5}), and object members are
     * sorted by name, so two copies that list members in another order still match.
     *
     * @return null if the key has no value
     */
    public static String keyText(Object key) {
        if (key == null) {
            return null;
        }
        if (key instanceof CharSequence) {
            return key.toString();
        }
        StringBuilder sb = new StringBuilder();
        appendCanonical(key, sb);
        return sb.toString();
    }

    private static void appendCanonical(Object value, StringBuilder sb) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Number n) {
            sb.append(numberText(n));
        } else if (value instanceof Boolean b) {
            sb.append(b);
        } else if (value instanceof java.util.Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (var name : new java.util.TreeSet<>(map.keySet().stream().map(String::valueOf).toList())) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                appendQuoted(name, sb);
                sb.append(':');
                Object member = map.containsKey(name) ? map.get(name) : findByStringKey(map, name);
                appendCanonical(member, sb);
            }
            sb.append('}');
        } else if (value instanceof Iterable<?> items) {
            sb.append('[');
            boolean first = true;
            for (Object item : items) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                appendCanonical(item, sb);
            }
            sb.append(']');
        } else {
            appendQuoted(value.toString(), sb);
        }
    }

    private static Object findByStringKey(java.util.Map<?, ?> map, String name) {
        for (var e : map.entrySet()) {
            if (name.equals(String.valueOf(e.getKey()))) {
                return e.getValue();
            }
        }
        return null;
    }

    private static String numberText(Number n) {
        if (n instanceof Double || n instanceof Float) {
            double d = n.doubleValue();
            if (!Double.isNaN(d) && !Double.isInfinite(d) && d == Math.rint(d) && Math.abs(d) < 1e15) {
                return Long.toString((long) d);
            }
            return Double.toString(d);
        }
        if (n instanceof java.math.BigDecimal bd) {
            return bd.stripTrailingZeros().toPlainString();
        }
        return n.toString();
    }

    private static void appendQuoted(String text, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }
}
