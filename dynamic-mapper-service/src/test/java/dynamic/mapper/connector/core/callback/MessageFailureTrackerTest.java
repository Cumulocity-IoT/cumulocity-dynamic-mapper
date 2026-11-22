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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import dynamic.mapper.configuration.ServiceConfiguration;

class MessageFailureTrackerTest {

    private final MessageFailureTracker tracker = new MessageFailureTracker(() -> 3);

    @Test
    void dropsAMessageOnTheConfiguredFailure() {
        assertFalse(tracker.recordFailureReachedLimit("m"));
        assertFalse(tracker.recordFailureReachedLimit("m"));
        assertTrue(tracker.recordFailureReachedLimit("m"), "the third failure reaches the limit of 3");
    }

    @Test
    void startsFreshAfterADrop() {
        for (int i = 0; i < 3; i++) {
            tracker.recordFailureReachedLimit("m");
        }

        assertEquals(0, tracker.failures("m"));
        assertFalse(tracker.recordFailureReachedLimit("m"));
    }

    @Test
    void aSuccessForgetsTheFailures() {
        tracker.recordFailureReachedLimit("m");
        tracker.recordFailureReachedLimit("m");

        tracker.clear("m");

        assertEquals(0, tracker.failures("m"));
        assertFalse(tracker.recordFailureReachedLimit("m"));
    }

    @Test
    void messagesAreCountedSeparately() {
        tracker.recordFailureReachedLimit("a");
        tracker.recordFailureReachedLimit("a");

        assertFalse(tracker.recordFailureReachedLimit("b"));
        assertEquals(2, tracker.failures("a"));
    }

    @Test
    void theLimitFollowsTheConfigurationOnEveryFailure() {
        ServiceConfiguration configuration = mock(ServiceConfiguration.class);
        when(configuration.getMaxProcessingAttempts()).thenReturn(2);
        MessageFailureTracker followed = MessageFailureTracker.forConfiguration(() -> configuration);

        assertFalse(followed.recordFailureReachedLimit("m"));
        assertTrue(followed.recordFailureReachedLimit("m"));
        assertEquals(2, followed.limit());

        when(configuration.getMaxProcessingAttempts()).thenReturn(4);
        assertEquals(4, followed.limit());
    }

    @Test
    void theLimitFollowsAReplacedConfigurationObject() {
        ServiceConfiguration first = mock(ServiceConfiguration.class);
        when(first.getMaxProcessingAttempts()).thenReturn(2);
        ServiceConfiguration second = mock(ServiceConfiguration.class);
        when(second.getMaxProcessingAttempts()).thenReturn(7);
        java.util.concurrent.atomic.AtomicReference<ServiceConfiguration> registry =
                new java.util.concurrent.atomic.AtomicReference<>(first);
        MessageFailureTracker followed = MessageFailureTracker.forConfiguration(registry::get);

        assertEquals(2, followed.limit());
        registry.set(second);
        assertEquals(7, followed.limit());
    }

    @Test
    void aMissingOrInvalidConfigurationFallsBackToTheDefault() {
        ServiceConfiguration configuration = mock(ServiceConfiguration.class);
        when(configuration.getMaxProcessingAttempts()).thenReturn(null, 0, -1);

        assertEquals(ServiceConfiguration.DEFAULT_MAX_PROCESSING_ATTEMPTS,
                MessageFailureTracker.forConfiguration(() -> configuration).limit());
        assertEquals(ServiceConfiguration.DEFAULT_MAX_PROCESSING_ATTEMPTS,
                MessageFailureTracker.forConfiguration(() -> configuration).limit());
        assertEquals(ServiceConfiguration.DEFAULT_MAX_PROCESSING_ATTEMPTS,
                MessageFailureTracker.forConfiguration(() -> configuration).limit());
        assertEquals(ServiceConfiguration.DEFAULT_MAX_PROCESSING_ATTEMPTS,
                MessageFailureTracker.forConfiguration(() -> null).limit());
        assertEquals(5, ServiceConfiguration.DEFAULT_MAX_PROCESSING_ATTEMPTS);
    }

    @Test
    void memoryIsBounded() {
        for (int i = 0; i < 20_000; i++) {
            tracker.recordFailureReachedLimit("m" + i);
        }

        assertEquals(0, tracker.failures("m0"), "the oldest entries are forgotten");
        assertEquals(1, tracker.failures("m19999"));
    }

    // ---- the delay between attempts

    @Test
    void theDelayGrowsWithTheFailuresUpToTheMaximum() {
        MessageFailureTracker delayed = new MessageFailureTracker(() -> 10, 5_000, 30_000);

        long[] expected = { 5_000, 10_000, 15_000, 20_000, 25_000, 30_000, 30_000 };
        for (long delay : expected) {
            delayed.recordFailureReachedLimit("m");
            assertEquals(delay, delayed.retryDelayMillis("m"));
        }
    }

