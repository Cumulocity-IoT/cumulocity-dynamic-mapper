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

package dynamic.mapper.connector.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Manages Sparkplug Birth and Death Certificate lifecycle for MQTT connectors.
 * Handles certificate generation, publishing, and periodic republishing (for brokers that don't support retain).
 */
@Slf4j
public class SparkplugCertificateManager {

    private static final String SPARKPLUG_STATE_TOPIC_PATTERN = "spBv1.0/STATE/%s";
    private static final String SPARKPLUG_SUBSCRIBE_PATTERN = "spBv1.0/+";
    private static final int PERIODIC_BIRTH_CERTIFICATE_INTERVAL_SECONDS = 60;

    private final String tenant;
    private final String sparkplugHostId;
    private final ObjectMapper objectMapper;
    private final SparkplugPublisher publisher;
    private ScheduledFuture<?> periodicBirthCertificateTask;

    /**
     * Interface for publishing Sparkplug certificates.
     * Implementations handle protocol-specific publishing (MQTT, Pulsar, etc.)
     */
    public interface SparkplugPublisher {
        /**
         * Publish a Sparkplug certificate to the specified topic
         *
         * @param topic The MQTT topic
         * @param payload The certificate payload (JSON bytes)
         * @throws Exception if publishing fails
         */
        void publishCertificate(String topic, byte[] payload) throws Exception;

        /**
         * Subscribe to a topic pattern
         *
         * @param topicPattern The topic pattern to subscribe to
         * @throws Exception if subscription fails
         */
        void subscribeTopic(String topicPattern) throws Exception;
    }

    /**
     * Constructor for SparkplugCertificateManager
     *
     * @param tenant The tenant identifier
     * @param sparkplugHostId The Sparkplug Host ID
     * @param objectMapper Jackson ObjectMapper for JSON serialization
     * @param publisher The certificate publisher
     */
    public SparkplugCertificateManager(String tenant, String sparkplugHostId,
                                       ObjectMapper objectMapper, SparkplugPublisher publisher) {
        this.tenant = tenant;
        this.sparkplugHostId = sparkplugHostId;
        this.objectMapper = objectMapper;
        this.publisher = publisher;
    }

    /**
     * Publish a Birth Certificate
     *
     * @return true if successful, false otherwise
     */
    public boolean publishBirthCertificate() {
        try {
            String topic = buildStateTopicName();
            byte[] payload = buildCertificatePayload(true);
            publisher.publishCertificate(topic, payload);
            log.info("{} - Published Sparkplug Birth Certificate to topic: [{}]", tenant, topic);
            return true;
        } catch (Exception e) {
            log.error("{} - Error publishing Sparkplug Birth Certificate", tenant, e);
            return false;
        }
    }

    /**
     * Publish a Death Certificate
     *
     * @return true if successful, false otherwise
     */
    public boolean publishDeathCertificate() {
        try {
            String topic = buildStateTopicName();
            byte[] payload = buildCertificatePayload(false);
            publisher.publishCertificate(topic, payload);
            log.debug("{} - Published Sparkplug Death Certificate to topic: [{}]", tenant, topic);
            return true;
        } catch (Exception e) {
            log.error("{} - Error publishing Sparkplug Death Certificate", tenant, e);
            return false;
        }
    }

    /**
     * Subscribe to Sparkplug topics after connection
     *
     * @return true if successful, false otherwise
     */
    public boolean subscribeToSparkplugTopics() {
        try {
            // Subscribe to own state topic
            String stateTopic = buildStateTopicName();
            publisher.subscribeTopic(stateTopic);
            log.info("{} - Subscribed to own Sparkplug STATE topic: [{}]", tenant, stateTopic);

            // Subscribe to all spBv1.0 topics
            String allSparkplugPattern = SPARKPLUG_SUBSCRIBE_PATTERN;
            publisher.subscribeTopic(allSparkplugPattern);
            log.info("{} - Subscribed to Sparkplug topic pattern: [{}]", tenant, allSparkplugPattern);

            return true;
        } catch (Exception e) {
            log.error("{} - Error subscribing to Sparkplug topics", tenant, e);
            return false;
        }
    }

    /**
     * Schedule periodic Birth Certificate publishing
     * Used for brokers that don't support message retain (e.g., Pulsar MQTT Service)
     *
     * @param scheduler The ScheduledExecutorService to use for scheduling
     */
    public void schedulePeriodicBirthCertificates(ScheduledExecutorService scheduler) {
        if (periodicBirthCertificateTask != null && !periodicBirthCertificateTask.isCancelled()) {
            log.debug("{} - Periodic Birth Certificate publishing already scheduled", tenant);
            return;
        }

        periodicBirthCertificateTask = scheduler.scheduleAtFixedRate(
                () -> {
                    if (!publishBirthCertificate()) {
                        log.warn("{} - Failed to publish periodic Birth Certificate", tenant);
                    }
                },
                PERIODIC_BIRTH_CERTIFICATE_INTERVAL_SECONDS,
                PERIODIC_BIRTH_CERTIFICATE_INTERVAL_SECONDS,
                TimeUnit.SECONDS);

        log.info("{} - Scheduled periodic Birth Certificate publishing every {} seconds",
                tenant, PERIODIC_BIRTH_CERTIFICATE_INTERVAL_SECONDS);
    }

    /**
     * Stop periodic Birth Certificate publishing
     */
    public void stopPeriodicPublishing() {
        if (periodicBirthCertificateTask != null) {
            periodicBirthCertificateTask.cancel(false);
            periodicBirthCertificateTask = null;
            log.info("{} - Stopped periodic Birth Certificate publishing", tenant);
        }
    }

    /**
     * Build the STATE topic name for this Sparkplug Host
     *
     * @return The full topic name
     */
    private String buildStateTopicName() {
        return String.format(SPARKPLUG_STATE_TOPIC_PATTERN, sparkplugHostId);
    }

    /**
     * Build the certificate payload as JSON bytes
     *
     * @param online true for Birth Certificate (online: true), false for Death Certificate (online: false)
     * @return The certificate payload as UTF-8 JSON bytes
     * @throws Exception if serialization fails
     */
    private byte[] buildCertificatePayload(boolean online) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("online", online);
        payload.put("timestamp", System.currentTimeMillis());

        String jsonStr = objectMapper.writeValueAsString(payload);
        return jsonStr.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Get the Sparkplug State topic name
     *
     * @return The STATE topic name
     */
    public String getStateTopicName() {
        return buildStateTopicName();
    }

    /**
     * Get the Sparkplug Host ID
     *
     * @return The Sparkplug Host ID
     */
    public String getSparkplugHostId() {
        return sparkplugHostId;
    }
}

