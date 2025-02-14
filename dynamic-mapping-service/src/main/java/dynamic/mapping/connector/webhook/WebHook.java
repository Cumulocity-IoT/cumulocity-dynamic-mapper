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

package dynamic.mapping.connector.webhook;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.TrustManagerFactory;
import dynamic.mapping.connector.core.ConnectorPropertyType;
import dynamic.mapping.connector.core.ConnectorSpecification;
import dynamic.mapping.connector.core.client.AConnectorClient;
import dynamic.mapping.connector.core.client.ConnectorException;
import dynamic.mapping.connector.core.client.ConnectorType;
import dynamic.mapping.model.Direction;
import dynamic.mapping.model.Mapping;
import dynamic.mapping.model.QOS;
import dynamic.mapping.processor.inbound.DispatcherInbound;
import dynamic.mapping.processor.model.C8YRequest;
import dynamic.mapping.processor.model.ProcessingContext;
import jakarta.ws.rs.NotSupportedException;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.mutable.MutableInt;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.hivemq.client.mqtt.MqttGlobalPublishFilter;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;
import com.hivemq.client.mqtt.mqtt3.message.auth.Mqtt3SimpleAuth;
import com.hivemq.client.mqtt.mqtt3.message.connect.connack.Mqtt3ConnAck;
import com.hivemq.client.mqtt.mqtt3.message.connect.connack.Mqtt3ConnAckReturnCode;
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish;
import com.hivemq.client.mqtt.mqtt3.message.unsubscribe.Mqtt3Unsubscribe;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import dynamic.mapping.configuration.ConnectorConfiguration;
import dynamic.mapping.connector.core.ConnectorProperty;
import dynamic.mapping.connector.core.ConnectorPropertyCondition;
import dynamic.mapping.core.ConfigurationRegistry;
import dynamic.mapping.core.ConnectorStatus;
import dynamic.mapping.core.ConnectorStatusEvent;

@Slf4j
public class WebHook extends AConnectorClient {
    public WebHook() {
        Map<String, ConnectorProperty> configProps = new HashMap<>();
        ConnectorPropertyCondition basicAuthenticationCondition = new ConnectorPropertyCondition("authentication",
                new String[] { "Basic" });
        ConnectorPropertyCondition bearerAuthenticationCondition = new ConnectorPropertyCondition("authentication",
                new String[] { "Bearer Token" });
        configProps.put("baseUrl",
                new ConnectorProperty(true, 0, ConnectorPropertyType.STRING_PROPERTY, false, false, null, null, null));
        configProps.put("authentication",
                new ConnectorProperty(true, 1, ConnectorPropertyType.OPTION_PROPERTY, false, false, null,
                        Map.ofEntries(
                                new AbstractMap.SimpleEntry<String, String>("Basic", "Basic"),
                                new AbstractMap.SimpleEntry<String, String>("Bearer Token", "Bearer Token")),
                        null));
        configProps.put("user",
                new ConnectorProperty(false, 2, ConnectorPropertyType.STRING_PROPERTY, false, false, null, null,
                        basicAuthenticationCondition));
        configProps.put("password",
                new ConnectorProperty(false, 3, ConnectorPropertyType.SENSITIVE_STRING_PROPERTY, false, false, null,
                        null, basicAuthenticationCondition));
        configProps.put("token",
                new ConnectorProperty(true, 4, ConnectorPropertyType.STRING_PROPERTY, false, false, null, null,
                        bearerAuthenticationCondition));
        configProps.put("headerAccept",
                new ConnectorProperty(false, 5, ConnectorPropertyType.BOOLEAN_PROPERTY, false, false,
                        "application/json", null,
                        null));
        configProps.put("baseUrlHealthEndpoint",
                new ConnectorProperty(true, 6, ConnectorPropertyType.STRING_PROPERTY, false, false, null, null, null));
        String name = "Webhook";
        String description = "Webhook to send outbound messages to.";
        connectorType = ConnectorType.WEB_HOOK;
        connectorSpecification = new ConnectorSpecification(name, description, connectorType, configProps, false,
                supportedDirections());
    }

