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

package dynamic.mapper.connector.mqtt;

import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient;
import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient;
import com.hivemq.client.mqtt.mqtt5.Mqtt5Client;
import com.hivemq.client.mqtt.mqtt5.Mqtt5ClientBuilder;
import com.hivemq.client.mqtt.mqtt5.message.auth.Mqtt5SimpleAuth;
import com.hivemq.client.mqtt.mqtt5.message.connect.connack.Mqtt5ConnAck;
import com.hivemq.client.mqtt.mqtt5.message.connect.connack.Mqtt5ConnAckReasonCode;
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish;
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5PublishBuilder;
import com.hivemq.client.mqtt.mqtt5.message.unsubscribe.Mqtt5Unsubscribe;
import com.hivemq.client.mqtt.mqtt5.message.auth.Mqtt5SimpleAuthBuilder.Complete;
import dynamic.mapper.configuration.ConnectorConfiguration;
import dynamic.mapper.connector.core.ConnectorSpecification;
import dynamic.mapper.connector.core.ConnectorSpecificationBuilder;
import dynamic.mapper.connector.core.client.ConnectorException;
import dynamic.mapper.connector.core.client.ConnectorType;
import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.model.ConnectorStatus;
import dynamic.mapper.processor.inbound.CamelDispatcherInbound;
import dynamic.mapper.processor.model.DynamicMapperRequest;
import dynamic.mapper.processor.model.ProcessingContext;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;

/**
 * MQTT 5.0 Connector Client.
 * Extends AMQTTClient with MQTT 5.0 specific functionality.
 */
@Slf4j
public class MQTT5Client extends AMQTTClient {

    // MQTT5-specific fields only
    private Mqtt5BlockingClient mqttClient;
    private MQTT5Callback mqttCallback;

    /**
     * Default constructor - initializes connector specification
     */
    public MQTT5Client() {
        super();
        this.connectorSpecification = createConnectorSpecification();
    }

    /**
     * Full constructor with dependencies
     */
    public MQTT5Client(ConfigurationRegistry configurationRegistry,
            ConnectorRegistry connectorRegistry,
            ConnectorConfiguration connectorConfiguration,
            CamelDispatcherInbound dispatcher,
            String additionalSubscriptionIdTest,
            String tenant) {
        super(configurationRegistry, connectorRegistry, connectorConfiguration,
                dispatcher, additionalSubscriptionIdTest, tenant);
        this.connectorSpecification = createConnectorSpecification();
    }

