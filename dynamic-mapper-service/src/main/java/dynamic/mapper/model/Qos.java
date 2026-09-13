/*
 * Copyright (c) 2022-2025 Cumulocity GmbH.
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

package dynamic.mapper.model;

import java.util.Collection;

/**
 * Delivery guarantee requested by a mapping, modelled after the MQTT QoS levels.
 *
 * <p>The enum constants are ordered from weakest to strongest guarantee, and
 * {@link #getLevel()} is the numeric MQTT level (0, 1, 2). Non-MQTT connectors map these
 * onto their own primitives (AMQP delivery mode, Kafka offset-commit timing, Pulsar
 * subscription type / ack mode) — see {@code docs/feature/reliability.md}.
 *
 * <p>Comparisons must go through {@link #getLevel()} / the helpers below rather than
 * {@code ordinal()} at call sites, so that the "which is stronger" rule lives in one place.
 */
public enum Qos {
    AT_MOST_ONCE(0, "At most once"),
    AT_LEAST_ONCE(1, "At least once"),
    EXACTLY_ONCE(2, "Exactly once");

    /** Default applied whenever a mapping or message carries no explicit QoS. */
    public static final Qos DEFAULT = AT_LEAST_ONCE;

    private final int level;
    private final String label;

    Qos(int level, String label) {
        this.level = level;
        this.label = label;
    }

    /** Numeric MQTT level: 0, 1 or 2. */
    public int getLevel() {
        return level;
    }

    /** Human readable label, kept in sync with the label shown in the UI. */
    public String getLabel() {
        return label;
    }

    /** {@code true} for every level that requires the message to be acknowledged after processing. */
    public boolean requiresAcknowledgement() {
        return level > AT_MOST_ONCE.level;
    }

    /**
     * Resolve a numeric MQTT level back to an enum constant.
     *
     * @throws IllegalArgumentException if {@code level} is not 0, 1 or 2
     */
    public static Qos ofLevel(int level) {
        for (Qos qos : values()) {
            if (qos.level == level) {
                return qos;
            }
        }
        throw new IllegalArgumentException("Unsupported QoS level: " + level);
    }

    /** Null-safe: returns {@code qos}, or {@link #DEFAULT} if it is {@code null}. */
    public static Qos orDefault(Qos qos) {
        return qos == null ? DEFAULT : qos;
    }

    /** The stronger of the two guarantees; {@code null} arguments are ignored. */
    public static Qos max(Qos a, Qos b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.level >= b.level ? a : b;
    }

    /** The weaker of the two guarantees; {@code null} arguments are ignored. */
    public static Qos min(Qos a, Qos b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.level <= b.level ? a : b;
    }

    /**
     * Clamp a requested QoS to what a connector actually supports: the strongest supported
     * level that is not stronger than {@code requested}; if the connector supports nothing that
     * weak (e.g. HTTP, which only ever delivers at-least-once), the weakest supported level.
     *
     * @param requested the QoS asked for by the mapping; {@code null} is treated as {@link #DEFAULT}
     * @param supported the levels the connector supports; {@code null}/empty means "no restriction"
     * @return the level the connector should actually use, never {@code null}
     */
    public static Qos clampTo(Qos requested, Collection<Qos> supported) {
        Qos target = orDefault(requested);
        if (supported == null || supported.isEmpty() || supported.contains(target)) {
            return target;
        }

        Qos best = null;
        Qos weakest = null;
        for (Qos candidate : supported) {
            if (candidate == null) {
                continue;
            }
            if (candidate.level < target.level) {
                best = max(best, candidate);
            }
            weakest = min(weakest, candidate);
        }
        if (best != null) {
            return best;
        }
        // Nothing weaker is supported — fall back to the weakest level the connector offers,
        // which is stronger than requested. Over-delivering is safe; silently using an
        // unsupported level is not.
        return weakest != null ? weakest : target;
    }
}
