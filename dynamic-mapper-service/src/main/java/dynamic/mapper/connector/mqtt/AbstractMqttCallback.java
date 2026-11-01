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

package dynamic.mapper.connector.mqtt;

import dynamic.mapper.util.TenantLogContext;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import com.hivemq.client.mqtt.datatypes.MqttTopic;

import dynamic.mapper.configuration.ConnectorConfiguration;
import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.connector.core.callback.GenericMessageCallback;
import dynamic.mapper.connector.core.callback.MessageFailureTracker;
import dynamic.mapper.core.ServiceRegistry;
import dynamic.mapper.processor.runtime.ProcessingResultWrapper;
import dynamic.mapper.processor.util.ProcessingResultHelper.AckOutcome;
import dynamic.mapper.processor.util.ProcessingResultHelper;
import lombok.extern.slf4j.Slf4j;

/**
 * Abstract base for MQTT3 and MQTT5 broker callbacks.
 *
 * <p>Implements the full QoS-aware ACK / reconnect / poison-pill logic in a single place.
 * Subclasses supply only the four protocol-specific operations:
 * <ol>
 *   <li>{@link #extractTopic(Object)} — decode the topic string from the message object</li>
 *   <li>{@link #extractPayload(Object)} — copy the payload bytes out of the message object</li>
 *   <li>{@link #extractQos(Object)} — return the message QoS as an int (0, 1 or 2)</li>
 *   <li>{@link #buildConnectorMessage(Object, String, byte[])} — assemble the {@link ConnectorMessage}</li>
 *   <li>{@link #acknowledgeMessage(Object)} — call the protocol's acknowledge / manual-ack method</li>
 * </ol>
 *
 * @param <M> the protocol-specific publish message type (e.g. {@code Mqtt3Publish} or {@code Mqtt5Publish})
 */
@Slf4j
public abstract class AbstractMqttCallback<M> implements Consumer<M> {

    static final String TOPIC_LEVEL_SEPARATOR = String.valueOf(MqttTopic.TOPIC_LEVEL_SEPARATOR);

    /** Minimum interval between two reconnect triggers to prevent reconnect storms. */
    private static final long MIN_RECONNECT_INTERVAL_MS = 60_000L;

    /** Upper bound on the per-retransmission timeout escalation, as a multiple of the base budget. */
    private static final int MAX_TIMEOUT_ESCALATION_FACTOR = 3;

    protected final GenericMessageCallback genericMessageCallback;
    protected final String tenant;
    protected final String connectorIdentifier;
    protected final String connectorName;
    protected final ServiceConfiguration serviceConfiguration;
    protected final ExecutorService virtualThreadPool;

    /** Whether to trigger a broker reconnect when a message cannot be processed. */
    protected final boolean reconnectOnProcessingError;

    /** Disconnects the connector so the broker retransmits unACKed messages. */
    protected final Runnable reconnectTrigger;

    /** Timestamp of the last triggered reconnect — used for rate-limiting. */
    protected final AtomicLong lastReconnectTriggerMs = new AtomicLong(0);

    /** Ensures only one thread executes {@link #reconnectTrigger} at a time. */
    protected final Semaphore reconnectExecutionSemaphore = new Semaphore(1);

    /**
     * Per-message failure counter (topic + payload hash as key). After {@code maxProcessingAttempts}
     * failures the message is treated as a poison pill and ACKed to break an infinite reconnect loop.
     */
    protected final MessageFailureTracker failureTracker;

    /** Number of messages currently being processed; used to delay reconnect until ACKs are sent. */
    protected final AtomicInteger activeProcessingMessages = new AtomicInteger(0);

    protected AbstractMqttCallback(
            String tenant,
            ServiceRegistry serviceRegistry,
            GenericMessageCallback callback,
            String connectorIdentifier,
            String connectorName,
            Runnable reconnectTrigger) {
        this.genericMessageCallback = callback;
        this.tenant = tenant;
        this.connectorIdentifier = connectorIdentifier;
        this.connectorName = connectorName;
        this.serviceConfiguration = serviceRegistry.getServiceConfiguration(tenant);
        this.virtualThreadPool = serviceRegistry.getVirtualThreadPool();
        this.reconnectTrigger = reconnectTrigger;
        this.failureTracker = MessageFailureTracker.forConfiguration(this.serviceConfiguration);

        boolean reconnect = true;
        try {
            ConnectorConfiguration configuration = serviceRegistry
                    .getConnectorConfigurationService()
                    .getConnectorConfiguration(connectorIdentifier, tenant);
            if (configuration != null && configuration.getProperties() != null) {
                reconnect = (Boolean) configuration.getProperties()
                        .getOrDefault("reconnectOnProcessingError", true);
            }
        } catch (Exception e) {
            log.warn("{} - Failed to load reconnectOnProcessingError for connector {}, using default (true): {}",
                    tenant, connectorIdentifier, e.getMessage());
        }
        this.reconnectOnProcessingError = reconnect;
    }

