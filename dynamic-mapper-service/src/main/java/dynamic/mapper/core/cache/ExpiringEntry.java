/*
 * Copyright (c) 2025 Cumulocity GmbH.
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

package dynamic.mapper.core.cache;

import java.time.Duration;

/** A cached value with an absolute expiry time (nanoTime based, immune to wall-clock changes). */
public record ExpiringEntry<V>(V value, long expiresAtNanos) {

    public static final Duration DEFAULT_TTL = Duration.ofHours(1);

    public static <V> ExpiringEntry<V> of(V value, Duration ttl) {
        return new ExpiringEntry<>(value, System.nanoTime() + ttl.toNanos());
    }

    public boolean isExpired() {
        return System.nanoTime() - expiresAtNanos >= 0;
    }
}
