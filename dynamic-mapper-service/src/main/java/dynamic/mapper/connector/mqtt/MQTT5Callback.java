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

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import com.cumulocity.sdk.client.SDKException;
import com.hivemq.client.mqtt.datatypes.MqttTopic;
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish;

import dynamic.mapper.configuration.ConnectorConfiguration;
import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.connector.core.callback.GenericMessageCallback;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.ProcessingResultWrapper;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MQTT5Callback implements Consumer<Mqtt5Publish> {
    static String TOPIC_LEVEL_SEPARATOR = String.valueOf(MqttTopic.TOPIC_LEVEL_SEPARATOR);

    /**
     * Minimum time between two reconnect triggers (milliseconds).
     * Prevents a reconnect storm when the server is persistently unavailable.
     */
    private static final long MIN_RECONNECT_INTERVAL_MS = 60_000L;

    /**
     * Maximum number of consecutive failures (timeout or server error) before treating
     * the message as a poison pill and ACKing it to break the infinite reconnect loop.
     * After this threshold, the message is acknowledged (discarded) and a hard error is logged.
     * The counter is reset to zero on every successful message processing.
     */
    private static final int MAX_CONSECUTIVE_RECONNECTS = 5;

    private GenericMessageCallback genericMessageCallback;
    private String tenant;
    private String connectorIdentifier;
    private String connectorName;
    private ServiceConfiguration serviceConfiguration;
    private ExecutorService virtualThreadPool;
    private ConfigurationRegistry configurationRegistry;
    /** Flag to control whether reconnect should be triggered on processing errors */
    private boolean reconnectOnProcessingError = true;
    /** Callback that disconnects the connector so the broker retransmits unACKed messages. */
    private final Runnable reconnectTrigger;
    /** Timestamp of the last triggered reconnect — used for rate-limiting. */
    private final AtomicLong lastReconnectTriggerMs = new AtomicLong(0);
    /**
     * Guards against parallel reconnect execution. Set to {@code true} when a reconnect is
     * in progress; reset to {@code false} after the reconnect trigger has completed.
     * Combined with the rate-limit this ensures at most one reconnect runs at a time.
     */
    private final java.util.concurrent.atomic.AtomicBoolean reconnectInProgress =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /**
     * Counts consecutive failures (timeout / HTTP≥500) without a successful processing in between.
     * When it exceeds {@link #MAX_CONSECUTIVE_RECONNECTS} the message is treated as a poison pill
     * and ACKed to break an infinite reconnect loop.
     */
    private final java.util.concurrent.atomic.AtomicInteger consecutiveReconnectCount =
            new java.util.concurrent.atomic.AtomicInteger(0);
    /**
     * Counts the number of messages currently being processed in the virtualThreadPool.
     * Used to avoid triggering a reconnect while other messages are still being processed,
     * as the reconnect would delay their ACKs.
     */
    private final java.util.concurrent.atomic.AtomicInteger activeProcessingMessages =
            new java.util.concurrent.atomic.AtomicInteger(0);

    MQTT5Callback(String tenant, ConfigurationRegistry configurationRegistry, GenericMessageCallback callback,
            String connectorIdentifier, String connectorName, Runnable reconnectTrigger) {
        this.genericMessageCallback = callback;
        this.tenant = tenant;
        this.connectorIdentifier = connectorIdentifier;
        this.connectorName = connectorName;
        this.serviceConfiguration = configurationRegistry.getServiceConfiguration(tenant);
        this.virtualThreadPool = configurationRegistry.getVirtualThreadPool();
        this.configurationRegistry = configurationRegistry;
        this.reconnectTrigger = reconnectTrigger;

        // Load the reconnectOnProcessingError flag from connector configuration
        try {
            ConnectorConfiguration configuration = configurationRegistry.getConnectorConfigurationService()
                    .getConnectorConfiguration(connectorIdentifier, tenant);
            if (configuration != null && configuration.getProperties() != null) {
                this.reconnectOnProcessingError = (Boolean) configuration.getProperties()
                        .getOrDefault("reconnectOnProcessingError", true);
            }
        } catch (Exception e) {
            log.warn("{} - Failed to load reconnectOnProcessingError flag for connector {}, using default (true): {}",
                    tenant, connectorIdentifier, e.getMessage());
            this.reconnectOnProcessingError = true;
        }
    }

    @Override
    public void accept(Mqtt5Publish mqttMessage) {
        String topic = String.join(TOPIC_LEVEL_SEPARATOR, mqttMessage.getTopic().getLevels());
        byte[] payloadBytes = mqttMessage.getPayload()
                .map(byteBuffer -> {
                    byte[] bytes = new byte[byteBuffer.remaining()];
                    byteBuffer.get(bytes);
                    return bytes;
                })
                .orElse(null);

        // Extract client ID from MQTT 5 user properties
        String publisherClientId = mqttMessage.getUserProperties().asList().stream()
                .filter(property -> "clientId".equals(property.getName().toString()))
                .map(property -> property.getValue().toString())
                .findFirst()
                .orElse(null);

        if (publisherClientId != null && serviceConfiguration.getLogPayload()) {
            log.info("{} - Publisher client ID from user properties: {}", tenant, publisherClientId);
        }

        ConnectorMessage connectorMessage = ConnectorMessage.builder()
                .tenant(tenant)
                .topic(topic)
                .clientId(publisherClientId)
                .sendPayload(true)
                .connectorIdentifier(connectorIdentifier)
                .payload(payloadBytes)
                .build();
        if (serviceConfiguration.getLogPayload()) {
            log.info(
                    "{} - INITIAL: message on topic: [{}], QoS message: {}, connector: {},{}",
                    tenant, mqttMessage.getTopic(), mqttMessage.getQos().ordinal(),
                    connectorName, connectorIdentifier);
        }
        // Process the message
        ProcessingResultWrapper<?> processedResults = genericMessageCallback.onMessage(connectorMessage);
        // Determine downgraded QoS as the minimum of QoS in the message and the
        // consolidated QoS of the mappings
        int publishQos = mqttMessage.getQos().getCode();
        int mappingQos = processedResults.getConsolidatedQos().ordinal();
        int timeout = processedResults.getMaxCPUTimeMS();
        int effectiveQos = Math.min(publishQos, mappingQos);
        if (serviceConfiguration.getLogPayload()) {
            log.info(
                    "{} - PREPARING_RESULTS: message on topic: [{}], QoS message: {}, QoS effective: {}, QoS mappings: {}, connector {}",
                    tenant, mqttMessage.getTopic(), mqttMessage.getQos().ordinal(), effectiveQos, mappingQos,
                    connectorIdentifier);
        }
        if (effectiveQos > 0) {
            activeProcessingMessages.incrementAndGet();
            // Use the provided virtualThreadPool instead of creating a new thread
            virtualThreadPool.submit(() -> {
                try {
                    // Wait for the future to complete.
                    // Multiply timeout by (attempt + 1) so each retransmission gets progressively
                    // more processing time — useful when the first timeout was caused by a slow server.
                    // attempt = consecutiveReconnectCount at the moment this message is being processed:
                    //   0 → first try   → 1× timeout
                    //   1 → 2nd try     → 2× timeout
                    //   …  capped at MAX_CONSECUTIVE_RECONNECTS × timeout
                    List<? extends ProcessingContext<?>> results;
                    if (timeout > 0) {
                        int attempt = consecutiveReconnectCount.get(); // 0-based; 0 = first attempt
                        long effectiveTimeout = Math.min(
                                (long) timeout * (attempt + 1),
                                (long) timeout * MAX_CONSECUTIVE_RECONNECTS);
                        if (attempt > 0) {
                            log.info("{} - Retransmission attempt {}: using increased timeout {}ms (base: {}ms), connector: {}",
                                    tenant, attempt + 1, effectiveTimeout, timeout, connectorIdentifier);
                        }
                        results = processedResults.getProcessingResult().get(effectiveTimeout, TimeUnit.MILLISECONDS);
                    } else {
                        results = processedResults.getProcessingResult().get();
                    }

                    // Check for errors in results
                    boolean hasErrors = false;
                    int httpStatusCode = 0;
                    if (results != null) {
                        for (ProcessingContext<?> context : results) {
                            if (context.hasError()) {
                                for (Exception error : context.getErrors()) {
                                    if (error instanceof ProcessingException) {
                                        if (((ProcessingException) error)
                                                .getOriginException() instanceof SDKException) {
                                            if (((SDKException) ((ProcessingException) error).getOriginException())
                                                    .getHttpStatus() > httpStatusCode) {
                                                httpStatusCode = ((SDKException) ((ProcessingException) error)
                                                        .getOriginException()).getHttpStatus();
                                            }
                                        }
                                    }
                                }
                                hasErrors = true;
                                log.error(
                                        "{} - Error in processing context for topic: [{}]",
                                        tenant, topic);
                                break;
                            }
                        }
                    }

                    if (!hasErrors) {
                        // No errors found, acknowledge the message
                        consecutiveReconnectCount.set(0); // reset circuit breaker on success
                        log.warn(
                                "{} - END: Sending manual ack for MQTT message: topic: [{}], QoS: {}, connector: {}",
                                tenant, mqttMessage.getTopic(), mqttMessage.getQos().ordinal(), connectorIdentifier);
                        mqttMessage.acknowledge();
                    } else if (httpStatusCode < 500) {
                        // Errors found but not a server error, acknowledge the message
                        consecutiveReconnectCount.set(0); // client-side error is not a transient failure
                        log.warn(
                                "{} - END: Sending manual ack due to non-Server error for MQTT message: topic: [{}], QoS: {}, connector: {}",
                                tenant, mqttMessage.getTopic(), mqttMessage.getQos().ordinal(), connectorIdentifier);
                        mqttMessage.acknowledge();
                    } else {
                        // Server error (>=500): do not ACK — trigger reconnect so the broker
                        // retransmits unACKed messages after the session is restored.
                        log.warn(
                                "{} - END: Server error (HTTP {}), not sending ACK. Triggering reconnect for retransmission. topic: [{}], connector: {}",
                                tenant, httpStatusCode, mqttMessage.getTopic(), connectorIdentifier);
                        triggerReconnectOrAck(mqttMessage, topic);
                    }
                } catch (InterruptedException | ExecutionException e) {
                    // Processing failed, don't acknowledge to allow redelivery
                    // Thread.currentThread().interrupt();
                    log.warn("{} - END: Was interrupted for MQTT message: topic: [{}], QoS: {}, connector: {}",
                            tenant, mqttMessage.getTopic(), mqttMessage.getQos().ordinal(), connectorIdentifier);
                } catch (TimeoutException e) {
                    // cancel(true) interrupts threads blocked in IO (C8Y SDK HTTP calls).
                    // cancelProcessing() additionally closes any active GraalVM context via
                    // Context.close(cancelIfExecuting=true) — the only reliable way to stop
                    // CPU-bound JS execution that ignores Java thread interruption.
                    var cancelResult = processedResults.cancelProcessing();
                    log.warn(
                            "{} - END: Processing timed out after {} ms, not sending ACK. Triggering reconnect for retransmission. connector: {}, cancel result: {}",
                            tenant, timeout, connectorIdentifier, cancelResult);
                    triggerReconnectOrAck(mqttMessage, topic);
                } finally {
                    activeProcessingMessages.decrementAndGet();
                }
                return null; // Proper return for Callable<Void>
            });
        } else {
            // For QoS 0 (or downgraded to 0), no need for special handling

            // Acknowledge message with QoS=0
            if (serviceConfiguration.getLogPayload()) {
                log.info("{} - END: Sending manual ack for MQTT message: topic: [{}], QoS: {}, connector: {}",
                        tenant, mqttMessage.getTopic(), mqttMessage.getQos().ordinal(), connectorIdentifier);
            }
            mqttMessage.acknowledge();

        }
    }

    /**
     * Circuit-breaker-aware reconnect handler.
     * <ul>
     *   <li>If reconnectOnProcessingError is disabled, the message is acknowledged immediately
     *       regardless of the error condition.</li>
     *   <li>If a reconnect is already in progress (including its back-off delay), the
     *       message stays unACKed — the broker will retransmit it after the session is
     *       restored, so no further action is required.</li>
     *   <li>If the consecutive-reconnect counter has reached
     *       {@link #MAX_CONSECUTIVE_RECONNECTS}: the message is treated as a
     *       <em>poison pill</em> — it is ACKed (discarded) and a hard error is logged.
     *       This breaks an infinite reconnect loop caused by a permanently unprocessable
     *       message.</li>
     *   <li>Otherwise {@link #triggerReconnect()} is called, which increments the
     *       counter and schedules a delayed disconnect.</li>
     * </ul>
     *
     * @param mqttMessage the unacknowledged MQTT message
     * @param topic       human-readable topic string for logging
     */
    private void triggerReconnectOrAck(Mqtt5Publish mqttMessage, String topic) {
        log.info("reconnectProcessingError={}", reconnectOnProcessingError);
        // If reconnectOnProcessingError is disabled, wait for broker to re-transmit un-acked message again
        if (!reconnectOnProcessingError) {
            log.info(
                    "{} - END: reconnectOnProcessingError is disabled, waiting for Broker to re-transmit unacked message automatically. connector: {}",
                    tenant, connectorIdentifier);
            return;
        }

        // If a reconnect is already scheduled/running, this message stays unACKed.
        // The broker will retransmit it after the session is restored.
        if (reconnectInProgress.get()) {
            log.debug("{} - Reconnect already in progress — message will be retransmitted after reconnect. topic: [{}], connector: {}",
                    tenant, topic, connectorIdentifier);
            return;
        }
        // Check poison-pill threshold BEFORE scheduling another reconnect attempt.
        if (consecutiveReconnectCount.get() >= MAX_CONSECUTIVE_RECONNECTS) {
            log.error("{} - POISON PILL: {} consecutive reconnect attempts without success — ACKing message to prevent "
                    + "infinite reconnect loop. Message is discarded. topic: [{}], connector: {}",
                    tenant, consecutiveReconnectCount.get(), topic, connectorIdentifier);
            consecutiveReconnectCount.set(0);
            mqttMessage.acknowledge();
            return;
        }
        triggerReconnect();
    }

    /**
     * Schedules a connector disconnect with exponential back-off delay.
     * <p>
     * The {@link #reconnectInProgress} flag is held for the entire duration — including
     * the sleep before the disconnect — so that concurrent callers in
     * {@link #triggerReconnectOrAck} see "reconnect in progress" and leave their
     * messages unACKed for broker retransmission.
     * <p>
     * If other messages are being processed, this thread waits for them to complete
     * (and be ACKed) before proceeding with the reconnect. This avoids blocking their
     * ACKs unnecessarily.
     * <p>
     * Back-off schedule (base = {@value #MIN_RECONNECT_INTERVAL_MS} ms):
     * <ul>
     *   <li>Attempt 1: immediate (0 ms)</li>
     *   <li>Attempt 2: {@value #MIN_RECONNECT_INTERVAL_MS} ms</li>
     *   <li>Attempt 3: 2 × {@value #MIN_RECONNECT_INTERVAL_MS} ms</li>
     *   <li>… capped at 5 × {@value #MIN_RECONNECT_INTERVAL_MS} ms</li>
     * </ul>
     * The consecutive-reconnect counter is incremented here (not in the caller) so it
     * accurately reflects actual reconnect attempts, not the number of failing messages.
     */
    private void triggerReconnect() {
        // Parallel-execution guard — also prevents a second reconnect from being queued
        // while the first one is still sleeping through its back-off delay.
        if (!reconnectInProgress.compareAndSet(false, true)) {
            log.warn("{} - Reconnect already in progress, skipping parallel trigger, connector: {}",
                    tenant, connectorIdentifier);
            return;
        }

        int attempt = consecutiveReconnectCount.incrementAndGet();
        lastReconnectTriggerMs.set(System.currentTimeMillis());

        // Exponential back-off: 0 ms for the first attempt, then (attempt-1) × base, capped at 5 × base.
        long delayMs = attempt <= 1 ? 0L
                : Math.min((long) (attempt - 1) * MIN_RECONNECT_INTERVAL_MS, 5 * MIN_RECONNECT_INTERVAL_MS);

        log.warn("{} - Scheduling reconnect attempt {} with {}ms back-off delay, connector: {}",
                tenant, attempt, delayMs, connectorIdentifier);

        // Run in a dedicated virtual thread so it doesn't block the callback thread.
        // reconnectInProgress remains true during the sleep AND the disconnect call,
        // ensuring no second reconnect can start until this one fully completes.
        Thread.ofVirtual()
                .name("mqtt-timeout-reconnect-" + connectorIdentifier)
                .start(() -> {
                    try {
                        // Wait for other messages to finish processing and be ACKed.
                        // This prevents the reconnect from blocking their ACK operations.
                        while (activeProcessingMessages.get() > 1) {
                            Thread.sleep(100); //NOSONAR intentional wait for other messages
                        }
                        if (delayMs > 0) {
                            Thread.sleep(delayMs); //NOSONAR intentional back-off delay
                        }
                        reconnectTrigger.run();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("{} - Reconnect thread interrupted, connector: {}", tenant, connectorIdentifier);
                    } finally {
                        reconnectInProgress.set(false);
                    }
                });
    }

}