    // -------------------------------------------------------------------------
    // Protocol-specific hooks — implemented by MQTT3Callback / MQTT5Callback
    // -------------------------------------------------------------------------

    /** Decode the topic string from the protocol message. */
    protected abstract String extractTopic(M message);

    /** Copy the raw payload bytes out of the protocol message (may return null). */
    protected abstract byte[] extractPayload(M message);

    /** Return the message QoS as an int (0 = AT_MOST_ONCE, 1 = AT_LEAST_ONCE, 2 = EXACTLY_ONCE). */
    protected abstract int extractQos(M message);

    /**
     * Assemble the {@link ConnectorMessage} for the generic processing pipeline.
     * MQTT5 can include {@code clientId} from user properties; MQTT3 leaves it null.
     */
    protected abstract ConnectorMessage buildConnectorMessage(M message, String topic, byte[] payload);

    /** Call the protocol's acknowledge / manual-ack API on the message. */
    protected abstract void acknowledgeMessage(M message);

    // -------------------------------------------------------------------------
    // Shared implementation
    // -------------------------------------------------------------------------

    /**
     * Generates a stable identifier for a message from its topic and payload.
     * Used to track per-message failure counts across redeliveries.
     */
    protected String getMessageId(String topic, byte[] payload) {
        int hash = 31 * topic.hashCode() + (payload != null ? Arrays.hashCode(payload) : 0);
        return topic + "#" + Integer.toHexString(hash);
    }

    @Override
    public void accept(M mqttMessage) {
        try (TenantLogContext ignored = TenantLogContext.open(tenant)) {
            String topic = extractTopic(mqttMessage);
            byte[] payloadBytes = extractPayload(mqttMessage);
            String messageId = getMessageId(topic, payloadBytes);
            ConnectorMessage connectorMessage = buildConnectorMessage(mqttMessage, topic, payloadBytes);

            if (serviceConfiguration.getLogPayload()) {
                log.info("{} - INITIAL: message on topic: [{}], QoS message: {}, connector: {},{}",
                        tenant, topic, extractQos(mqttMessage), connectorName, connectorIdentifier);
            }

            ProcessingResultWrapper<?> processedResults = genericMessageCallback.onMessage(connectorMessage);
            int publishQos = extractQos(mqttMessage);
            int mappingQos = processedResults.getConsolidatedQos().getLevel();
            int timeout = processedResults.getPipelineTimeoutMS();
            // The guarantee we can actually provide is bounded by both ends: the publisher's QoS
            // (a QoS 0 message is gone the moment it is delivered, no matter what the mapping asks
            // for) and the strongest QoS among the mappings that matched.
            int effectiveQos = Math.min(publishQos, mappingQos);

            if (serviceConfiguration.getLogPayload()) {
                log.info(
                        "{} - PREPARING_RESULTS: message on topic: [{}], QoS message: {}, QoS effective: {}, QoS mappings: {}, connector {}",
                        tenant, topic, extractQos(mqttMessage), effectiveQos, mappingQos, connectorIdentifier);
            }

            if (effectiveQos > 0) {
                activeProcessingMessages.incrementAndGet();
                virtualThreadPool.submit(TenantLogContext.wrap(tenant, () -> {
                    try {
                        // Give a retransmission more time than the first attempt: a timeout can
                        // be caused by a transiently slow platform, not by a broken mapping.
                        // Capped at MAX_TIMEOUT_ESCALATION_FACTOR x the base budget.
                        Long timeoutOverride = null;
                        if (timeout > 0) {
                            int attempt = failureTracker.failures(messageId);
                            timeoutOverride = Math.min(
                                    (long) timeout * (attempt + 1),
                                    (long) timeout * MAX_TIMEOUT_ESCALATION_FACTOR);
                            if (attempt > 0) {
                                log.info(
                                        "{} - Retransmission attempt {}: using increased timeout {}ms (base: {}ms), connector: {}",
                                        tenant, attempt + 1, timeoutOverride, timeout, connectorIdentifier);
                            }
                        }

                        AckOutcome outcome = ProcessingResultHelper.awaitOutcome(
                                processedResults, tenant, topic, log, timeoutOverride);
                        if (outcome == AckOutcome.ACK) {
                            failureTracker.clear(messageId);
                            log.warn("{} - END: Sending manual ack for MQTT message: topic: [{}], QoS: {}, connector: {}",
                                    tenant, topic, extractQos(mqttMessage), connectorIdentifier);
                            acknowledgeMessage(mqttMessage);
                        } else {
                            // Not ACKed: let the broker retransmit (after a reconnect if configured)
                            triggerReconnectOrAck(mqttMessage, topic, messageId);
                        }
                    } finally {
                        activeProcessingMessages.decrementAndGet();
                    }
                    return null; // Callable<Void>
                }));
            } else {
                // QoS 0 (or downgraded to 0): acknowledge immediately, no reliability guarantee
                if (serviceConfiguration.getLogPayload()) {
                    log.info("{} - END: Sending manual ack for MQTT message: topic: [{}], QoS: {}, connector: {}",
                            tenant, topic, extractQos(mqttMessage), connectorIdentifier);
                }
                acknowledgeMessage(mqttMessage);
            }
        }
    }

