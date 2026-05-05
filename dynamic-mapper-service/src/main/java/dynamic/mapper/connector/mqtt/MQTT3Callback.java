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
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.connector.core.callback.GenericMessageCallback;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.ProcessingResultWrapper;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MQTT3Callback implements Consumer<Mqtt3Publish> {
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
    private String clientId;
    private ServiceConfiguration serviceConfiguration;
    private ExecutorService virtualThreadPool;
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

    MQTT3Callback(String tenant, ConfigurationRegistry configurationRegistry, GenericMessageCallback callback,
            String connectorIdentifier, String connectorName, String clientId, Runnable reconnectTrigger) {
        this.genericMessageCallback = callback;
        this.tenant = tenant;
        this.connectorIdentifier = connectorIdentifier;
        this.connectorName = connectorName;
        this.clientId = clientId;
        this.serviceConfiguration = configurationRegistry.getServiceConfiguration(tenant);
        this.virtualThreadPool = configurationRegistry.getVirtualThreadPool();
        this.reconnectTrigger = reconnectTrigger;
    }

    @Override
    public void accept(Mqtt3Publish mqttMessage) {
        String topic = String.join(TOPIC_LEVEL_SEPARATOR, mqttMessage.getTopic().getLevels());
        byte[] payloadBytes = mqttMessage.getPayload()
                .map(byteBuffer -> {
                    byte[] bytes = new byte[byteBuffer.remaining()];
                    byteBuffer.get(bytes);
                    return bytes;
                })
                .orElse(null);
        ConnectorMessage connectorMessage = ConnectorMessage.builder()
                .tenant(tenant)
                .topic(topic)
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
            // Use the provided virtualThreadPool instead of creating a new thread
            virtualThreadPool.submit(() -> {
                try {
                    // Wait for the future to complete
                    List<? extends ProcessingContext<?>> results;
                    if (timeout > 0) {
                        results = processedResults.getProcessingResult().get(timeout,
                                TimeUnit.MILLISECONDS);
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
                    var cancelResult = processedResults.getProcessingResult().cancel(true);
                    log.warn(
                            "{} - END: Processing timed out after {} ms, not sending ACK. Triggering reconnect for retransmission. connector: {}, cancel result: {}",
                            tenant, timeout, connectorIdentifier, cancelResult);
                    triggerReconnectOrAck(mqttMessage, topic);
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
     *   <li>Increments the consecutive-failure counter.</li>
     *   <li>If the counter is below {@link #MAX_CONSECUTIVE_RECONNECTS}: triggers a
     *       rate-limited disconnect so the broker retransmits the unACKed message after
     *       the session is restored.</li>
     *   <li>If the counter reaches the threshold: the message is treated as a
     *       <em>poison pill</em> — it is ACKed (discarded) and a hard error is logged.
     *       This breaks an infinite reconnect loop caused by a permanently unprocessable
     *       message.</li>
     * </ul>
     *
     * @param mqttMessage the unacknowledged MQTT message
     * @param topic       human-readable topic string for logging
     */
    private void triggerReconnectOrAck(Mqtt3Publish mqttMessage, String topic) {
        int count = consecutiveReconnectCount.incrementAndGet();
        if (count <= MAX_CONSECUTIVE_RECONNECTS) {
            triggerReconnect();
        } else {
            // Poison-pill detected: ACK to remove from broker session and break the loop.
            log.error("{} - POISON PILL: {} consecutive failures without success — ACKing message to prevent "
                    + "infinite reconnect loop. Message is discarded. topic: [{}], connector: {}",
                    tenant, count, topic, connectorIdentifier);
            consecutiveReconnectCount.set(0); // reset so normal processing can resume
            mqttMessage.acknowledge();
        }
    }

    /**
     * Triggers a connector disconnect (rate-limited to once per {@value #MIN_RECONNECT_INTERVAL_MS} ms).
     * <p>
     * Two guards prevent parallel execution:
     * <ol>
     *   <li><b>Rate-limit</b>: reconnect is suppressed if the last one was less than
     *       {@value #MIN_RECONNECT_INTERVAL_MS} ms ago.</li>
     *   <li><b>In-progress flag</b>: {@link #reconnectInProgress} ensures that only one
     *       reconnect runs at a time. Concurrent callers immediately return without
     *       triggering a second disconnect.</li>
     * </ol>
     * Disconnecting causes the MQTT broker to retain all unACKed QoS&gt;0 messages in the session.
     * When the connector reconnects (with {@code cleanSession=false}), the broker retransmits those
     * messages — effectively a safe retry without data loss.
     */
    private void triggerReconnect() {
        // Rate-limit pre-check (fast path, no locking needed)
        long now = System.currentTimeMillis();
        long last = lastReconnectTriggerMs.get();
        if (now - last < MIN_RECONNECT_INTERVAL_MS) {
            log.warn("{} - Reconnect suppressed (rate-limited: last was {}ms ago, min {}ms), connector: {}",
                    tenant, now - last, MIN_RECONNECT_INTERVAL_MS, connectorIdentifier);
            return;
        }
        // Parallel-execution guard: only one reconnect at a time
        if (!reconnectInProgress.compareAndSet(false, true)) {
            log.warn("{} - Reconnect already in progress, skipping parallel trigger, connector: {}",
                    tenant, connectorIdentifier);
            return;
        }
        // Update timestamp now that we own the reconnect slot
        lastReconnectTriggerMs.set(now);
        log.warn("{} - Triggering connector reconnect for message retransmission (rate-limit: {}s), connector: {}",
                tenant, MIN_RECONNECT_INTERVAL_MS / 1000, connectorIdentifier);
        // Run disconnect in a dedicated virtual thread so it doesn't block the callback thread.
        // Housekeeping will detect the disconnection and reconnect automatically.
        // The in-progress flag is reset in the finally block after the trigger completes.
        Thread.ofVirtual()
                .name("mqtt-timeout-reconnect-" + connectorIdentifier)
                .start(() -> {
                    try {
                        reconnectTrigger.run();
                    } finally {
                        reconnectInProgress.set(false);
                    }
                });
    }

}