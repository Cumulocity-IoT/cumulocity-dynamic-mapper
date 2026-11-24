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

package dynamic.mapper.processor.model;

/**
 * Public API for Java extensions: the completion conditions of {@code JavaExtensionContext.collectNative}.
 * Build them with {@link #count}, {@link #keys} or {@link #onLast}, optionally with {@link #withMaxAgeSeconds}.
 */
/**
 * When a group is complete. Any one condition that holds hands the group out; at least one must be given.
 *
 * @param count         complete at this many distinct items, or {@code null}
 * @param keys          complete when all of these item keys are present, or {@code null}
 * @param last          complete now: this call carries the last item
 * @param maxAgeSeconds incomplete groups older than this (measured from their first item) are dropped when
 *                      a call with this option arrives, or {@code null}
 */
public record CollectOptions(Integer count, java.util.Set<String> keys, boolean last, Integer maxAgeSeconds) {
    /** The most items a group may be told to wait for. */
    public static final int MAX_EXPECTED_COUNT = 10_000;
    /** The longest {@code maxAgeSeconds} accepted: one day. */
    public static final int MAX_AGE_SECONDS = 86_400;

    public static CollectOptions count(int count) {
        return new CollectOptions(count, null, false, null);
    }

    /** Complete when all of these item keys are present. */
    public static CollectOptions keys(String... keys) {
        return new CollectOptions(null, java.util.Set.of(keys), false, null);
    }

    /** Complete now: this call carries the last item. */
    public static CollectOptions onLast() {
        return new CollectOptions(null, null, true, null);
    }

    /** The same options, and incomplete groups older than this many seconds are dropped. */
    public CollectOptions withMaxAgeSeconds(int seconds) {
        return new CollectOptions(count, keys, last, seconds);
    }

    /**
     * Checks the options the way {@code context.collect} of a Smart Function does.
     *
     * @return what is wrong with them, or {@code null} if they are valid
     */
    public String problem() {
        if (count != null && (count < 1 || count > MAX_EXPECTED_COUNT)) {
            return "count must be a whole number from 1 to " + MAX_EXPECTED_COUNT;
        }
        if (keys != null) {
            if (keys.isEmpty() || keys.size() > MAX_EXPECTED_COUNT) {
                return "keys must be a list of 1 to " + MAX_EXPECTED_COUNT + " item keys";
            }
            if (keys.stream().anyMatch(java.util.Objects::isNull)) {
                return "keys must be strings";
            }
        }
        if (maxAgeSeconds != null && (maxAgeSeconds < 1 || maxAgeSeconds > MAX_AGE_SECONDS)) {
            return "maxAgeSeconds must be a whole number from 1 to " + MAX_AGE_SECONDS;
        }
        if (count == null && keys == null && !last) {
            return "give count, keys or last, otherwise the group never completes";
        }
        return null;
    }
}