    public WebHook(ConfigurationRegistry configurationRegistry,
            ConnectorConfiguration connectorConfiguration,
            DispatcherInbound dispatcher, String additionalSubscriptionIdTest, String tenant) {
        this();
        this.configurationRegistry = configurationRegistry;
        this.mappingComponent = configurationRegistry.getMappingComponent();
        this.serviceConfigurationComponent = configurationRegistry.getServiceConfigurationComponent();
        this.connectorConfigurationComponent = configurationRegistry.getConnectorConfigurationComponent();
        this.connectorConfiguration = connectorConfiguration;
        // ensure the client knows its identity even if configuration is set to null
        this.connectorName = connectorConfiguration.name;
        this.connectorIdentifier = connectorConfiguration.identifier;
        this.connectorStatus = ConnectorStatusEvent.unknown(connectorConfiguration.name,
                connectorConfiguration.identifier);
        // this.connectorType = connectorConfiguration.connectorType;
        this.c8yAgent = configurationRegistry.getC8yAgent();
        this.virtThreadPool = configurationRegistry.getVirtThreadPool();
        this.objectMapper = configurationRegistry.getObjectMapper();
        this.additionalSubscriptionIdTest = additionalSubscriptionIdTest;
        this.mappingServiceRepresentation = configurationRegistry.getMappingServiceRepresentations().get(tenant);
        this.serviceConfiguration = configurationRegistry.getServiceConfigurations().get(tenant);
        this.dispatcher = dispatcher;
        this.tenant = tenant;
        this.supportedQOS = Arrays.asList(QOS.AT_LEAST_ONCE, QOS.AT_MOST_ONCE, QOS.EXACTLY_ONCE);
    }

    protected WebhookCallback webhookCallback = null;

    protected RestClient webhookClient;
    protected RestClient webhookClientHealth;

    @Getter
    protected List<QOS> supportedQOS;

    public boolean initialize() {
        loadConfiguration();
        log.info("Tenant {} - Connector {} - Initialization of connector {} was successful!", tenant,
                getConnectorType(),
                getConnectorName());
        return true;
    }