    @Override
    protected void buildMqttClient() {
        String protocol = (String) connectorConfiguration.getProperties()
                .getOrDefault("protocol", MQTT_PROTOCOL_MQTT);
        String mqttHost = (String) connectorConfiguration.getProperties().get("mqttHost");
        Integer mqttPort = (Integer) connectorConfiguration.getProperties().get("mqttPort");
        String clientId = (String) connectorConfiguration.getProperties().get("clientId");
        String user = (String) connectorConfiguration.getProperties().get("user");
        String password = (String) connectorConfiguration.getProperties().get("password");
        cleanSession = (Boolean) connectorConfiguration.getProperties()
                .getOrDefault("cleanSession", true); // Note: cleanSession maps to cleanStart in MQTT5

        // Build client
        Mqtt5ClientBuilder builder = Mqtt5Client.builder()
                .serverHost(mqttHost)
                .serverPort(mqttPort)
                .identifier(clientId + (additionalSubscriptionIdTest != null ? additionalSubscriptionIdTest : ""));

        // Add authentication if provided
        if (!StringUtils.isEmpty(user)) {
            Complete authBuilder = Mqtt5SimpleAuth.builder().username(user);
            if (!StringUtils.isEmpty(password)) {
                authBuilder.password(password.getBytes(StandardCharsets.UTF_8));
            }
            builder.simpleAuth(authBuilder.build());
        }

        // Add SSL if needed
        boolean useSelfSignedCertificate = (Boolean) connectorConfiguration.getProperties()
                .getOrDefault("useSelfSignedCertificate", false);

        if (useSelfSignedCertificate && mqttSslConfig != null) {
            builder.sslConfig(mqttSslConfig);
            log.debug("{} - Using self-signed certificate for MQTT5", tenant);
        } else if (MQTT_PROTOCOL_MQTTS.equals(protocol) || MQTT_PROTOCOL_WSS.equals(protocol)) {
            builder.sslWithDefaultConfig();
        }

        // Add WebSocket if needed
        if (MQTT_PROTOCOL_WS.equals(protocol) || MQTT_PROTOCOL_WSS.equals(protocol)) {
            builder.webSocketWithDefaultConfig();
            String serverPath = (String) connectorConfiguration.getProperties().get("serverPath");
            if (serverPath != null) {
                builder.webSocketConfig()
                        .serverPath(serverPath)
                        .applyWebSocketConfig();
            }
            log.debug("{} - Using WebSocket with path: {}", tenant, serverPath);
        }

        // Add listeners (using base class synchronization)
        mqttClient = builder
                .addDisconnectedListener(context -> {
                    boolean wasConnected = connectionStateManager.isConnected();
                    connectionStateManager.setConnected(false);

                    // Check if we should reconnect (using base class intentionalDisconnect flag)
                    boolean shouldReconnect;
                    synchronized (disconnectionLock) {
                        shouldReconnect = !intentionalDisconnect &&
                                !isDisconnecting &&
                                connectorConfiguration.getEnabled() &&
                                wasConnected;
                    }

                    if (shouldReconnect) {
                        log.warn("{} - Unexpected disconnection, attempting to reconnect", tenant);
                        virtualThreadPool.submit(() -> {
                            try {
                                Thread.sleep(5000);
                                connect();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                log.debug("{} - Reconnection interrupted", tenant);
                            } catch (Exception e) {
                                log.error("{} - Error during reconnection", tenant, e);
                            }
                        });
                    } else {
                        log.debug(
                                "{} - Intentional disconnect or not reconnecting (intentional={}, disconnecting={}, enabled={}, wasConnected={})",
                                tenant, intentionalDisconnect, isDisconnecting,
                                connectorConfiguration.getEnabled(), wasConnected);
                    }
                })
                .addConnectedListener(context -> {
                    connectionStateManager.setConnected(true);
                    log.info("{} - MQTT5 client connected", tenant);
                })
                .buildBlocking();
    }

    @Override
    protected void createMqttCallback() {
        mqttCallback = new MQTT5Callback(
                tenant,
                configurationRegistry,
                dispatcher,
                connectorIdentifier,
                connectorName);
    }

    @Override
    protected void connectMqttWithRetry() {
        int maxAttempts = 3;
        int attempt = 0;

        while (attempt < maxAttempts && !isConnected() && shouldConnect()) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }

