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

package dynamic.mapper.notification.service;

import com.cumulocity.microservice.context.credentials.MicroserviceCredentials;
import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;
import com.hivemq.client.mqtt.mqtt3.Mqtt3Client;
import com.hivemq.client.mqtt.mqtt3.message.auth.Mqtt3SimpleAuth;
import dynamic.mapper.core.ConfigurationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * Manages MQTT push connectivity for devices.
 */
@Slf4j
@Service
public class MqttPushManager {

    private static final int CONNECTION_TIMEOUT_SECONDS = 30;

    @Autowired
    private MicroserviceSubscriptionsService subscriptionsService;

    private ConfigurationRegistry configurationRegistry;

    @Autowired
    public void setConfigurationRegistry(@Lazy ConfigurationRegistry configurationRegistry) {
        this.configurationRegistry = configurationRegistry;
    }

    @Value("${C8Y.baseURL}")
    private String baseUrl;

    // Active MQTT connections
    private final Map<String, Map<String, Mqtt3Client>> activePushConnections = new ConcurrentHashMap<>();

    // === Public API ===

    public void activatePushConnectivity(String tenant, String deviceId) {
        if (tenant == null || deviceId == null || deviceId.trim().isEmpty()) {
            log.warn("{} - Cannot activate push connectivity: invalid parameters", tenant);
            return;
        }

        // Check if already connected
        Map<String, Mqtt3Client> tenantConnections = activePushConnections.get(tenant);
        if (tenantConnections != null && tenantConnections.containsKey(deviceId)) {
            Mqtt3Client existing = tenantConnections.get(deviceId);
            if (existing != null && existing.getState().isConnected()) {
                log.debug("{} - MQTT already connected for device {}", tenant, deviceId);
                return;
            }
        }

        try {
            String mqttHost = extractMqttHost(baseUrl);
            log.info("{} - Activating MQTT push connectivity for device {} at host {}",
                    tenant, deviceId, mqttHost);

            Optional<MicroserviceCredentials> credentialsOpt = subscriptionsService.getCredentials(tenant);
            if (credentialsOpt.isEmpty()) {
                log.warn("{} - No credentials found for tenant", tenant);
                return;
            }

            MicroserviceCredentials credentials = credentialsOpt.get();
            Mqtt3SimpleAuth auth = Mqtt3SimpleAuth.builder()
                    .username(credentials.getTenant() + "/" + credentials.getUsername())
                    .password(credentials.getPassword().getBytes(StandardCharsets.UTF_8))
                    .build();

            Mqtt3AsyncClient client = Mqtt3Client.builder()
                    .serverHost(mqttHost)
                    .serverPort(8883)
                    .sslWithDefaultConfig()
                    .identifier(deviceId)
                    .automaticReconnectWithDefaultConfig()
                    .simpleAuth(auth)
                    .buildAsync();

            CompletableFuture<Void> connectionFuture = client.connectWith()
                    .cleanSession(true)
                    .keepAlive(60)
                    .send()
                    .thenRun(() -> {
                        log.info("{} - MQTT connected for device {}", tenant, deviceId);

                        // Subscribe to device messages
                        client.toAsync().subscribeWith()
                                .topicFilter("s/ds")
                                .qos(MqttQos.AT_LEAST_ONCE)
                                .callback(publish -> {
                                    if (log.isDebugEnabled()) {
                                        log.debug("{} - MQTT message received from device {}: {}",
                                                tenant, deviceId, new String(publish.getPayloadAsBytes()));
                                    }
                                })
                                .send()
                                .whenComplete((ack, throwable) -> {
                                    if (throwable != null) {
                                        log.warn("{} - Failed to subscribe to topic for device {}: {}",
                                                tenant, deviceId, throwable.getMessage());
                                    }
                                });
                    });

            // Handle connection errors
            connectionFuture.exceptionally(throwable -> {
                logMqttError(tenant, deviceId, throwable);
                return null;
            });

            // Add timeout
            connectionFuture.orTimeout(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .whenComplete((result, throwable) -> {
                        if (throwable instanceof TimeoutException) {
                            log.warn("{} - MQTT connection timeout for device {}", tenant, deviceId);
                            client.disconnect();
                        }
                    });

            activePushConnections.computeIfAbsent(tenant, k -> new ConcurrentHashMap<>())
                    .put(deviceId, client);

        } catch (Exception e) {
            log.error("{} - Error activating push connectivity for device {}: {}",
                    tenant, deviceId, e.getMessage(), e);
        }
    }

    public void activatePushConnectivityForDevice(String tenant, ManagedObjectRepresentation mor) {
        try {
            ExternalIDRepresentation extId = configurationRegistry.getC8yAgent()
                    .resolveGlobalId2ExternalId(tenant, mor.getId(), null, null);

            String deviceId = extId != null ? extId.getExternalId() : mor.getId().getValue();
            activatePushConnectivity(tenant, deviceId);
        } catch (Exception e) {
            log.warn("{} - Error activating push connectivity for device {}: {}",
                    tenant, mor.getId().getValue(), e.getMessage());
        }
    }

    public void deactivatePushConnectivity(String tenant, String deviceId) {
        if (tenant == null || deviceId == null) {
            return;
        }

        Map<String, Mqtt3Client> clients = activePushConnections.get(tenant);
        if (clients != null) {
            Mqtt3Client client = clients.remove(deviceId);
            if (client != null && client.getState().isConnected()) {
                try {
                    client.toBlocking().disconnect();
                    log.info("{} - MQTT disconnected for device {}", tenant, deviceId);
                } catch (Exception e) {
                    log.warn("{} - Error disconnecting MQTT for device {}: {}",
                            tenant, deviceId, e.getMessage());
                }
            }
        }
    }

    public void deactivatePushConnectivityForDevice(String tenant, ManagedObjectRepresentation mor) {
        try {
            ExternalIDRepresentation extId = configurationRegistry.getC8yAgent()
                    .resolveGlobalId2ExternalId(tenant, mor.getId(), null, null);

            String deviceId = extId != null ? extId.getExternalId() : mor.getId().getValue();
            deactivatePushConnectivity(tenant, deviceId);
        } catch (Exception e) {
            log.warn("{} - Error deactivating push connectivity for device {}: {}",
                    tenant, mor.getId().getValue(), e.getMessage());
        }
    }

    public void disconnectAll(String tenant) {
        Map<String, Mqtt3Client> tenantConnections = activePushConnections.remove(tenant);
        if (tenantConnections != null) {
            int disconnectedCount = 0;
            for (Map.Entry<String, Mqtt3Client> entry : tenantConnections.entrySet()) {
                try {
                    Mqtt3Client client = entry.getValue();
                    if (client != null && client.getState().isConnected()) {
                        client.toBlocking().disconnect();
                        disconnectedCount++;
                    }
                } catch (Exception e) {
                    log.warn("{} - Error disconnecting MQTT for device {}: {}",
                            tenant, entry.getKey(), e.getMessage());
                }
            }
            log.info("{} - Disconnected {} MQTT connections", tenant, disconnectedCount);
        }
    }

    // === Private Helper Methods ===

    private String extractMqttHost(String baseUrl) {
        if (baseUrl == null) {
            throw new IllegalArgumentException("Base URL cannot be null");
        }
        return baseUrl.replace("http://", "")
                .replace("https://", "")
                .replace(":8111", "")
                .replace(":8111/", "");
    }

    private void logMqttError(String tenant, String deviceId, Throwable throwable) {
        String errorClass = throwable.getClass().getSimpleName();
        String errorMessage = throwable.getMessage();
        String causeInfo = "";

        if (throwable.getCause() != null) {
            Throwable cause = throwable.getCause();
            causeInfo = String.format(" | Caused by %s: %s",
                    cause.getClass().getSimpleName(),
                    cause.getMessage() != null ? cause.getMessage() : "No cause message");
        }

        log.error("{} - MQTT connection failed for device {}: {}{}{}",
                tenant, deviceId, errorClass,
                errorMessage != null ? ": " + errorMessage : " (no message)",
                causeInfo);

        log.debug("{} - Full stack trace:", tenant, throwable);
    }

    @PreDestroy
    public void cleanup() {
        log.info("Cleaning up MqttPushManager");

        // Disconnect all MQTT connections
        for (String tenant : new HashSet<>(activePushConnections.keySet())) {
            disconnectAll(tenant);
        }

        activePushConnections.clear();

        log.info("MqttPushManager cleanup completed");
    }
}
