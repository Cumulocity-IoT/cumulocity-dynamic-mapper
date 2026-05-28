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

package dynamic.mapper.connector.core.client;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import dynamic.mapper.configuration.ConnectorConfiguration;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Qos;
import dynamic.mapper.processor.model.ProcessingContext;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Unit tests for {@link AConnectorClient#retryOperation(String, int, long, SupplierWithException)}.
 *
 * <p>A minimal {@link AConnectorClient} subclass is created in-package so that
 * the {@code protected} method is accessible without reflection.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConnectorRetryReconnectTest {

    /**
     * Minimal concrete subclass that only exposes {@code retryOperation} as
     * {@code public} and implements all abstract methods as no-ops.
     */
    static class TestableConnector extends AConnectorClient {

        TestableConnector() {
            this.tenant = "test-tenant";
        }

        // ── Abstract method implementations (no-ops) ───────────────────────

        @Override
        public boolean initialize() {
            return true;
        }

        @Override
        public void connect() {
            // no-op
        }

        @Override
        public void disconnect() {
            // no-op
        }

        @Override
        public boolean isConfigValid(ConnectorConfiguration configuration) {
            return true;
        }

        @Override
        public void publishMEAO(ProcessingContext<?> context) {
            // no-op
        }

        @Override
        protected void subscribe(String topic, Qos qos) {
            // no-op
        }

        @Override
        protected void unsubscribe(String topic) {
            // no-op
        }

        @Override
        protected void connectorSpecificHousekeeping(String tenant) {
            // no-op
        }

        @Override
        public Boolean supportsWildcardInTopic(Direction direction) {
            return Boolean.FALSE;
        }

        @Override
        public List<Direction> supportedDirections() {
            return List.of(Direction.INBOUND);
        }

        // ── Exposed for tests ──────────────────────────────────────────────

        public <T> T retryPublic(String operationName, int maxAttempts, long baseDelayMs,
                SupplierWithException<T> operation) throws ConnectorException {
            return retryOperation(operationName, maxAttempts, baseDelayMs, operation);
        }
    }

    private TestableConnector connector;

    @BeforeEach
    void setUp() {
        connector = new TestableConnector();
    }

    // ── Success on first attempt ──────────────────────────────────────────

    @Test
    void successOnFirstAttempt_returnsResult() throws ConnectorException {
        String result = connector.retryPublic("test-op", 3, 1, () -> "ok");
        assertEquals("ok", result);
    }

    @Test
    void nullReturn_isPropagated() throws ConnectorException {
        String result = connector.retryPublic("test-op", 3, 1, () -> null);
        assertNull(result);
    }

    // ── Transient failure, then success ──────────────────────────────────

    @Test
    void transientFailure_succeedsOnSecondAttempt() throws ConnectorException {
        AtomicInteger attempts = new AtomicInteger(0);

        String result = connector.retryPublic("transient-op", 3, 1, () -> {
            if (attempts.incrementAndGet() < 2) {
                throw new RuntimeException("transient error");
            }
            return "recovered";
        });

        assertEquals("recovered", result);
        assertEquals(2, attempts.get());
    }

    @Test
    void twoTransientFailures_succeedsOnThirdAttempt() throws ConnectorException {
        AtomicInteger attempts = new AtomicInteger(0);

        String result = connector.retryPublic("transient-op", 3, 1, () -> {
            if (attempts.incrementAndGet() < 3) {
                throw new RuntimeException("transient error #" + attempts.get());
            }
            return "finally";
        });

        assertEquals("finally", result);
        assertEquals(3, attempts.get());
    }

    // ── All attempts exhausted → ConnectorException ───────────────────────

    @Test
    void allAttemptsExhausted_throwsConnectorException() {
        ConnectorException ex = assertThrows(ConnectorException.class, () ->
                connector.retryPublic("failing-op", 3, 1, () -> {
                    throw new RuntimeException("always fails");
                }));

        assertNotNull(ex.getMessage());
        assertTrue(ex.getMessage().contains("failing-op"),
                "Exception message must contain the operation name, was: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("3"),
                "Exception message must state the attempt count, was: " + ex.getMessage());
    }

    @Test
    void singleAttempt_failsImmediately_throwsConnectorException() {
        ConnectorException ex = assertThrows(ConnectorException.class, () ->
                connector.retryPublic("single-fail", 1, 1, () -> {
                    throw new IllegalStateException("one-shot failure");
                }));

        assertNotNull(ex.getMessage());
        assertTrue(ex.getMessage().contains("single-fail"));
    }

    // ── Cause chain ───────────────────────────────────────────────────────

    @Test
    void wrappedException_causeIsOriginalException() {
        RuntimeException root = new RuntimeException("root cause");

        ConnectorException ex = assertThrows(ConnectorException.class, () ->
                connector.retryPublic("causal-op", 2, 1, () -> {
                    throw root;
                }));

        assertSame(root, ex.getCause(),
                "ConnectorException must chain the original exception as its cause");
    }

    // ── Exponential backoff invocation count ─────────────────────────────

    @Test
    void exponentialBackoff_delayDoublesOnEachRetry() throws ConnectorException {
        // Verify that the implementation uses at most maxAttempts calls —
        // the backoff timing itself cannot be asserted deterministically in a unit test,
        // but we can confirm that exactly maxAttempts executions occurred before giving up.
        AtomicInteger callCount = new AtomicInteger(0);

        assertThrows(ConnectorException.class, () ->
                connector.retryPublic("backoff-op", 3, 1, () -> {
                    callCount.incrementAndGet();
                    throw new RuntimeException("fail");
                }));

        assertEquals(3, callCount.get(),
                "Operation must be attempted exactly maxAttempts times");
    }

    // ── Integer return type ───────────────────────────────────────────────

    @Test
    void integerResult_returnedCorrectly() throws ConnectorException {
        Integer result = connector.retryPublic("int-op", 2, 1, () -> 42);
        assertEquals(42, result);
    }
}
