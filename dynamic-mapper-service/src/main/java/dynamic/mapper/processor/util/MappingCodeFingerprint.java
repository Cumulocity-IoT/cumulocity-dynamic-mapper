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

package dynamic.mapper.processor.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the per-message path needs to know about a Smart Function's Base64 source, computed once per
 * distinct code instead of once per message.
 *
 * <p>Before this class, every message of a Smart Function mapping hashed the whole Base64 code with
 * SHA-256 (the context pool key) and, with ESM support off, Base64-decoded it and scanned it with a
 * regular expression (the "export syntax is stripped" warning). Smart Functions that bundle a
 * library are hundreds of kilobytes, so that was milliseconds of CPU per message, spent to
 * re-derive a value that only changes when the mapping is edited.
 *
 * <p>Entries are keyed by tenant and mapping identifier and validated against the code itself
 * ({@link String#equals} is an identity check first, so an unchanged mapping costs a pointer
 * compare). The cache is a small LRU, so deleted mappings cannot pin their code in memory.
 */
public final class MappingCodeFingerprint {

    private static final int MAX_ENTRIES = 512;

    private record Fingerprint(String code, String sha256Hex, Boolean containsExport) {
    }

    private static final Map<String, Fingerprint> CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Fingerprint> eldest) {
                    return size() > MAX_ENTRIES;
                }
            });

    private MappingCodeFingerprint() {
    }

    /** SHA-256 (hex) of the Base64 code; the code part of the context pool key. */
    public static String sha256Hex(String tenant, String mappingIdentifier, String base64Code) {
        return entryFor(tenant, mappingIdentifier, base64Code).sha256Hex();
    }

    /**
     * Whether the decoded code contains an ES module {@code export} statement, see
     * {@link JavaScriptModuleStripper#containsExportStatement}.
     */
    public static boolean containsExport(String tenant, String mappingIdentifier, String base64Code) {
        String key = key(tenant, mappingIdentifier);
        Fingerprint entry = entryFor(key, base64Code);
        if (entry.containsExport() != null) {
            return entry.containsExport();
        }
        boolean contains = JavaScriptModuleStripper.containsExportStatement(
                new String(Base64.getDecoder().decode(base64Code), StandardCharsets.UTF_8));
        // Replace with the completed entry; a racing thread computing the same value is harmless.
        CACHE.put(key, new Fingerprint(entry.code(), entry.sha256Hex(), contains));
        return contains;
    }

    private static Fingerprint entryFor(String tenant, String mappingIdentifier, String base64Code) {
        return entryFor(key(tenant, mappingIdentifier), base64Code);
    }

    private static Fingerprint entryFor(String key, String base64Code) {
        Fingerprint entry = CACHE.get(key);
        if (entry != null && (entry.code() == base64Code || entry.code().equals(base64Code))) {
            return entry;
        }
        Fingerprint fresh = new Fingerprint(base64Code, sha256Hex(base64Code), null);
        CACHE.put(key, fresh);
        return fresh;
    }

    private static String key(String tenant, String mappingIdentifier) {
        return tenant + ":" + mappingIdentifier;
    }

    private static String sha256Hex(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }
}
