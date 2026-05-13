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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
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

    /** Maximum time a mapping can use CPU-time to process end to end. Only used in error cases,
     *  otherwise configured timeout is used
     */
    private static final int MAX_PROCESSING_TIMEOUT = 30000;

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
     * Semaphore that ensures only one thread executes reconnectTrigger.run() at a time.
     * This allows other messages to be processed during the back-off sleep phase.
     */
    private final Semaphore reconnectExecutionSemaphore = new Semaphore(1);
    /**
     * Counts consecutive failures (timeout / HTTP≥500) per message (identified by topic+payload hash).
     * Each message is tracked independently. When a message's counter exceeds
     * {@link #MAX_CONSECUTIVE_RECONNECTS} it is treated as a poison pill and ACKed
     * to break an infinite reconnect loop.
     */
    private final Map<String, AtomicInteger> failureCountPerMessage = new ConcurrentHashMap<>();
    /**
     * Counts the number of messages currently being processed in the virtualThreadPool.
     * Used to avoid triggering a reconnect while other messages are still being processed,
     * as the reconnect would delay their ACKs.
     */
    private final java.util.concurrent.atomic.AtomicInteger activeProcessingMessages =
            new java.util.concurrent.atomic.AtomicInteger(0);

    /**
     * Generates a unique identifier for a message based on topic and payload.
     * This allows per-message failure tracking during redelivery.
     */
    private String getMessageId(String topic, byte[] payload) {
        // Use topic + payload hash as unique message identifier
        int hash = 31 * topic.hashCode() + (payload != null ? java.util.Arrays.hashCode(payload) : 0);
        return topic + "#" + Integer.toHexString(hash);
    }

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
        String messageId = getMessageId(topic, payloadBytes);

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
                long effectiveTimeout = timeout;
                try {
                    // Wait for the future to complete.
                    // Multiply timeout by (attempt + 1) so each retransmission gets progressively
                    // more processing time — useful when the first timeout was caused by a slow server.
                    // attempt = current failure count for this message:
                    //   0 → first try   → 1× timeout
                    //   1 → 2nd try     → 2× timeout
                    //   …  capped at MAX_CONSECUTIVE_RECONNECTS × timeout
                    List<? extends ProcessingContext<?>> results;
                    if (timeout > 0) {
                        int attempt = failureCountPerMessage.getOrDefault(messageId, new AtomicInteger(0)).get();
                        //Effective Timeout is capped at max 30s for processing
                        effectiveTimeout = Math.min(
                                (long) timeout * (attempt + 1),
                                MAX_PROCESSING_TIMEOUT);
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
                        // No errors found, acknowledge the message and reset failure counter for this message
                        failureCountPerMessage.remove(messageId);
                        log.warn(
                                "{} - END: Sending manual ack for MQTT message: topic: [{}], QoS: {}, connector: {}",
                                tenant, mqttMessage.getTopic(), mqttMessage.getQos().ordinal(), connectorIdentifier);
                        mqttMessage.acknowledge();
                    } else if (httpStatusCode < 500) {
                        // Errors found but not a server error, acknowledge the message
                        failureCountPerMessage.remove(messageId); // client-side error is not a transient failure
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
                        triggerReconnectOrAck(mqttMessage, topic, messageId);
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
                    log.warn("{} - Timeout occurred, initiating cancellation of processing task", tenant);
                    var cancelResult = processedResults.cancelProcessing();
                    log.info("{} - Cancellation result: future was cancelled={}", tenant, cancelResult);

                    // Give the cancellation a brief moment to take effect (e.g., interrupt flag propagation,
                    // GraalVM context closure). This helps ensure that the processing task actually stops
                    // rather than continuing to run after the timeout.
                    try {
                        Thread.sleep(50); //NOSONAR intentional wait for cancellation to take effect
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }

                    // If the future couldn't be cancelled but cancellation was requested,
                    // the task is likely already running. Log this for diagnostics.
                    if (!cancelResult && processedResults.getCancellationRequested().get()) {
                        log.warn("{} - Future was already running when cancellation was requested. Waiting for it to complete or be interrupted.", tenant);
                    }

                    log.warn(
                            "{} - END: Processing timed out after {} ms, not sending ACK. Triggering reconnect for retransmission. connector: {}, cancel result: {}",
                            tenant, effectiveTimeout, connectorIdentifier, cancelResult);
                    triggerReconnectOrAck(mqttMessage, topic, messageId);
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
     * Tracks failures per message (by messageId) to prevent infinite reconnect loops.
     * <ul>
     *   <li>If reconnectOnProcessingError is disabled, the message is acknowledged immediately
     *       regardless of the error condition.</li>
     *   <li>If the failure counter for this message has reached
     *       {@link #MAX_CONSECUTIVE_RECONNECTS}: the message is treated as a
     *       <em>poison pill</em> — it is ACKed (discarded) and a hard error is logged.</li>
      *   <li>Otherwise {@link #triggerReconnect(String)} is called, which increments the
      *       counter for this message and schedules a delayed disconnect.</li>
     * </ul>
     *
     * @param mqttMessage the unacknowledged MQTT message
     * @param topic       human-readable topic string for logging
     * @param messageId   unique identifier for tracking failures per message
     */
    private void triggerReconnectOrAck(Mqtt5Publish mqttMessage, String topic, String messageId) {
        // If reconnectOnProcessingError is disabled, wait for broker to re-transmit un-acked message again
        if (!reconnectOnProcessingError) {
            log.info(
                    "{} - END: reconnectOnProcessingError is disabled, waiting for Broker to re-transmit unacked message automatically. connector: {}",
                    tenant, connectorIdentifier);
            return;
        }

        // Check poison-pill threshold BEFORE scheduling another reconnect attempt.
        int failureCount = failureCountPerMessage.getOrDefault(messageId, new AtomicInteger(0)).get();
        if (failureCount >= MAX_CONSECUTIVE_RECONNECTS) {
            log.error("{} - POISON PILL: {} consecutive failures without success — ACKing message to prevent "
                    + "infinite reconnect loop. Message is discarded. topic: [{}], connector: {}",
                    tenant, failureCount, topic, connectorIdentifier);
            failureCountPerMessage.remove(messageId);
            mqttMessage.acknowledge();
            return;
        }
        triggerReconnect(messageId);
    }

    /**
     * Schedules a connector disconnect with exponential back-off delay.
     * <p>
     * The semaphore ensures that only one thread executes the reconnect trigger at a time.
     * However, the back-off delay happens BEFORE acquiring the semaphore, allowing other
     * messages to be processed during this sleep phase. This improves throughput when the
     * broker is recovering.
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
     * The failure counter is incremented per message (by messageId) so each message
     * is counted independently.
     *
     * @param messageId unique identifier for tracking failures per message
     */
    private void triggerReconnect(String messageId) {
        int attempt = failureCountPerMessage
            .computeIfAbsent(messageId, k -> new AtomicInteger(0))
            .incrementAndGet();
        lastReconnectTriggerMs.set(System.currentTimeMillis());

        // Exponential back-off: 0 ms for the first attempt, then (attempt-1) × base, capped at 5 × base.
        long delayMs = attempt <= 1 ? 0L
                : Math.min((long) (attempt - 1) * MIN_RECONNECT_INTERVAL_MS, 5 * MIN_RECONNECT_INTERVAL_MS);

        log.warn("{} - Scheduling reconnect attempt {} with {}ms back-off delay, connector: {}",
                tenant, attempt, delayMs, connectorIdentifier);

        // Run in a dedicated virtual thread so it doesn't block the callback thread.
        // The back-off sleep happens BEFORE acquiring the semaphore, allowing other
        // messages to proceed without waiting for this reconnect to complete.
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

                        // Only one thread can execute reconnectTrigger.run() at a time.
                        // Acquire the semaphore before executing the reconnect.
                        try {
                            reconnectExecutionSemaphore.acquire();
                            log.debug("{} - Reconnect attempt {} acquiring execution semaphore and executing reconnect trigger, connector: {}",
                                    tenant, attempt, connectorIdentifier);
                            reconnectTrigger.run();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            log.warn("{} - Reconnect thread interrupted while waiting for semaphore, connector: {}", tenant, connectorIdentifier);
                        } finally {
                            reconnectExecutionSemaphore.release();
                            log.debug("{} - Reconnect attempt {} completed and released execution semaphore, connector: {}",
                                    tenant, attempt, connectorIdentifier);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("{} - Reconnect thread interrupted, connector: {}", tenant, connectorIdentifier);
                    }
                });
    }

}