    @Override
    public void connect() {
        log.info("Tenant {} - Trying to connect to {} - phase I: (isConnected:shouldConnect) ({}:{})",
                tenant, getConnectorName(), isConnected(),
                shouldConnect());
        if (isConnected())
            disconnect();

        if (shouldConnect())
            updateConnectorStatusAndSend(ConnectorStatus.CONNECTING, true, shouldConnect());
        String baseUrl = (String) connectorConfiguration.getProperties().getOrDefault("baseUrl", false);
        String baseUrlHealthEndpoint = (String) connectorConfiguration.getProperties()
                .getOrDefault("baseUrlHealthEndpoint", false);
        String authentication = (String) connectorConfiguration.getProperties().getOrDefault("authentication", false);
        String user = (String) connectorConfiguration.getProperties().get("user");
        String password = (String) connectorConfiguration.getProperties().get("password");
        String token = (String) connectorConfiguration.getProperties().get("token");
        String headerAccept = (String) connectorConfiguration.getProperties().get("headerAccept");

        // Create RestClient builder
        RestClient.Builder builder = RestClient.builder()
                .requestFactory(new HttpComponentsClientHttpRequestFactory())
                .baseUrl(baseUrl)
                .defaultHeader("Accept", headerAccept);

        // Add authentication if specified
        if ("Basic".equalsIgnoreCase(authentication) && user != null && password != null) {
            String credentials = Base64.getEncoder()
                    .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
            builder.defaultHeader("Authorization", "Basic " + credentials);
        } else if ("Bearer".equalsIgnoreCase(authentication) && password != null) {
            builder.defaultHeader("Authorization", "Bearer " + token);
        }

        // Build the client
        webhookClient = builder.build();

        // Create RestClient builder
        RestClient.Builder builderHealth = RestClient.builder()
                .requestFactory(new HttpComponentsClientHttpRequestFactory())
                .baseUrl(baseUrl)
                .defaultHeader("Accept", headerAccept);

        // Add authentication if specified
        if ("Basic".equalsIgnoreCase(authentication) && !StringUtils.isEmpty(user) &&!StringUtils.isEmpty(password)) {
            String credentials = Base64.getEncoder()
                    .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
            builderHealth.defaultHeader("Authorization", "Basic " + credentials);
        } else if ("Bearer".equalsIgnoreCase(authentication) && password != null) {
            builderHealth.defaultHeader("Authorization", "Bearer " + token);
        }

        // Build the client
        webhookClientHealth = builderHealth.build();




        String configuredProtocol = "mqtt";
        String configuredServerPath = "";
        if (webhookClient.getConfig().getWebSocketConfig().isPresent()) {
            if (webhookClient.getConfig().getSslConfig().isPresent()) {
                configuredProtocol = "wss";
            } else {
                configuredProtocol = "ws";
            }
            configuredServerPath = "/" + webhookClient.getConfig().getWebSocketConfig().get().getServerPath();
        } else {
            if (webhookClient.getConfig().getSslConfig().isPresent()) {
                configuredProtocol = "mqtts";
            } else {
                configuredProtocol = "mqtt";
            }
        }
        String configuredUrl = String.format("%s://%s:%s%s", configuredProtocol,
                webhookClient.getConfig().getServerHost(),
                webhookClient.getConfig().getServerPort(), configuredServerPath);
        // Registering Callback
        Mqtt3AsyncClient mqtt3AsyncClient = webhookClient.toAsync();
        webhookCallback = new WebhookCallback(dispatcher, tenant, getConnectorIdent(), false);
        mqtt3AsyncClient.publishes(MqttGlobalPublishFilter.ALL, webhookCallback);

        // stay in the loop until successful
        boolean successful = false;
        while (!successful) {
            loadConfiguration();
            var firstRun = true;
            while (!isConnected() && shouldConnect()) {
                log.info("Tenant {} - Trying to connect {} - phase II: (shouldConnect):{} {}", tenant,
                        getConnectorName(),
                        shouldConnect(), configuredUrl);
                if (!firstRun) {
                    try {
                        Thread.sleep(WAIT_PERIOD_MS);
                    } catch (InterruptedException e) {
                        // ignore errorMessage
                        // log.error("Tenant {} - Error on reconnect: {}", tenant, e.getMessage());
                    }
                }
                try {
                    Mqtt3ConnAck ack = webhookClient.connectWith()
                            .cleanSession(true)
                            .keepAlive(60)
                            .send();
                    if (!ack.getReturnCode().equals(Mqtt3ConnAckReturnCode.SUCCESS)) {

                        throw new ConnectorException(
                                String.format("Tenant %s - Error connecting to broker: %s. Errorcode: %s", tenant,
                                        webhookClient.getConfig().getServerHost(), ack.getReturnCode().name()));
                    }

                    connectionState.setTrue();
                    log.info("Tenant {} - Connected to broker {}", tenant,
                            webhookClient.getConfig().getServerHost());
                    updateConnectorStatusAndSend(ConnectorStatus.CONNECTED, true, true);
                    List<Mapping> updatedMappingsInbound = mappingComponent.rebuildMappingInboundCache(tenant);
                    updateActiveSubscriptionsInbound(updatedMappingsInbound, true);
                    List<Mapping> updatedMappingsOutbound = mappingComponent.rebuildMappingOutboundCache(tenant);
                    updateActiveSubscriptionsOutbound(updatedMappingsOutbound);

                } catch (Exception e) {
                    log.error("Tenant {} - Failed to connect to broker {}, {}, {}, {}", tenant,
                            webhookClient.getConfig().getServerHost(), e.getMessage(), connectionState.booleanValue(),
                            webhookClient.getState().isConnected());
                    updateConnectorStatusToFailed(e);
                    sendConnectorLifecycle();
                }
                firstRun = false;
            }

            try {
                // test if the mqtt connection is configured and enabled
                if (shouldConnect()) {
                    /*
                     * try {
                     * // is not working for broker.emqx.io
                     * subscribe("$SYS/#", QOS.AT_LEAST_ONCE);
                     * } catch (ConnectorException e) {
                     * log.warn(
                     * "Tenant {} - Error on subscribing to topic $SYS/#, this might not be supported by the mqtt broker {} {}"
                     * ,
                     * e.getMessage(), e);
                     * }
                     */
                    mappingComponent.rebuildMappingOutboundCache(tenant);
                    // in order to keep MappingInboundCache and ActiveSubscriptionMappingInbound in
                    // sync, the ActiveSubscriptionMappingInbound is build on the
                    // previously used updatedMappings
                }
                successful = true;
            } catch (Exception e) {
                log.error("Tenant {} - Error on reconnect, retrying ... {}: ", tenant, e.getMessage(), e);
                updateConnectorStatusToFailed(e);
                sendConnectorLifecycle();
                if (serviceConfiguration.logConnectorErrorInBackend) {
                    log.error("Tenant {} - Stacktrace: ", tenant, e);
                }
                successful = false;
            }
        }
    }

    @Override
    public void close() {
    }

