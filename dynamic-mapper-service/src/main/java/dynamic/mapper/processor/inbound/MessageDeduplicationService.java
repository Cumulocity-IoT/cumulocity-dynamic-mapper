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

    // <tenant, <mappingIdentifier, <fingerprint, expiry in epoch millis>>>
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>> store =
            new ConcurrentHashMap<>();

    private final Clock clock;

    public MessageDeduplicationService() {
        this(Clock.systemUTC());
    }

    // for tests, which need to move time
    MessageDeduplicationService(Clock clock) {
        this.clock = clock;
    }

    /** Clamps a configured window to the supported range. 0 or less means the check is off. */
    public static int clampWindowSeconds(int configured) {
        if (configured <= 0) {
            return 0;
        }
        return Math.min(Math.max(configured, MIN_WINDOW_SECONDS), MAX_WINDOW_SECONDS);
    }

    /**
     * Records the fingerprint, or reports it as a repeat.
     *
     * @return true if the same fingerprint was recorded for this mapping less than {@code windowSeconds} ago
     */
    public boolean isDuplicate(String tenant, String mappingIdentifier, String fingerprint, int windowSeconds) {
        int window = clampWindowSeconds(windowSeconds);
        if (window == 0 || fingerprint == null) {
            return false;
        }
        long now = clock.millis();
        ConcurrentHashMap<String, Long> entries = store
                .computeIfAbsent(tenant, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(mappingIdentifier, k -> new ConcurrentHashMap<>());

        if (entries.size() >= MAX_ENTRIES_PER_MAPPING) {
            evictExpired(entries, now);
            if (entries.size() >= MAX_ENTRIES_PER_MAPPING && !entries.containsKey(fingerprint)) {
                log.warn("{} - Mapping {}: de-duplication store is full ({} fingerprints), message is processed without a check",
                        tenant, mappingIdentifier, entries.size());
                return false;
            }
        }

        boolean[] duplicate = { false };
        entries.compute(fingerprint, (key, expiry) -> {
            if (expiry != null && expiry > now) {
                duplicate[0] = true;
                return expiry; // keep the original expiry, the window does not slide
            }
            return now + window * 1000L;
        });

        return duplicate[0];
    }

    /** Number of fingerprints currently held for the mapping, including expired ones not yet removed. */
    public int size(String tenant, String mappingIdentifier) {
        var perTenant = store.get(tenant);
        if (perTenant == null) {
            return 0;
        }
        var entries = perTenant.get(mappingIdentifier);
        return entries == null ? 0 : entries.size();
    }

    /** Removes expired fingerprints of one tenant. Called once a minute. */
    public void evictExpired(String tenant) {
        var perTenant = store.get(tenant);
        if (perTenant == null) {
            return;
        }
        long now = clock.millis();
        perTenant.values().forEach(entries -> evictExpired(entries, now));
        // a deleted or idle mapping leaves an empty map behind
        perTenant.values().removeIf(ConcurrentHashMap::isEmpty);
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

    /** SHA-256 of the topic and the raw payload bytes, as lower-case hex. */
    public static String fingerprint(String topic, byte[] rawPayload) {
        MessageDigest digest = sha256();
        if (topic != null) {
            digest.update(topic.getBytes(StandardCharsets.UTF_8));
        }
        digest.update((byte) 0); // separator, so topic "a" + payload "bc" differs from topic "ab" + payload "c"
        if (rawPayload != null) {
            digest.update(rawPayload);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** SHA-256 of a key built by the mapping's de-duplication expression, as lower-case hex. */
    public static String fingerprint(String key) {
        return HexFormat.of().formatHex(sha256().digest(key.getBytes(StandardCharsets.UTF_8)));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }
}
