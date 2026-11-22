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

package dynamic.mapper.connector.core.callback;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import dynamic.mapper.configuration.ServiceConfiguration;

/**
 * Counts how often a message failed so a message that always fails does not loop forever.
 *
 * <p>Every connector that retries (a nack, a requeue, a seek back, a reconnect) records each failure here.
 * When the count reaches the limit the message is dropped: the connector acknowledges it and the mapping
 * status keeps the error. The limit is the service configuration {@code maxProcessingAttempts}
 * (default {@value #DEFAULT_MAX_ATTEMPTS}).</p>
 *
 * <p>The key must stay the same when the broker redelivers the message (a message id, an offset, or a
 * hash of topic and payload, not a delivery tag). Entries are bounded: when more than {@value #MAX_TRACKED}
 * messages are tracked the oldest is forgotten, which only means that message gets a fresh count.</p>
 */
public class MessageFailureTracker {

    public static final int DEFAULT_MAX_ATTEMPTS = ServiceConfiguration.DEFAULT_MAX_PROCESSING_ATTEMPTS;
    private static final int MAX_TRACKED = 10_000;

    /**
     * JVM system property for the delay step in milliseconds, 0 to retry at once. The test run sets it to 0, so the
     * tests that fail a message several times do not wait.
     */
    public static final String RETRY_DELAY_STEP_PROPERTY = "dynamic.mapper.retry.delay.step.ms";
    public static final long DEFAULT_RETRY_DELAY_STEP_MS = 5_000;
    public static final long DEFAULT_RETRY_DELAY_MAX_MS = 30_000;

    private final IntSupplier maxAttempts;
    private final long delayStepMs;
    private final long delayMaxMs;
    private final Map<String, Integer> failures = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
            return size() > MAX_TRACKED;
        }
    };

    public MessageFailureTracker(IntSupplier maxAttempts) {
        this(maxAttempts, configuredDelayStepMs(), DEFAULT_RETRY_DELAY_MAX_MS);
    }

    public MessageFailureTracker(IntSupplier maxAttempts, long delayStepMs, long delayMaxMs) {
        this.maxAttempts = maxAttempts;
        this.delayStepMs = Math.max(0, delayStepMs);
        this.delayMaxMs = Math.max(0, delayMaxMs);
    }

    private static long configuredDelayStepMs() {
        Long configured = Long.getLong(RETRY_DELAY_STEP_PROPERTY);
        return configured == null ? DEFAULT_RETRY_DELAY_STEP_MS : configured;
    }

    /**
     * How long to wait before the next attempt at this message: one step per failure so far, up to the maximum
     * (5, 10, 15, 20 seconds, then 30). Without a delay a connector that nacks or reconnects retries within
     * milliseconds, and a Cumulocity outage of a few seconds used up all attempts of every message in that time.
     * Call it after {@link #recordFailureReachedLimit} returned {@code false}.
     */
    public long retryDelayMillis(String key) {
        return Math.min(Math.max(1, failures(key)) * delayStepMs, delayMaxMs);
    }

    /**
     * Waits {@link #retryDelayMillis} before the caller redelivers the message (a nack, a requeue, a recover, a
     * reconnect). It blocks the delivery thread, which is the intent: nothing else on that channel is delivered
     * while the platform is failing. An interrupt ends the wait early and is kept for the caller.
     */
    public void awaitRetryDelay(String key) {
        long delay = retryDelayMillis(key);
        if (delay <= 0) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * A tracker that resolves the tenant's current configuration on every failure. Use this form with a
     * registry lookup: updating the service configuration replaces the registry entry, so a captured
     * instance would keep the old limit.
     */
    public static MessageFailureTracker forConfiguration(Supplier<ServiceConfiguration> configuration) {
        return new MessageFailureTracker(() -> {
            ServiceConfiguration current = configuration.get();
            Integer configured = current == null ? null : current.getMaxProcessingAttempts();
            return configured == null || configured < 1 ? DEFAULT_MAX_ATTEMPTS : configured;
        });
    }

    /** Failures recorded so far for the message, for example to lengthen the wait on a retransmission. */
    public synchronized int failures(String key) {
        return failures.getOrDefault(key, 0);
    }

    /**
     * Records one failure of the message.
     *
     * @return {@code true} if the limit is reached: the counter is cleared and the caller must drop the
     *         message (acknowledge it) instead of retrying
     */
    public synchronized boolean recordFailureReachedLimit(String key) {
        int count = failures.merge(key, 1, Integer::sum);
        if (count >= maxAttempts.getAsInt()) {
            failures.remove(key);
            return true;
        }
        return false;
    }

    /** The message was processed (or dropped): forget its failures. */
    public synchronized void clear(String key) {
        failures.remove(key);
    }

    /** The current limit. */
    public int limit() {
        return maxAttempts.getAsInt();
    }
}
