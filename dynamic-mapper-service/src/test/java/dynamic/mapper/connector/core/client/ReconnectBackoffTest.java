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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ReconnectBackoffTest {

    private static final long NOW = 1_000_000L;

    @Test
    void aNewBackoffAllowsTheFirstAttemptAtOnce() {
        assertTrue(new ReconnectBackoff().isDue(NOW));
    }

    @Test
    void eachAttemptWaitsOneStepLongerUpToTheMaximum() {
        ReconnectBackoff backoff = new ReconnectBackoff(10_000, 50_000);

        long[] expected = { 10_000, 20_000, 30_000, 40_000, 50_000, 50_000 };
        for (int i = 0; i < expected.length; i++) {
            ReconnectBackoff.Attempt attempt = backoff.next(NOW);
            assertEquals(i + 1, attempt.number());
            assertEquals(expected[i], attempt.delayMs());
        }
    }

    @Test
    void theNextAttemptIsHeldBackUntilTheDelayPassed() {
        ReconnectBackoff backoff = new ReconnectBackoff(10_000, 300_000);
        backoff.next(NOW); // 10 seconds

        assertFalse(backoff.isDue(NOW));
        assertFalse(backoff.isDue(NOW + 9_999));
        assertTrue(backoff.isDue(NOW + 10_000));
    }

    @Test
    void aSuccessfulConnectStartsOver() {
        ReconnectBackoff backoff = new ReconnectBackoff(10_000, 300_000);
        backoff.next(NOW);
        backoff.next(NOW);

        backoff.reset();

        assertTrue(backoff.isDue(NOW));
        assertEquals(10_000, backoff.next(NOW).delayMs(), "back to the first step");
    }

    @Test
    void aClientThatReconnectsByItselfHoldsTheHousekeepingBackUntilItGivesUp() {
        ReconnectBackoff backoff = new ReconnectBackoff();

        backoff.holdWhileConnected();
        assertFalse(backoff.isDue(Long.MAX_VALUE - 1), "the housekeeping stays out of the way");

        backoff.retryNow(); // the connection dropped unexpectedly
        assertTrue(backoff.isDue(System.currentTimeMillis()));
    }

    @Test
    void theDefaultsAreTenSecondsUpToFiveMinutes() {
        ReconnectBackoff backoff = new ReconnectBackoff();
        long delay = 0;
        for (int i = 0; i < 100; i++) {
            delay = backoff.next(NOW).delayMs();
        }

        assertEquals(300_000, delay);
        assertEquals(10_000, new ReconnectBackoff().next(NOW).delayMs());
    }
}
