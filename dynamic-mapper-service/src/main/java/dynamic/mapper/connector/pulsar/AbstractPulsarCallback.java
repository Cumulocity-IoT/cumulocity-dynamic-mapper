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

package dynamic.mapper.connector.pulsar;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageListener;
import org.apache.pulsar.client.api.PulsarClientException;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.callback.GenericMessageCallback;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.processor.model.ProcessingResultWrapper;
import lombok.extern.slf4j.Slf4j;

/**
 * Abstract base for Pulsar broker callbacks.
 *
 * <p>Provides shared infrastructure used by both {@link PulsarCallback} (plain Pulsar)
 * and {@link MQTTServicePulsarCallback} (Cumulocity MQTT Service over Pulsar):
 * <ul>
 *   <li>Common fields and constructor wiring</li>
 *   <li>{@link #cancelAndDrain(ProcessingResultWrapper)} — cancel a processing future and wait
 *       up to 2 seconds for it to drain before releasing the Pulsar ack slot</li>
 *   <li>{@link #handleFailureOrPoisonPill(Consumer, Message, String, String)} — per-message
 *       failure counting with poison-pill detection after {@link #MAX_CONSECUTIVE_FAILURES}
 *       consecutive errors</li>
 * </ul>
 */
@Slf4j
public abstract class AbstractPulsarCallback implements MessageListener<byte[]> {

    /**
     * Maximum consecutive failures per message before it is treated as a poison pill,
     * ACKed (discarded), and a hard error is logged to break an infinite redelivery loop.
     */
    protected static final int MAX_CONSECUTIVE_FAILURES = 5;

    protected final GenericMessageCallback genericMessageCallback;
    protected final String tenant;
    protected final String connectorIdentifier;
    protected final String connectorName;
    protected final ServiceConfiguration serviceConfiguration;
    protected final ExecutorService virtualThreadPool;

    /** Per-message consecutive failure counter (message ID as key). */
    protected final Map<String, AtomicInteger> failureCountPerMessage = new ConcurrentHashMap<>();

    protected AbstractPulsarCallback(
            String tenant,
            ConfigurationRegistry configurationRegistry,
            GenericMessageCallback callback,
            String connectorIdentifier,
            String connectorName) {
        this.genericMessageCallback = callback;
        this.tenant = tenant;
        this.connectorIdentifier = connectorIdentifier;
        this.connectorName = connectorName;
        this.serviceConfiguration = configurationRegistry.getServiceConfiguration(tenant);
        this.virtualThreadPool = configurationRegistry.getVirtualThreadPool();
    }

    /**
     * Cancels an in-flight processing task and waits up to 2 seconds for the future to drain.
     *
     * <p>The active-cancellation checks in {@code C8YAgent.createMEAO()} prevent new HTTP calls
     * from starting, so threads typically exit quickly. The 2-second cap is a fail-safe for
     * threads already mid-flight in an HTTP call that cannot be interrupted immediately.
     *
     * @param processedResults the result wrapper whose future should be cancelled
     * @return true if the future completed within the 2-second window, false otherwise
     */
    protected boolean cancelAndDrain(ProcessingResultWrapper<?> processedResults) {
        var cancelResult = processedResults.cancelProcessing();
        log.info("{} - Cancellation result: future was cancelled={}, connector: {}",
                tenant, cancelResult, connectorIdentifier);

        for (int i = 0; i < 20; i++) {
            if (processedResults.getProcessingResult().isDone()) {
                log.info("{} - Future completed after {}ms wait, connector: {}",
                        tenant, (i + 1) * 100, connectorIdentifier);
                return true;
            }
            try {
                Thread.sleep(100); //NOSONAR intentional wait for future completion
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("{} - Interrupted while waiting for future completion, connector: {}",
                        tenant, connectorIdentifier);
                return false;
            }
        }
        log.error("{} - Future did NOT complete within 2 seconds after cancellation! "
                + "Check for long-running HTTP calls or other blocking I/O. connector: {}",
                tenant, connectorIdentifier);
        return false;
    }

    /**
     * Handles a per-message failure by either negative-ACKing for redelivery or,
     * once {@link #MAX_CONSECUTIVE_FAILURES} consecutive failures have occurred,
     * treating the message as a poison pill and positive-ACKing it (discarding it)
     * to break an infinite redelivery loop.
     *
     * @param consumer  the Pulsar consumer (for ack/nack)
     * @param message   the unacknowledged Pulsar message
     * @param messageId unique message identifier for per-message failure tracking
     * @param topic     human-readable topic string for logging
     */
    protected void handleFailureOrPoisonPill(Consumer<byte[]> consumer, Message<byte[]> message,
            String messageId, String topic) {
        int failureCount = failureCountPerMessage
                .computeIfAbsent(messageId, k -> new AtomicInteger(0))
                .incrementAndGet();

        if (failureCount >= MAX_CONSECUTIVE_FAILURES) {
            log.error(
                    "{} - POISON PILL: {} consecutive failures without success — ACKing message to prevent "
                            + "infinite redelivery loop. Message is discarded. messageId: {}, topic: [{}], connector: {}",
                    tenant, failureCount, messageId, topic, connectorIdentifier);
            failureCountPerMessage.remove(messageId);
            try {
                consumer.acknowledge(message);
            } catch (PulsarClientException e) {
                log.error(
                        "{} - Error acknowledging poison-pill Pulsar message: messageId: {}, topic: [{}], connector: {}",
                        tenant, messageId, topic, connectorIdentifier, e);
            }
            return;
        }

        if (failureCount > 1) {
            log.warn("{} - Redelivery attempt {} for message: {}, topic: [{}], connector: {}",
                    tenant, failureCount, messageId, topic, connectorIdentifier);
        }

        consumer.negativeAcknowledge(message);
    }
}