    @Test
    void aDelayStepOfZeroRetriesAtOnce() {
        MessageFailureTracker immediate = new MessageFailureTracker(() -> 10, 0, 30_000);
        immediate.recordFailureReachedLimit("m");

        assertEquals(0, immediate.retryDelayMillis("m"));
        long start = System.nanoTime();
        immediate.awaitRetryDelay("m");
        assertTrue((System.nanoTime() - start) / 1_000_000 < 50);
    }

    @Test
    void awaitRetryDelayWaitsForTheDelay() {
        MessageFailureTracker delayed = new MessageFailureTracker(() -> 10, 40, 1_000);
        delayed.recordFailureReachedLimit("m");

        long start = System.nanoTime();
        delayed.awaitRetryDelay("m");

        assertTrue((System.nanoTime() - start) / 1_000_000 >= 35, "waited about one step");
    }

    @Test
    void anInterruptEndsTheWaitAndIsKeptForTheCaller() {
        MessageFailureTracker delayed = new MessageFailureTracker(() -> 10, 60_000, 60_000);
        delayed.recordFailureReachedLimit("m");

        Thread.currentThread().interrupt();
        long start = System.nanoTime();
        delayed.awaitRetryDelay("m");

        assertTrue((System.nanoTime() - start) / 1_000_000 < 1_000, "did not sleep a minute");
        assertTrue(Thread.interrupted(), "the interrupt flag is restored");
    }

    @Test
    void theDefaultStepComesFromTheSystemPropertyWhichTheTestRunSetsToZero() {
        // surefire and failsafe pass -Ddynamic.mapper.retry.delay.step.ms=0: tests that fail a message several
        // times must not wait for the production delay
        assertEquals("0", System.getProperty(MessageFailureTracker.RETRY_DELAY_STEP_PROPERTY));
        MessageFailureTracker fromProperty = new MessageFailureTracker(() -> 10);
        fromProperty.recordFailureReachedLimit("m");
        assertEquals(0, fromProperty.retryDelayMillis("m"));
    }

    // ---- settle: the decision every connector takes after the pipeline ran

    @Test
    void settleAcknowledgesAndForgetsTheFailuresOnSuccess() {
        tracker.recordFailureReachedLimit("m");

        assertEquals(MessageFailureTracker.Settlement.ACK,
                tracker.settle("m", dynamic.mapper.processor.util.ProcessingResultHelper.AckOutcome.ACK));

        assertEquals(0, tracker.failures("m"));
    }

    @Test
    void settleCountsAFailureAndRetriesUntilTheLimitThenDrops() {
        var retry = dynamic.mapper.processor.util.ProcessingResultHelper.AckOutcome.RETRY;

        assertEquals(MessageFailureTracker.Settlement.RETRY, tracker.settle("m", retry));
        assertEquals(MessageFailureTracker.Settlement.RETRY, tracker.settle("m", retry));
        assertEquals(MessageFailureTracker.Settlement.DROP, tracker.settle("m", retry)); // the limit is 3
        assertEquals(0, tracker.failures("m"), "a dropped message starts fresh");
    }

    @Test
    void settleDoesNotCountABrokerConnectionThatIsDown() {
        var unavailable = dynamic.mapper.processor.util.ProcessingResultHelper.AckOutcome.RETRY_CONNECTOR_UNAVAILABLE;

        for (int i = 0; i < 10; i++) {
            assertEquals(MessageFailureTracker.Settlement.RETRY, tracker.settle("m", unavailable));
        }

        assertEquals(0, tracker.failures("m"), "it says nothing about the message");
    }

    // ---- the wait budget of a retransmission

    @Test
    void aRetransmissionGetsMoreTimeCappedAtThreeTimesTheBase() {
        assertEquals(1_000L, tracker.escalatedTimeoutMs("m", 1_000));   // first attempt
        tracker.recordFailureReachedLimit("m");
        assertEquals(2_000L, tracker.escalatedTimeoutMs("m", 1_000));
        tracker.recordFailureReachedLimit("m");
        assertEquals(3_000L, tracker.escalatedTimeoutMs("m", 1_000));
        MessageFailureTracker patient = new MessageFailureTracker(() -> 10);
        for (int i = 0; i < 6; i++) {
            patient.recordFailureReachedLimit("m");
        }
        assertEquals(3_000L, patient.escalatedTimeoutMs("m", 1_000), "capped");
    }

    @Test
    void noBaseBudgetMeansTheDefaultWait() {
        assertEquals(null, tracker.escalatedTimeoutMs("m", 0));
        assertEquals(null, tracker.escalatedTimeoutMs("m", -5));
    }
}
