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

package dynamic.mapper.connector.core.client;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * The backoff of the housekeeping's reconnect attempts, shared by the MQTT and the AMQP clients.
 *
 * <p>Without it a connector that is down for a long time would start a new reconnect on every 30 second
 * housekeeping tick, forever. Each attempt waits one step longer than the one before, up to a maximum:
 * 10, 20, 30 ... 300 seconds. A successful connect starts over.</p>
 *
 * <p>Thread safety: the attempt counter is incremented on the housekeeping thread and reset from a client
 * library callback thread, so it is an {@link AtomicInteger} (a plain {@code ++} could lose a concurrent reset)
 * and the due time is volatile.</p>
 */
public final class ReconnectBackoff {

    public static final long STEP_MS = 10_000;
    public static final long MAX_MS = 300_000; // 5 minutes

    /** One scheduled attempt: its number and how long the next one waits. */
    public record Attempt(int number, long delayMs) {
    }

    private final long stepMs;
    private final long maxMs;
    private final AtomicInteger attempts = new AtomicInteger(0);
    private volatile long nextAttemptAtMs = 0;

    public ReconnectBackoff() {
        this(STEP_MS, MAX_MS);
    }

    public ReconnectBackoff(long stepMs, long maxMs) {
        this.stepMs = stepMs;
        this.maxMs = maxMs;
    }

    /** A successful connect: the next loss is retried at once, then backed off. */
    public void reset() {
        attempts.set(0);
        nextAttemptAtMs = 0;
    }

    /**
     * A successful connect of a client that reconnects by itself (the MQTT clients): the housekeeping stays out
     * of the way until {@link #retryNow()} says the client gave up.
     */
    public void holdWhileConnected() {
        attempts.set(0);
        nextAttemptAtMs = Long.MAX_VALUE;
    }

    /** The connection dropped unexpectedly: the next housekeeping cycle reconnects, without waiting. */
    public void retryNow() {
        nextAttemptAtMs = System.currentTimeMillis();
    }

    /** Whether an attempt is allowed at {@code now}. */
    public boolean isDue(long now) {
        return now >= nextAttemptAtMs;
    }

    /**
     * Starts an attempt at {@code now}: counts it and holds the next one back by one step more than the last, up to
     * the maximum.
     */
    public Attempt next(long now) {
        int number = attempts.incrementAndGet();
        long delay = Math.min(number * stepMs, maxMs);
        nextAttemptAtMs = now + delay;
        return new Attempt(number, delay);
    }
}