    @Override
    public boolean isConfigValid(ConnectorConfiguration configuration) {
        if (configuration == null)
            return false;
        // if using self signed certificate additional properties have to be set
        Boolean useSelfSignedCertificate = (Boolean) configuration.getProperties()
                .getOrDefault("useSelfSignedCertificate", false);
        if (useSelfSignedCertificate && (configuration.getProperties().get("fingerprintSelfSignedCertificate") == null
                || configuration.getProperties().get("nameCertificate") == null)) {
            return false;
        }
        // check if all required properties are set
        for (String property : getConnectorSpecification().getProperties().keySet()) {
            if (getConnectorSpecification().getProperties().get(property).required
                    && configuration.getProperties().get(property) == null) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean isConnected() {
        return connectionState.booleanValue();
        // return mqttClient != null ? mqttClient.getState().isConnected() : false;
    }

    @Override
    public void disconnect() {
        if (isConnected()) {
            updateConnectorStatusAndSend(ConnectorStatus.DISCONNECTING, true, true);
            log.info("Tenant {} - Disconnecting from broker: {}", tenant,
                    (webhookClient == null ? (String) connectorConfiguration.getProperties().get("mqttHost")
                            : webhookClient.getConfig().getServerHost()));
            log.debug("Tenant {} - Disconnected from broker I: {}", tenant,
                    webhookClient.getConfig().getServerHost());
            activeSubscriptions.entrySet().forEach(entry -> {
                // only unsubscribe if still active subscriptions exist
                String topic = entry.getKey();
                MutableInt activeSubs = entry.getValue();
                if (activeSubs.intValue() > 0 && webhookClient.getState().isConnected()) {
                    webhookClient.unsubscribe(Mqtt3Unsubscribe.builder().topicFilter(topic).build());
                }
            });

            // if (mqttClient.getState().isConnected()) {
            // mqttClient.unsubscribe(Mqtt3Unsubscribe.builder().topicFilter("$SYS").build());
            // }

            try {
                if (webhookClient != null && webhookClient.getState().isConnected())
                    webhookClient.disconnect();
            } catch (Exception e) {
                log.error("Tenant {} - Error disconnecting from MQTT broker:", tenant,
                        e);
            }
            updateConnectorStatusAndSend(ConnectorStatus.DISCONNECTED, true, true);
            List<Mapping> updatedMappingsInbound = mappingComponent.rebuildMappingInboundCache(tenant);
            updateActiveSubscriptionsInbound(updatedMappingsInbound, true);
            List<Mapping> updatedMappingsOutbound = mappingComponent.rebuildMappingOutboundCache(tenant);
            updateActiveSubscriptionsOutbound(updatedMappingsOutbound);
            log.info("Tenant {} - Disconnected from MQTT broker II: {}", tenant,
                    webhookClient.getConfig().getServerHost());
        }
    }

    @Override
    public String getConnectorIdent() {
        return connectorIdentifier;
    }

    @Override
    public void subscribe(String topic, QOS qos) throws ConnectorException {
        log.debug("Tenant {} - Subscribing on topic: {} for connector {}", tenant, topic, connectorName);
        QOS usedQOS = qos;
        sendSubscriptionEvents(topic, "Subscribing");
        if (usedQOS.equals(null))
            usedQOS = QOS.AT_LEAST_ONCE;
        else if (!supportedQOS.contains(qos)) {
            // determine maximum supported QOS
            usedQOS = QOS.AT_LEAST_ONCE;
            for (int i = 1; i < qos.ordinal(); i++) {
                if (supportedQOS.contains(QOS.values()[i])) {
                    usedQOS = QOS.values()[i];
                }
            }
            if (usedQOS.ordinal() < qos.ordinal()) {
                log.warn("Tenant {} - QOS {} is not supported. Using instead: {}", tenant, qos, usedQOS);
            }
        }

        // We don't need to add a handler on subscribe using hive client
        Mqtt3AsyncClient asyncMqttClient = webhookClient.toAsync();
        asyncMqttClient.subscribeWith().topicFilter(topic).qos(MqttQos.fromCode(usedQOS.ordinal())).send()
                .thenRun(() -> {
                    log.info("Tenant {} - Successfully subscribed on topic: {} for connector {}", tenant, topic,
                            connectorName);
                }).exceptionally(throwable -> {
                    log.error("Tenant {} - Failed to subscribe on topic {} with error: ", tenant, topic,
                            throwable.getMessage());
                    return null;
                });
    }

    public void unsubscribe(String topic) throws Exception {
        throw new NotSupportedException("WebHook does not support inbound mappings");
    }

    public void publishMEAO(ProcessingContext<?> context) {
        C8YRequest currentRequest = context.getCurrentRequest();
        String payload = currentRequest.getRequest();
        MqttQos mqttQos = MqttQos.fromCode(context.getQos().ordinal());
        Mqtt3Publish mqttMessage = Mqtt3Publish.builder().topic(context.getResolvedPublishTopic()).qos(mqttQos)
                .payload(payload.getBytes()).build();
        webhookClient.publish(mqttMessage);

        log.info("Tenant {} - Published outbound message: {} for mapping: {} on topic: {}, {}", tenant, payload,
                context.getMapping().name, context.getResolvedPublishTopic(), connectorName);
    }

    @Override
    public String getConnectorName() {
        return connectorName;
    }

    @Override
    public Boolean supportsWildcardsInTopic() {
        return true;
    }

    @Override
    public void monitorSubscriptions() {
        // nothing to do
    }

    @Override
    public List<Direction> supportedDirections() {
        return new ArrayList<>(Arrays.asList(Direction.OUTBOUND));
    }

}