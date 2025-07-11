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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.TrustManagerFactory;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.mutable.MutableInt;

import com.hivemq.client.mqtt.MqttClientSslConfig;
import com.hivemq.client.mqtt.MqttClientSslConfigBuilder;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient;
import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient;
import com.hivemq.client.mqtt.mqtt5.Mqtt5Client;
import com.hivemq.client.mqtt.mqtt5.Mqtt5ClientBuilder;
import com.hivemq.client.mqtt.mqtt5.message.auth.Mqtt5SimpleAuth;
import com.hivemq.client.mqtt.mqtt5.message.auth.Mqtt5SimpleAuthBuilder.Complete;
import com.hivemq.client.mqtt.mqtt5.message.connect.connack.Mqtt5ConnAck;
import com.hivemq.client.mqtt.mqtt5.message.connect.connack.Mqtt5ConnAckReasonCode;
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish;
import com.hivemq.client.mqtt.mqtt5.message.unsubscribe.Mqtt5Unsubscribe;

import dynamic.mapper.configuration.ConnectorConfiguration;
import dynamic.mapper.configuration.ConnectorId;
import dynamic.mapper.connector.core.ConnectorProperty;
import dynamic.mapper.connector.core.ConnectorPropertyCondition;
import dynamic.mapper.connector.core.ConnectorPropertyType;
import dynamic.mapper.connector.core.ConnectorSpecification;
import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.connector.core.client.ConnectorException;
import dynamic.mapper.connector.core.client.ConnectorType;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.core.ConnectorStatus;
import dynamic.mapper.core.ConnectorStatusEvent;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.Qos;
import dynamic.mapper.processor.inbound.DispatcherInbound;
import dynamic.mapper.processor.model.C8YRequest;
import dynamic.mapper.processor.model.ProcessingContext;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MQTT5Client extends AConnectorClient {
    private static final int KEEP_ALIVE = 60;

    public MQTT5Client() {
        Map<String, ConnectorProperty> configProps = new HashMap<>();
        ConnectorPropertyCondition tlsCondition = new ConnectorPropertyCondition("protocol",
                new String[] { AConnectorClient.MQTT_PROTOCOL_MQTTS, AConnectorClient.MQTT_PROTOCOL_WSS });
        ConnectorPropertyCondition useSelfSignedCertificateCondition = new ConnectorPropertyCondition(
                "useSelfSignedCertificate", new String[] { "true" });
        ConnectorPropertyCondition wsCondition = new ConnectorPropertyCondition("protocol",
                new String[] { AConnectorClient.MQTT_PROTOCOL_WS, AConnectorClient.MQTT_PROTOCOL_WSS });
        configProps.put("version",
                new ConnectorProperty(null, true, 0, ConnectorPropertyType.OPTION_PROPERTY, false, false,
                        MQTT_VERSION_3_1_1,
                        Map.ofEntries(
                                new AbstractMap.SimpleEntry<String, String>(MQTT_VERSION_3_1_1, MQTT_VERSION_3_1_1),
                                new AbstractMap.SimpleEntry<String, String>(MQTT_VERSION_5_0, MQTT_VERSION_5_0)),
                        null));
        configProps.put("protocol",
                new ConnectorProperty(null, true, 1, ConnectorPropertyType.OPTION_PROPERTY, false, false,
                        AConnectorClient.MQTT_PROTOCOL_MQTT,
                        Map.ofEntries(
                                new AbstractMap.SimpleEntry<String, String>(AConnectorClient.MQTT_PROTOCOL_MQTT,
                                        AConnectorClient.MQTT_PROTOCOL_MQTT),
                                new AbstractMap.SimpleEntry<String, String>(AConnectorClient.MQTT_PROTOCOL_MQTTS,
                                        AConnectorClient.MQTT_PROTOCOL_MQTTS),
                                new AbstractMap.SimpleEntry<String, String>(AConnectorClient.MQTT_PROTOCOL_WS,
                                        AConnectorClient.MQTT_PROTOCOL_WS),
                                new AbstractMap.SimpleEntry<String, String>(AConnectorClient.MQTT_PROTOCOL_WSS,
                                        AConnectorClient.MQTT_PROTOCOL_WSS)),
                        null));
        configProps.put("mqttHost",
                new ConnectorProperty(null, true, 2, ConnectorPropertyType.STRING_PROPERTY, false, false, null, null,
                        null));
        configProps.put("mqttPort",
                new ConnectorProperty(null, true, 3, ConnectorPropertyType.NUMERIC_PROPERTY, false, false, null, null,
                        null));
        configProps.put("user",
                new ConnectorProperty(null, false, 4, ConnectorPropertyType.STRING_PROPERTY, false, false, null, null,
                        null));
        configProps.put("password",
                new ConnectorProperty(null, false, 5, ConnectorPropertyType.SENSITIVE_STRING_PROPERTY, false, false,
                        null,
                        null, null));
        configProps.put("clientId",
                new ConnectorProperty(null, true, 6, ConnectorPropertyType.STRING_PROPERTY, false, false, null, null,
                        null));
        configProps.put("useSelfSignedCertificate",
                new ConnectorProperty(null, false, 7, ConnectorPropertyType.BOOLEAN_PROPERTY, false, false, false, null,
                        tlsCondition));
        configProps.put("fingerprintSelfSignedCertificate",
                new ConnectorProperty(null, false, 8, ConnectorPropertyType.STRING_PROPERTY, false, false, null, null,
                        useSelfSignedCertificateCondition));
        configProps.put("nameCertificate",
                new ConnectorProperty(null, false, 9, ConnectorPropertyType.STRING_PROPERTY, false, false, null, null,
                        useSelfSignedCertificateCondition));
        configProps.put("supportsWildcardInTopic",
                new ConnectorProperty(null, false, 10, ConnectorPropertyType.BOOLEAN_PROPERTY, false, false, true, null,
                        null));
        configProps.put("serverPath",
                new ConnectorProperty(null, false, 11, ConnectorPropertyType.STRING_PROPERTY, false, false, null, null,
                        wsCondition));
        configProps.put("cleanSession",
                new ConnectorProperty(null, false, 12, ConnectorPropertyType.BOOLEAN_PROPERTY, false, false, true, null,
                        null));
        String name = "Generic MQTT";
        String description = "Connector for connecting to external MQTT broker over tcp or websocket.";
        connectorType = ConnectorType.MQTT;
        connectorSpecification = new ConnectorSpecification(name, description, connectorType, configProps, false,
                supportedDirections());
    }

    public MQTT5Client(ConfigurationRegistry configurationRegistry,
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
        this.connectorId = new ConnectorId(connectorConfiguration.name, connectorConfiguration.identifier,
                connectorType);
        this.connectorStatus = ConnectorStatusEvent.unknown(connectorConfiguration.name,
                connectorConfiguration.identifier);
        // this.connectorType = connectorConfiguration.connectorType;
        this.c8yAgent = configurationRegistry.getC8yAgent();
        this.virtualThreadPool = configurationRegistry.getVirtualThreadPool();
        this.objectMapper = configurationRegistry.getObjectMapper();
        this.additionalSubscriptionIdTest = additionalSubscriptionIdTest;
        this.mapperServiceRepresentation = configurationRegistry.getMapperServiceRepresentation(tenant);
        this.serviceConfiguration = configurationRegistry.getServiceConfiguration(tenant);
        this.dispatcher = dispatcher;
        this.tenant = tenant;
        this.supportedQOS = Arrays.asList(Qos.AT_LEAST_ONCE, Qos.AT_MOST_ONCE, Qos.EXACTLY_ONCE);
        // set qQoS to default QoS
        // this.qos = QOS.AT_LEAST_ONCE;
    }

    protected AConnectorClient.Certificate cert;

    protected MqttClientSslConfig sslConfig;

    protected MQTT5Callback mqttCallback = null;

    protected Mqtt5BlockingClient mqttClient;

    protected Boolean cleanSession = false;

    @Getter
    protected List<Qos> supportedQOS;

    public boolean initialize() {
        loadConfiguration();
        Boolean useSelfSignedCertificate = (Boolean) connectorConfiguration.getProperties()
                .getOrDefault("useSelfSignedCertificate", false);
        log.debug("{} - Testing connector for useSelfSignedCertificate: {} ", tenant, useSelfSignedCertificate);
        if (useSelfSignedCertificate) {
            try {
                String nameCertificate = (String) connectorConfiguration.getProperties().get("nameCertificate");
                String fingerprint = (String) connectorConfiguration.getProperties()
                        .get("fingerprintSelfSignedCertificate");
                if (nameCertificate == null || fingerprint == null) {
                    throw new Exception(
                            "Required properties nameCertificate, fingerprint are not set. Please update the connector configuration!");
                }
                cert = c8yAgent.loadCertificateByName(nameCertificate, fingerprint, tenant, getConnectorName());
                if (cert == null) {
                    String errorMessage = String.format(
                            "Required certificate %s with fingerprint %s not found. Please update trusted certificates in the Cumulocity Device Management!",
                            nameCertificate, fingerprint);
                    throw new Exception(errorMessage);
                }
                KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
                trustStore.load(null, null);
                trustStore.setCertificateEntry("Custom CA",
                        (X509Certificate) CertificateFactory.getInstance("X509")
                                .generateCertificate(new ByteArrayInputStream(
                                        cert.getCertInPemFormat().getBytes(Charset.defaultCharset()))));
                TrustManagerFactory tmf = TrustManagerFactory
                        .getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(trustStore);
                // TrustManager[] trustManagers = tmf.getTrustManagers();
                // SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
                // sslContext.init(null, trustManagers, null);
                // sslSocketFactory = sslContext.getSocketFactory();
                MqttClientSslConfigBuilder sslConfigBuilder = MqttClientSslConfig.builder();
                // use sample from
                // https://github.com/micronaut-projects/micronaut-mqtt/blob/ac2720937871b8907ad429f7ea5b8b4664a0776e/mqtt-hivemq/src/main/java/io/micronaut/mqtt/hivemq/v3/client/Mqtt5ClientFactory.java#L118
                // and https://hivemq.github.io/hivemq-mqtt-client/docs/client-configuration/
                List<String> expectedProtocols = Arrays.asList("TLSv1.2");
                sslConfig = sslConfigBuilder.trustManagerFactory(tmf).protocols(expectedProtocols).build();
            } catch (NoSuchAlgorithmException | CertificateException | IOException | KeyStoreException
                    | KeyManagementException e) {
                log.error("{} - {} - Error configuring socketFactory for TLS: ", tenant,
                        getConnectorName(), e);
                updateConnectorStatusToFailed(e);
                sendConnectorLifecycle();
                return false;
            } catch (Exception e) {
                log.error("{} - {} - Error initializing connector: ", tenant,
                        getConnectorName(), e);
                updateConnectorStatusToFailed(e);
                sendConnectorLifecycle();
                return false;
            }
        }
        log.info("{} - Phase 0: {} initialized, connectorType: {}", tenant,
                getConnectorType(),
                getConnectorName());
        return true;
    }

    @Override
    public void connect() {
        log.info("{} - Phase I: {} connecting, isConnected: {}, shouldConnect: {}",
                tenant, getConnectorName(), isConnected(),
                shouldConnect());
        if (isConnected())
            disconnect();

        if (shouldConnect())
            updateConnectorStatusAndSend(ConnectorStatus.CONNECTING, true, shouldConnect());
        String protocol = (String) connectorConfiguration.getProperties().getOrDefault("protocol",
                AConnectorClient.MQTT_PROTOCOL_MQTT);
        boolean useSelfSignedCertificate = (Boolean) connectorConfiguration.getProperties()
                .getOrDefault("useSelfSignedCertificate", false);

        String mqttHost = (String) connectorConfiguration.getProperties().get("mqttHost");
        String clientId = (String) connectorConfiguration.getProperties().get("clientId");
        int mqttPort = (Integer) connectorConfiguration.getProperties().get("mqttPort");
        String user = (String) connectorConfiguration.getProperties().get("user");
        String password = (String) connectorConfiguration.getProperties().get("password");
        cleanSession = (Boolean) connectorConfiguration.getProperties().get("cleanSession");

        Mqtt5ClientBuilder partialBuilder;
        partialBuilder = Mqtt5Client.builder().serverHost(mqttHost).serverPort(mqttPort)
                .identifier(clientId + additionalSubscriptionIdTest);

        // is username & password used
        if (!StringUtils.isEmpty(user)) {
            Complete simpleAuthComplete = Mqtt5SimpleAuth.builder().username(user);
            if (!StringUtils.isEmpty(password)) {
                simpleAuthComplete = simpleAuthComplete.password(password.getBytes());
            }
            partialBuilder = partialBuilder
                    .simpleAuth(simpleAuthComplete.build());
        }

        // tls configuration
        if (useSelfSignedCertificate) {
            partialBuilder = partialBuilder.sslConfig(sslConfig);
            log.debug("{} - Using certificate: {}", tenant, cert.getCertInPemFormat());
        } else if (AConnectorClient.MQTT_PROTOCOL_MQTTS.equals(protocol)
                || AConnectorClient.MQTT_PROTOCOL_WSS.equals(protocol)) {
            partialBuilder = partialBuilder.sslWithDefaultConfig();
        }

        // websocket configuration
        if (AConnectorClient.MQTT_PROTOCOL_WS.equals(protocol) || AConnectorClient.MQTT_PROTOCOL_WSS.equals(protocol)) {
            partialBuilder = partialBuilder.webSocketWithDefaultConfig();
            String serverPath = (String) connectorConfiguration.getProperties().get("serverPath");
            if (serverPath != null) {
                partialBuilder = partialBuilder.webSocketConfig()
                        .serverPath(serverPath)
                        .applyWebSocketConfig();
            }
            log.debug("{} - Using websocket: {}", tenant, serverPath);
        }

        // finally build mqttClient
        mqttClient = partialBuilder
                .addDisconnectedListener(context -> {
                    // test if we closed the connection deliberately, otherwise we have to try to
                    // reconnect
                    connectionState.setFalse();
                    if (connectorConfiguration.enabled) {
                        try {
                            connectionLost("Disconnected from " + context.getSource().toString(), context.getCause());
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                })
                .addConnectedListener(connext -> {
                    connectionState.setTrue();
                })
                .buildBlocking();

        String configuredServerPath = "";
        if (mqttClient.getConfig().getWebSocketConfig().isPresent()) {
            configuredServerPath = "/" + mqttClient.getConfig().getWebSocketConfig().get().getServerPath();
        }
        String configuredUrl = String.format("%s//%s:%s%s", protocol, mqttClient.getConfig().getServerHost(),
                mqttClient.getConfig().getServerPort(), configuredServerPath);
        // Registering Callback
        // Mqtt5AsyncClient mqtt3AsyncClient = mqttClient.toAsync();
        mqttCallback = new MQTT5Callback(tenant, configurationRegistry, dispatcher, getConnectorIdentifier(),
                getConnectorIdentifier(), false);

        // stay in the loop until successful
        boolean successful = false;
        while (!successful) {
            if (Thread.currentThread().isInterrupted())
                return;
            loadConfiguration();
            var firstRun = true;
            var mappingOutboundCacheRebuild = false;
            while (!isConnected() && shouldConnect()) {
                if (Thread.currentThread().isInterrupted())
                    return;
                log.info("{} - Phase II: {} connecting, shouldConnect: {}, server: {}", tenant,
                        getConnectorName(),
                        shouldConnect(), configuredUrl);
                if (!firstRun) {
                    try {
                        Thread.sleep(WAIT_PERIOD_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                try {
                    Mqtt5ConnAck ack = mqttClient.connectWith()
                            .cleanStart(cleanSession != null ? cleanSession : true)
                            .keepAlive(KEEP_ALIVE)
                            .send();
                    if (!ack.getReasonCode().equals(Mqtt5ConnAckReasonCode.SUCCESS)) {

                        throw new ConnectorException(
                                String.format("Tenant %s - Error connecting to server: %s. Error code: %s", tenant,
                                        mqttClient.getConfig().getServerHost(), ack.getReasonCode().name()));
                    }

                    connectionState.setTrue();
                    log.info("{} - Phase III: {} connected, serverHost: {}", tenant, getConnectorName(),
                            mqttClient.getConfig().getServerHost());
                    updateConnectorStatusAndSend(ConnectorStatus.CONNECTED, true, true);
                    List<Mapping> updatedMappingsInbound = mappingComponent.rebuildMappingInboundCache(tenant,
                            connectorId);
                    initializeSubscriptionsInbound(updatedMappingsInbound, true, cleanSession);
                    List<Mapping> updatedMappingsOutbound = mappingComponent.rebuildMappingOutboundCache(tenant,
                            connectorId);
                    mappingOutboundCacheRebuild = true;
                    initializeSubscriptionsOutbound(updatedMappingsOutbound);

                } catch (Exception e) {
                    if (e instanceof InterruptedException || e instanceof RuntimeException) {
                        log.error("{} - Phase III: {} interrupted while connecting to server: {}, {}, {}, {}",
                                tenant, getConnectorName(), mqttClient.getConfig().getServerHost(),
                                e.getMessage(), connectionState.booleanValue(), mqttClient.getState().isConnected(), e);
                        return;
                    }
                    log.error("{} - Phase III: {} failed to connect to server {}, {}, {}, {}", tenant,
                            mqttClient.getConfig().getServerHost(), e.getMessage(), connectionState.booleanValue(),
                            mqttClient.getState().isConnected(), e);
                    updateConnectorStatusToFailed(e);
                    sendConnectorLifecycle();
                }
                firstRun = false;
            }

            if (!mappingOutboundCacheRebuild) {
                mappingComponent.rebuildMappingOutboundCache(tenant, connectorId);
            }
            successful = true;
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
            log.info("{} - Disconnecting from broker: {}", tenant,
                    (mqttClient == null ? (String) connectorConfiguration.getProperties().get("mqttHost")
                            : mqttClient.getConfig().getServerHost()));
            log.debug("{} - Disconnected from broker I: {}", tenant,
                    mqttClient.getConfig().getServerHost());
            countSubscriptionsPerTopicInbound.entrySet().forEach(entry -> {
                // only unsubscribe if still active subscriptions exist
                String topic = entry.getKey();
                MutableInt activeSubs = entry.getValue();
                if (activeSubs.intValue() > 0 && mqttClient.getState().isConnected()) {
                    mqttClient.unsubscribe(Mqtt5Unsubscribe.builder().topicFilter(topic).build());
                }
            });

            // if (mqttClient.getState().isConnected()) {
            // mqttClient.unsubscribe(Mqtt5Unsubscribe.builder().topicFilter("$SYS").build());
            // }

            try {
                if (mqttClient != null && mqttClient.getState().isConnected())
                    mqttClient.disconnect();
            } catch (Exception e) {
                log.error("{} - Error disconnecting from MQTT broker:", tenant,
                        e);
            }
            updateConnectorStatusAndSend(ConnectorStatus.DISCONNECTED, true, true);
            List<Mapping> updatedMappingsInbound = mappingComponent.rebuildMappingInboundCache(tenant, connectorId);
            initializeSubscriptionsInbound(updatedMappingsInbound, true, cleanSession);
            List<Mapping> updatedMappingsOutbound = mappingComponent.rebuildMappingOutboundCache(tenant, connectorId);
            initializeSubscriptionsOutbound(updatedMappingsOutbound);
            log.info("{} - Disconnected from MQTT broker II: {}", tenant,
                    mqttClient.getConfig().getServerHost());
        }
    }

    @Override
    public String getConnectorIdentifier() {
        return connectorIdentifier;
    }

    @Override
    public void subscribe(String topic, Qos qos) throws ConnectorException {
        log.debug("{} - Subscribing on topic: [{}] for connector: {}", tenant, topic, connectorName);
        Qos usedQOS = qos;
        sendSubscriptionEvents(topic, "Subscribing");
        // Default to QoS=0 if not provided
        if (usedQOS.equals(null))
            usedQOS = Qos.AT_MOST_ONCE;
        else if (!supportedQOS.contains(qos)) {
            // determine maximum supported QOS
            usedQOS = Qos.AT_MOST_ONCE;
            for (int i = 1; i < qos.ordinal(); i++) {
                if (supportedQOS.contains(Qos.values()[i])) {
                    usedQOS = Qos.values()[i];
                }
            }
            if (usedQOS.ordinal() < qos.ordinal()) {
                log.warn("{} - QOS {} is not supported. Using instead: {}", tenant, qos, usedQOS);
            }
        }

        // We don't need to add a handler on subscribe using hive client
        Mqtt5AsyncClient asyncMqttClient = mqttClient.toAsync();
        asyncMqttClient.subscribeWith().topicFilter(topic).qos(MqttQos.fromCode(usedQOS.ordinal()))
                .callback(mqttCallback)
                .manualAcknowledgement(true)
                .send()
                .exceptionally(throwable -> {
                    log.error("{} - Failed to subscribe on topic {} with error: ", tenant, topic,
                            throwable.getMessage());
                    return null;
                });

    }

    public void unsubscribe(String topic) throws Exception {
        log.debug("{} - Unsubscribing from topic: [{}]", tenant, topic);
        sendSubscriptionEvents(topic, "Unsubscribing");
        Mqtt5AsyncClient asyncMqttClient = mqttClient.toAsync();
        asyncMqttClient.unsubscribe(Mqtt5Unsubscribe.builder().topicFilter(topic).build()).thenRun(() -> {
            log.info("{} - Successfully unsubscribed from topic: [{}] for connector: {}", tenant, topic,
                    connectorName);
        }).exceptionally(throwable -> {
            log.error("{} - Failed to subscribe on topic {} with error: ", tenant, topic,
                    throwable.getMessage());
            return null;
        });
    }

    public void publishMEAO(ProcessingContext<?> context) {
        C8YRequest currentRequest = context.getCurrentRequest();
        String payload = currentRequest.getRequest();
        MqttQos mqttQos = MqttQos.fromCode(context.getQos().ordinal());
        Mqtt5Publish mqttMessage = Mqtt5Publish.builder().topic(context.getResolvedPublishTopic()).qos(mqttQos)
                .payload(payload.getBytes()).build();
        mqttClient.publish(mqttMessage);

        if (context.getMapping().getDebug() || context.getServiceConfiguration().logPayload) {
            log.info("{} - Published outbound message: {} for mapping: {} on topic: [{}], {}", tenant, payload,
                    context.getMapping().name, context.getResolvedPublishTopic(), connectorName);
        }
    }

    @Override
    public String getConnectorName() {
        return connectorName;
    }

    @Override
    public Boolean supportsWildcardsInTopic() {
        return Boolean.parseBoolean(connectorConfiguration.getProperties().get("supportsWildcardInTopic").toString());
    }

    @Override
    public void monitorSubscriptions() {
        // nothing to do
    }

    @Override
    public List<Direction> supportedDirections() {
        return new ArrayList<>(Arrays.asList(Direction.INBOUND, Direction.OUTBOUND));
    }

}