    /**
     * Circuit-breaker-aware reconnect handler.
     * <ul>
     *   <li>Records the failure. If the per-message counter reaches {@code maxProcessingAttempts}, the message is
     *       treated as a poison pill: it is ACKed (discarded) and a hard error is logged, whatever the setting
     *       below.</li>
     *   <li>If {@code reconnectOnProcessingError} is disabled, returns (broker will retransmit).</li>
     *   <li>Otherwise {@link #triggerReconnect(String)} schedules an exponentially backed-off disconnect.</li>
     * </ul>
     */
    protected void triggerReconnectOrAck(M mqttMessage, String topic, String messageId) {
        if (failureTracker.recordFailureReachedLimit(messageId)) {
            log.error(
                    "{} - POISON PILL: {} failed attempts without success: ACKing message to prevent "
                            + "infinite retransmission. Message is discarded. topic: [{}], connector: {}",
                    tenant, failureTracker.limit(), topic, connectorIdentifier);
            acknowledgeMessage(mqttMessage);
            return;
        }
        if (!reconnectOnProcessingError) {
            log.info(
                    "{} - END: reconnectOnProcessingError is disabled, waiting for broker to retransmit unACKed message. connector: {}",
                    tenant, connectorIdentifier);
            return;
        }
        triggerReconnect(messageId);
    }

    /**
     * Schedules a connector disconnect with exponential back-off delay.
     *
     * <p>Back-off schedule (base = {@value #MIN_RECONNECT_INTERVAL_MS} ms):
     * <ul>
     *   <li>Attempt 1: immediate (0 ms)</li>
     *   <li>Attempt 2: 1 × base</li>
     *   <li>Attempt N: (N-1) × base, capped at 5 × base</li>
     * </ul>
     *
     * The semaphore ensures only one thread executes the reconnect trigger at a time;
     * the back-off sleep happens before acquiring it so other messages can still be ACKed.
     */
    private void triggerReconnect(String messageId) {
        int attempt = Math.max(1, failureTracker.failures(messageId));
        lastReconnectTriggerMs.set(System.currentTimeMillis());

        long delayMs = attempt <= 1 ? 0L
                : Math.min((long) (attempt - 1) * MIN_RECONNECT_INTERVAL_MS,
                        5 * MIN_RECONNECT_INTERVAL_MS);

        log.warn("{} - Scheduling reconnect attempt {} with {}ms back-off delay, connector: {}",
                tenant, attempt, delayMs, connectorIdentifier);

        Thread.ofVirtual()
                .name("mqtt-timeout-reconnect-" + connectorIdentifier)
                .start(() -> {
                    try {
                        // Wait for in-flight messages to finish before reconnecting
                        while (activeProcessingMessages.get() > 1) {
                            Thread.sleep(100); //NOSONAR intentional wait
                        }
                        if (delayMs > 0) {
                            Thread.sleep(delayMs); //NOSONAR intentional back-off delay
                        }
                        try {
                            reconnectExecutionSemaphore.acquire();
                            log.debug(
                                    "{} - Reconnect attempt {} executing reconnect trigger, connector: {}",
                                    tenant, attempt, connectorIdentifier);
                            reconnectTrigger.run();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            log.warn("{} - Reconnect thread interrupted waiting for semaphore, connector: {}",
                                    tenant, connectorIdentifier);
                        } finally {
                            reconnectExecutionSemaphore.release();
                            log.debug("{} - Reconnect attempt {} released semaphore, connector: {}",
                                    tenant, attempt, connectorIdentifier);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("{} - Reconnect thread interrupted, connector: {}",
                                tenant, connectorIdentifier);
                    }
                });
    }
}