            try {
                if (attempt > 0) {
                    log.info("{} - Connection attempt {} of {}", tenant, attempt + 1, maxAttempts);
                    Thread.sleep(WAIT_PERIOD_MS);
                }

                Mqtt5ConnAck ack = mqttClient.connectWith()
                        .cleanStart(cleanSession)
                        .keepAlive(60)
                        .send();

                if (!ack.getReasonCode().equals(Mqtt5ConnAckReasonCode.SUCCESS)) {
                    throw new ConnectorException(
                            String.format("Connection failed with code: %s", ack.getReasonCode().name()));
                }

                connectionStateManager.setConnected(true);
                connectionStateManager.updateStatus(ConnectorStatus.CONNECTED, true, true);

                log.info("{} - MQTT5 client connected successfully to {}:{}",
                        tenant, mqttClient.getConfig().getServerHost(), mqttClient.getConfig().getServerPort());

                // Log MQTT5 specific connection properties
                if (ack.isSessionPresent()) {
                    log.info("{} - MQTT5 session present, reusing existing session", tenant);
                } else {
                    log.info("{} - MQTT5 new session created", tenant);
                }

                // Log received properties if any
                if (ack.getSessionExpiryInterval().isPresent()) {
                    log.debug("{} - Session expiry interval: {} seconds",
                            tenant, ack.getSessionExpiryInterval().getAsLong());
                }

            } catch (Exception e) {
                attempt++;
                log.error("{} - Connection attempt {} failed: {}", tenant, attempt, e.getMessage());

                if (attempt >= maxAttempts) {
                    connectionStateManager.updateStatusWithError(e);
                }
            }
        }
    }

    @Override
    protected void disconnectMqttClient() {
        if (mqttClient != null) {
            try {
                if (mqttClient.getState().isConnected()) {
                    mqttClient.disconnect();
                    log.info("{} - MQTT5 client disconnected successfully", tenant);
                } else {
                    log.debug("{} - MQTT5 client was not connected, skipping disconnect", tenant);
                }
            } catch (Exception e) {
                log.debug("{} - Error disconnecting MQTT5 client (may already be disconnected): {}",
                        tenant, e.getMessage());
            }
        }
    }

    @Override
    protected boolean isMqttClientConnected() {
        return mqttClient != null && mqttClient.getState().isConnected();
    }

    @Override
    protected void closeMqttResources() {
        synchronized (disconnectionLock) {
            mqttClient = null;
            mqttCallback = null;
        }
    }

    @Override
    protected void unsubscribeMqttTopic(String topic) {
        if (mqttClient != null && mqttClient.getState().isConnected()) {
            mqttClient.unsubscribe(Mqtt5Unsubscribe.builder().topicFilter(topic).build());
        }
    }

    @Override
    protected void subscribe(String topic, dynamic.mapper.model.Qos qos) throws ConnectorException {
        if (!isConnected()) {
            throw new ConnectorException("Cannot subscribe: not connected");
        }

        try {
            // Use base class adjustQos
            dynamic.mapper.model.Qos adjustedQos = adjustQos(qos);

            log.debug("{} - Subscribing to topic: [{}], QoS: {}", tenant, topic, adjustedQos);

            Mqtt5AsyncClient asyncClient = mqttClient.toAsync();
            asyncClient.subscribeWith()
                    .topicFilter(topic)
                    .qos(MqttQos.fromCode(adjustedQos.ordinal()))
                    .callback(mqttCallback)
                    .manualAcknowledgement(true)
                    .send()
                    .whenComplete((subAck, throwable) -> {
                        if (throwable != null) {
                            log.error("{} - Failed to subscribe to topic: [{}]", tenant, topic, throwable);
                        } else {
                            log.info("{} - Successfully subscribed to topic: [{}], QoS: {}",
                                    tenant, topic, adjustedQos);

                            // MQTT5 specific: log reason codes for each subscription
                            subAck.getReasonCodes().forEach(reasonCode -> {
                                if (reasonCode.isError()) {
                                    log.warn("{} - Subscription error for topic [{}]: {}",
                                            tenant, topic, reasonCode);
                                }
                            });

                            sendSubscriptionEvents(topic, "Subscribed");
                        }
                    });

        } catch (Exception e) {
            throw new ConnectorException("Failed to subscribe to topic: " + topic, e);
        }
    }

    @Override
    protected void unsubscribe(String topic) throws ConnectorException {
        if (!isConnected()) {
            log.warn("{} - Cannot unsubscribe: not connected", tenant);
            return;
        }

        log.debug("{} - Unsubscribing from topic: [{}]", tenant, topic);

        Mqtt5AsyncClient asyncClient = mqttClient.toAsync();
        asyncClient.unsubscribe(Mqtt5Unsubscribe.builder().topicFilter(topic).build())
                .whenComplete((unsubAck, throwable) -> {
                    if (throwable != null) {
                        log.error("{} - Failed to unsubscribe from topic: [{}]", tenant, topic, throwable);
                    } else {
                        log.info("{} - Successfully unsubscribed from topic: [{}]", tenant, topic);

                        // MQTT5 specific: log reason codes for each unsubscription
                        unsubAck.getReasonCodes().forEach(reasonCode -> {
                            if (reasonCode.isError()) {
                                log.warn("{} - Unsubscription error for topic [{}]: {}",
                                        tenant, topic, reasonCode);
                            }
                        });

                        sendSubscriptionEvents(topic, "Unsubscribed");
                    }
                });
    }

    @Override
    public void publishMEAO(ProcessingContext<?> context) {
        if (!isConnected()) {
            log.warn("{} - Cannot publish: not connected", tenant);
            return;
        }

        var requests = context.getRequests();
        if (requests == null || requests.isEmpty()) {
            log.warn("{} - No requests to publish for mapping: {}", tenant, context.getMapping().getName());
            return;
        }

        MqttQos mqttQos = MqttQos.fromCode(context.getQos().ordinal());

        // Process each request
        for (int i = 0; i < requests.size(); i++) {
            DynamicMapperRequest request = requests.get(i);

            if (request == null || request.getRequest() == null) {
                log.warn("{} - Skipping null request or payload ({}/{})", tenant, i + 1, requests.size());
                continue;
            }

            String payload = request.getRequest();
            String topic = request.getPublishTopic() != null ? request.getPublishTopic() : context.getResolvedPublishTopic();

            if (topic == null || topic.isEmpty()) {
                log.warn("{} - No topic specified for request ({}/{}), skipping", tenant, i + 1, requests.size());
                request.setError(new Exception("No publish topic specified"));
                continue;
            }

            try {
                Mqtt5PublishBuilder.Complete messageBuilder = Mqtt5Publish.builder()
                        .topic(topic)
                        .retain(context.getRetain() == null ? false : context.getRetain())
                        .qos(mqttQos)
                        .payload(payload.getBytes(StandardCharsets.UTF_8));

                // MQTT5 specific: can add user properties, content type, etc.
                // Example: messageBuilder.contentType("application/json");

                Mqtt5Publish message = messageBuilder.build();

                mqttClient.publish(message);

                if (context.getMapping().getDebug() || context.getServiceConfiguration().getLogPayload()) {
                    log.info("{} - Published message ({}/{}): topic=[{}], QoS: {}, payload: {}",
                            tenant, i + 1, requests.size(), topic, mqttQos, payload);
                } else {
                    log.debug("{} - Published message ({}/{}): topic=[{}], QoS: {}", tenant, i + 1, requests.size(), topic, mqttQos);
                }

            } catch (Exception e) {
                log.error("{} - Error publishing to topic: {} ({}/{})", tenant, topic, i + 1, requests.size(), e);
                request.setError(e);
                context.addError(new dynamic.mapper.processor.ProcessingException(
                        "Failed to publish message " + (i + 1) + "/" + requests.size(), e));
            }
        }
    }

    @Override
    protected void connectorSpecificHousekeeping(String tenant) {
        // MQTT5-specific housekeeping tasks
        if (mqttClient != null && !mqttClient.getState().isConnected() && shouldConnect()) {
            log.warn("{} - MQTT5 client is disconnected (state will be handled by connection manager)", tenant);
        }
    }

    @Override
    protected ConnectorSpecification createConnectorSpecification() {
        return ConnectorSpecificationBuilder
                .create("Generic MQTT 5.0", ConnectorType.MQTT)
                .description("Connector for connecting to external MQTT 5.0 broker over tcp or websocket.")
                .properties(buildCommonMqttProperties(MQTT_VERSION_5_0))
                .supportedDirections(supportedDirections())
                .build();
    }
}
