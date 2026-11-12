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

    /** A full mapping scans for expired keys at most this often, so a flood does not cost a scan per message. */
    static final long FULL_STORE_EVICTION_INTERVAL_MILLIS = 1_000;

    /** The fingerprints of one mapping: fingerprint to expiry in epoch millis. */
    private static final class Entries {
        final ConcurrentHashMap<String, Long> map = new ConcurrentHashMap<>();
        volatile long lastFullEvictionMillis = Long.MIN_VALUE;
    }

    // <tenant, <mappingIdentifier, entries>>
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Entries>> store = new ConcurrentHashMap<>();

    private final Clock clock;

    public MessageDeduplicationService() {
        this(Clock.systemUTC());
    }

    // for tests, which need to move time
    MessageDeduplicationService(Clock clock) {
        this.clock = clock;
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
                log.warn("{} - Mapping {}: de-duplication store is full ({} fingerprints), message is processed without a check",
                        tenant, mappingIdentifier, entries.size());
                return Result.NOT_CHECKED;
            }
        }

        boolean[] duplicate = { false };
        long newExpiry = now + window * 1000L;
        entries.compute(fingerprint, (key, expiry) -> {
            if (expiry != null && expiry > now) {
                duplicate[0] = true;
                return expiry; // keep the original expiry, the window does not slide
            }
            return newExpiry;
        });
        return duplicate[0] ? new Result(true, null)
                : new Result(false, new Claim(tenant, mappingIdentifier, fingerprint, newExpiry));
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
            perTenant.remove(mappingIdentifier);
        }
    }

    public void removeTenant(String tenant) {
        store.remove(tenant);
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
