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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** The in-progress flags and the intentional-disconnect mark that the connector subclasses rely on. */
@Timeout(30)
class ConnectionGuardTest {

    private ConnectionGuard guard;

    @BeforeEach
    void setUp() {
        guard = new ConnectionGuard(() -> "t1");
    }

    @Test
    void freshGuard_hasNothingInProgress_andNoIntentionalDisconnect() {
        assertFalse(guard.isConnecting());
        assertFalse(guard.isDisconnecting());
        assertFalse(guard.isDisconnectIntentional());
    }

    // ── connect ──────────────────────────────────────────────────────────

    @Test
    void beginConnection_firstCallProceeds_secondIsRejected() {
        assertTrue(guard.beginConnection());
        assertTrue(guard.isConnecting());

        assertFalse(guard.beginConnection());
        assertTrue(guard.isConnecting(), "a rejected call leaves the running connect marked");
    }

    @Test
    void endConnection_allowsTheNextConnect() {
        guard.beginConnection();
        guard.endConnection();

        assertFalse(guard.isConnecting());
        assertTrue(guard.beginConnection());
    }

    @Test
    void endConnection_withoutBegin_isHarmless() {
        guard.endConnection();

        assertTrue(guard.beginConnection());
    }

    @Test
    void connect_andDisconnect_areTrackedIndependently() {
        assertTrue(guard.beginConnection());

        assertTrue(guard.beginDisconnection(), "this guard does not exclude connect from disconnect");
        assertTrue(guard.isConnecting());
        assertTrue(guard.isDisconnecting());
    }

    // ── disconnect ───────────────────────────────────────────────────────

    @Test
    void beginDisconnection_firstCallProceeds_secondIsRejected() {
        assertTrue(guard.beginDisconnection());
        assertTrue(guard.isDisconnecting());

        assertFalse(guard.beginDisconnection());
    }

    @Test
    void endDisconnection_allowsTheNextDisconnect() {
        guard.beginDisconnection();
        guard.endDisconnection();

        assertFalse(guard.isDisconnecting());
        assertTrue(guard.beginDisconnection());
    }

    // ── intentional disconnect ───────────────────────────────────────────

    @Test
    void duringDisconnect_theDisconnectIsIntentional() {
        guard.beginDisconnection();

        assertTrue(guard.isDisconnectIntentional());
    }

    @Test
    void afterOurDisconnectEnded_itStaysIntentional_untilTheNextConnectBegins() {
        guard.beginDisconnection();
        guard.endDisconnection();

        // A late connection-lost callback for our own close must not be reported as a drop.
        assertTrue(guard.isDisconnectIntentional());

        guard.beginConnection();
        assertFalse(guard.isDisconnectIntentional(), "a new connect clears the mark");
    }

    @Test
    void rejectedBeginConnection_doesNotClearTheIntentionalMark() {
        guard.beginConnection();
        guard.beginDisconnection();
        guard.endDisconnection();
        assertTrue(guard.isDisconnectIntentional());

        assertFalse(guard.beginConnection(), "a connect is still running");

        assertTrue(guard.isDisconnectIntentional());
    }

    @Test
    void rejectedBeginDisconnection_keepsTheMark() {
        guard.beginDisconnection();

        assertFalse(guard.beginDisconnection());

        assertTrue(guard.isDisconnectIntentional());
    }

    @Test
    void connectAfterConnect_withoutDisconnect_neverBecomesIntentional() {
        guard.beginConnection();
        guard.endConnection();
        guard.beginConnection();

        assertFalse(guard.isDisconnectIntentional());
    }

    // ── concurrency ──────────────────────────────────────────────────────

    @Test
    void concurrentBeginConnection_letsExactlyOneCallerProceed() throws Exception {
        int callers = 16;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger proceeded = new AtomicInteger();
            java.util.List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < callers; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    if (guard.beginConnection()) {
                        proceeded.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get();
            }

            assertEquals(1, proceeded.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentBeginDisconnection_letsExactlyOneCallerProceed() throws Exception {
        int callers = 16;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger proceeded = new AtomicInteger();
            java.util.List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < callers; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    if (guard.beginDisconnection()) {
                        proceeded.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get();
            }

            assertEquals(1, proceeded.get());
        } finally {
            pool.shutdownNow();
        }
    }
}
