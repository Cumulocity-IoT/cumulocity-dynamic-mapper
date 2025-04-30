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

package dynamic.mapping.connector.mqtt;

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

import com.hivemq.client.mqtt.mqtt3.message.unsubscribe.Mqtt3Unsubscribe;
import dynamic.mapping.connector.core.ConnectorPropertyType;
import dynamic.mapping.connector.core.ConnectorSpecification;
import dynamic.mapping.connector.core.client.AConnectorClient;
import dynamic.mapping.connector.core.client.ConnectorException;
import dynamic.mapping.connector.core.client.ConnectorType;
import dynamic.mapping.model.Direction;
import dynamic.mapping.model.Mapping;
import dynamic.mapping.model.Qos;
import dynamic.mapping.processor.inbound.DispatcherInbound;
import dynamic.mapping.processor.model.C8YRequest;
import dynamic.mapping.processor.model.ProcessingContext;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.mutable.MutableInt;

import com.hivemq.client.mqtt.MqttClientSslConfig;
import com.hivemq.client.mqtt.MqttClientSslConfigBuilder;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;
import com.hivemq.client.mqtt.mqtt3.Mqtt3BlockingClient;
import com.hivemq.client.mqtt.mqtt3.Mqtt3Client;
import com.hivemq.client.mqtt.mqtt3.Mqtt3ClientBuilder;
import com.hivemq.client.mqtt.mqtt3.message.auth.Mqtt3SimpleAuth;
import com.hivemq.client.mqtt.mqtt3.message.auth.Mqtt3SimpleAuthBuilder.Complete;
import com.hivemq.client.mqtt.mqtt3.message.connect.connack.Mqtt3ConnAck;
import com.hivemq.client.mqtt.mqtt3.message.connect.connack.Mqtt3ConnAckReturnCode;
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import dynamic.mapping.configuration.ConnectorConfiguration;
import dynamic.mapping.connector.core.ConnectorProperty;
import dynamic.mapping.connector.core.ConnectorPropertyCondition;
import dynamic.mapping.core.ConfigurationRegistry;
import dynamic.mapping.core.ConnectorStatus;
import dynamic.mapping.core.ConnectorStatusEvent;

@Slf4j
public class MQTTClient extends AConnectorClient {
    public MQTTClient() {
        Map<String, ConnectorProperty> configProps = new HashMap<>();
        ConnectorPropertyCondition tlsCondition = new ConnectorPropertyCondition("protocol",
                new String[]{"mqtts://", "wss://"});
        ConnectorPropertyCondition useSelfSignedCertificateCondition = new ConnectorPropertyCondition(
                "useSelfSignedCertificate", new String[]{"true"});
        ConnectorPropertyCondition wsCondition = new ConnectorPropertyCondition("protocol",
                new String[]{"ws://", "wss://"});
        configProps.put("protocol",
                new ConnectorProperty(null, true, 0, ConnectorPropertyType.OPTION_PROPERTY, false, false, "mqtt://",
                        Map.ofEntries(
                                new AbstractMap.SimpleEntry<String, String>("mqtt://", "mqtt://"),
                                new AbstractMap.SimpleEntry<String, String>("mqtts://", "mqtts://"),
                                new AbstractMap.SimpleEntry<String, String>("ws://", "ws://"),
                                new AbstractMap.SimpleEntry<String, String>("wss://", "wss://")),
                        null));
        configProps.put("mqttHost",
                new ConnectorProperty(null, true, 1, ConnectorPropertyType.STRING_PROPERTY, false, false, null, null,
                        null));
        configProps.put("mqttPort",
                new ConnectorProperty(null, true, 2, ConnectorPropertyType.NUMERIC_PROPERTY, false, false, null, null,
                        null));
        configProps.put("user",
                new ConnectorProperty(null, false, 3, ConnectorPropertyType.STRING_PROPERTY, false, false, null, null,
                        null));
        configProps.put("password",
                new ConnectorProperty(null, false, 4, ConnectorPropertyType.SENSITIVE_STRING_PROPERTY, false, false,
                        null,
                        null, null));
        configProps.put("clientId",
                new ConnectorProperty(null, true, 5, ConnectorPropertyType.STRING_PROPERTY, false, false, null, null,
                        null));
        configProps.put("useSelfSignedCertificate",
                new ConnectorProperty(null, false, 6, ConnectorPropertyType.BOOLEAN_PROPERTY, false, false, false, null,
                        tlsCondition));
        configProps.put("fingerprintSelfSignedCertificate",
                new ConnectorProperty(null, false, 7, ConnectorPropertyType.STRING_PROPERTY, false, false, null, null,
                        useSelfSignedCertificateCondition));
        configProps.put("nameCertificate",
                new ConnectorProperty(null, false, 8, ConnectorPropertyType.STRING_PROPERTY, false, false, null, null,
                        useSelfSignedCertificateCondition));
        configProps.put("supportsWildcardInTopic",
                new ConnectorProperty(null, false, 9, ConnectorPropertyType.BOOLEAN_PROPERTY, false, false, true, null,
                        null));
        configProps.put("serverPath",
                new ConnectorProperty(null, false, 10, ConnectorPropertyType.STRING_PROPERTY, false, false, null, null,
                        wsCondition));
        String name = "Generic MQTT";
        String description = "Generic connector for connecting to external MQTT broker over tcp or websocket.";
        connectorType = ConnectorType.MQTT;
        connectorSpecification = new ConnectorSpecification(name, description, connectorType, configProps, false,
                supportedDirections());
    }

    public MQTTClient(ConfigurationRegistry configurationRegistry,
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
        this.virtualThreadPool = configurationRegistry.getVirtualThreadPool();
        this.objectMapper = configurationRegistry.getObjectMapper();
        this.additionalSubscriptionIdTest = additionalSubscriptionIdTest;
        this.mappingServiceRepresentation = configurationRegistry.getMappingServiceRepresentations().get(tenant);
        this.serviceConfiguration = configurationRegistry.getServiceConfigurations().get(tenant);
        this.dispatcher = dispatcher;
        this.tenant = tenant;
        this.supportedQOS = Arrays.asList(Qos.AT_LEAST_ONCE, Qos.AT_MOST_ONCE, Qos.EXACTLY_ONCE);
        // set qQoS to default QoS
        //this.qos = QOS.AT_LEAST_ONCE;
    }

    protected AConnectorClient.Certificate cert;

    protected MqttClientSslConfig sslConfig;

    protected MQTTCallback mqttCallback = null;

    protected Mqtt3BlockingClient mqttClient;

    @Getter
    protected List<Qos> supportedQOS;

    public boolean initialize() {
        loadConfiguration();
        Boolean useSelfSignedCertificate = (Boolean) connectorConfiguration.getProperties()
                .getOrDefault("useSelfSignedCertificate", false);
        log.debug("Tenant {} - Testing connector for useSelfSignedCertificate: {} ", tenant, useSelfSignedCertificate);
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
                // https://github.com/micronaut-projects/micronaut-mqtt/blob/ac2720937871b8907ad429f7ea5b8b4664a0776e/mqtt-hivemq/src/main/java/io/micronaut/mqtt/hivemq/v3/client/Mqtt3ClientFactory.java#L118
                // and https://hivemq.github.io/hivemq-mqtt-client/docs/client-configuration/
                List<String> expectedProtocols = Arrays.asList("TLSv1.2");
                sslConfig = sslConfigBuilder.trustManagerFactory(tmf).protocols(expectedProtocols).build();
            } catch (NoSuchAlgorithmException | CertificateException | IOException | KeyStoreException
                     | KeyManagementException e) {
                log.error("Tenant {} - Connector {} - Exception when configuring socketFactory for TLS: ", tenant,
                        getConnectorName(), e);
                updateConnectorStatusToFailed(e);
                sendConnectorLifecycle();
                return false;
            } catch (Exception e) {
                log.error("Tenant {} - Connector {} - Exception when initializing connector: ", tenant,
                        getConnectorName(), e);
                updateConnectorStatusToFailed(e);
                sendConnectorLifecycle();
                return false;
            }
        }
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
        String protocol = (String) connectorConfiguration.getProperties().getOrDefault("protocol", false);
        boolean useSelfSignedCertificate = (Boolean) connectorConfiguration.getProperties()
                .getOrDefault("useSelfSignedCertificate", false);

        String mqttHost = (String) connectorConfiguration.getProperties().get("mqttHost");
        String clientId = (String) connectorConfiguration.getProperties().get("clientId");
        int mqttPort = (Integer) connectorConfiguration.getProperties().get("mqttPort");
        String user = (String) connectorConfiguration.getProperties().get("user");
        String password = (String) connectorConfiguration.getProperties().get("password");

        Mqtt3ClientBuilder partialBuilder;
        partialBuilder = Mqtt3Client.builder().serverHost(mqttHost).serverPort(mqttPort)
                .identifier(clientId + additionalSubscriptionIdTest);

        // is username & password used
        if (!StringUtils.isEmpty(user)) {
            Complete simpleAuthComplete = Mqtt3SimpleAuth.builder().username(user);
            if (!StringUtils.isEmpty(password)) {
                simpleAuthComplete = simpleAuthComplete.password(password.getBytes());
            }
            partialBuilder = partialBuilder
                    .simpleAuth(simpleAuthComplete.build());
        }

        // tls configuration
        if (useSelfSignedCertificate) {
            partialBuilder = partialBuilder.sslConfig(sslConfig);
            log.debug("Tenant {} - Using certificate: {}", tenant, cert.getCertInPemFormat());
        } else if ("mqtts://".equals(protocol) || "wss://".equals(protocol)) {
            partialBuilder = partialBuilder.sslWithDefaultConfig();
        }

        // websocket configuration
        if ("ws://".equals(protocol) || "wss://".equals(protocol)) {
            partialBuilder = partialBuilder.webSocketWithDefaultConfig();
            String serverPath = (String) connectorConfiguration.getProperties().get("serverPath");
            if (serverPath != null) {
                partialBuilder = partialBuilder.webSocketConfig()
                        .serverPath(serverPath)
                        .applyWebSocketConfig();
            }
            log.debug("Tenant {} - Using websocket: {}", tenant, serverPath);
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

        String configuredProtocol = "mqtt";
        String configuredServerPath = "";
        if (mqttClient.getConfig().getWebSocketConfig().isPresent()) {
            if (mqttClient.getConfig().getSslConfig().isPresent()) {
                configuredProtocol = "wss";
            } else {
                configuredProtocol = "ws";
            }
            configuredServerPath = "/" + mqttClient.getConfig().getWebSocketConfig().get().getServerPath();
        } else {
            if (mqttClient.getConfig().getSslConfig().isPresent()) {
                configuredProtocol = "mqtts";
            } else {
                configuredProtocol = "mqtt";
            }
        }
        String configuredUrl = String.format("%s://%s:%s%s", configuredProtocol, mqttClient.getConfig().getServerHost(),
                mqttClient.getConfig().getServerPort(), configuredServerPath);
        // Registering Callback
        // Mqtt3AsyncClient mqtt3AsyncClient = mqttClient.toAsync();
        mqttCallback = new MQTTCallback(tenant, configurationRegistry, dispatcher, getConnectorIdentifier(), false);

        // stay in the loop until successful
        boolean successful = false;
        while (!successful) {
            if (Thread.currentThread().isInterrupted())
                return;
            loadConfiguration();
            var firstRun = true;
            while (!isConnected() && shouldConnect()) {
                if (Thread.currentThread().isInterrupted())
                    return;
                log.info("Tenant {} - Trying to connect {} - phase II: (shouldConnect):{} {}", tenant,
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
                    Mqtt3ConnAck ack = mqttClient.connectWith()
                            .cleanSession(true)
                            .keepAlive(60)
                            .send();
                    if (!ack.getReturnCode().equals(Mqtt3ConnAckReturnCode.SUCCESS)) {

                        throw new ConnectorException(
                                String.format("Tenant %s - Error connecting to broker: %s. Error code: %s", tenant,
                                        mqttClient.getConfig().getServerHost(), ack.getReturnCode().name()));
                    }

                    connectionState.setTrue();
                    log.info("Tenant {} - Connected to broker {}", tenant,
                            mqttClient.getConfig().getServerHost());
                    updateConnectorStatusAndSend(ConnectorStatus.CONNECTED, true, true);
                    List<Mapping> updatedMappingsInbound = mappingComponent.rebuildMappingInboundCache(tenant);
                    updateActiveSubscriptionsInbound(updatedMappingsInbound, true);
                    List<Mapping> updatedMappingsOutbound = mappingComponent.rebuildMappingOutboundCache(tenant);
                    updateActiveSubscriptionsOutbound(updatedMappingsOutbound);

                } catch (Exception e) {
                    if (e instanceof InterruptedException || e instanceof RuntimeException)
                        return;
                    log.error("Tenant {} - Failed to connect to broker {}, {}, {}, {}", tenant,
                            mqttClient.getConfig().getServerHost(), e.getMessage(), connectionState.booleanValue(),
                            mqttClient.getState().isConnected());
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
                    (mqttClient == null ? (String) connectorConfiguration.getProperties().get("mqttHost")
                            : mqttClient.getConfig().getServerHost()));
            log.debug("Tenant {} - Disconnected from broker I: {}", tenant,
                    mqttClient.getConfig().getServerHost());
            activeSubscriptionsInbound.entrySet().forEach(entry -> {
                // only unsubscribe if still active subscriptions exist
                String topic = entry.getKey();
                MutableInt activeSubs = entry.getValue();
                if (activeSubs.intValue() > 0 && mqttClient.getState().isConnected()) {
                    mqttClient.unsubscribe(Mqtt3Unsubscribe.builder().topicFilter(topic).build());
                }
            });

            // if (mqttClient.getState().isConnected()) {
            // mqttClient.unsubscribe(Mqtt3Unsubscribe.builder().topicFilter("$SYS").build());
            // }

            try {
                if (mqttClient != null && mqttClient.getState().isConnected())
                    mqttClient.disconnect();
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
                    mqttClient.getConfig().getServerHost());
        }
    }

    @Override
    public String getConnectorIdentifier() {
        return connectorIdentifier;
    }

    @Override
    public void subscribe(String topic, Qos qos) throws ConnectorException {
        log.debug("Tenant {} - Subscribing on topic: {} for connector {}", tenant, topic, connectorName);
        Qos usedQOS = qos;
        sendSubscriptionEvents(topic, "Subscribing");
        //Default to QoS=0 if not provided
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
                log.warn("Tenant {} - QOS {} is not supported. Using instead: {}", tenant, qos, usedQOS);
            }
        }

        // We don't need to add a handler on subscribe using hive client
        Mqtt3AsyncClient asyncMqttClient = mqttClient.toAsync();
        asyncMqttClient.subscribeWith().topicFilter(topic).qos(MqttQos.fromCode(usedQOS.ordinal()))
                .callback(mqttCallback).manualAcknowledgement(true)
                .send()
                .exceptionally(throwable -> {
                    log.error("Tenant {} - Failed to subscribe on topic {} with error: ", tenant, topic,
                            throwable.getMessage());
                    return null;
                });

    }

    public void unsubscribe(String topic) throws Exception {
        log.debug("Tenant {} - Unsubscribing from topic: {}", tenant, topic);
        sendSubscriptionEvents(topic, "Unsubscribing");
        Mqtt3AsyncClient asyncMqttClient = mqttClient.toAsync();
        asyncMqttClient.unsubscribe(Mqtt3Unsubscribe.builder().topicFilter(topic).build()).thenRun(() -> {
            log.info("Tenant {} - Successfully unsubscribed from topic: {} for connector {}", tenant, topic,
                    connectorName);
        }).exceptionally(throwable -> {
            log.error("Tenant {} - Failed to subscribe on topic {} with error: ", tenant, topic,
                    throwable.getMessage());
            return null;
        });
        // mqttClient.unsubscribe(Mqtt3Unsubscribe.builder().topicFilter(topic).build());
    }

    public void publishMEAO(ProcessingContext<?> context) {
        C8YRequest currentRequest = context.getCurrentRequest();
        String payload = currentRequest.getRequest();
        MqttQos mqttQos = MqttQos.fromCode(context.getQos().ordinal());
        Mqtt3Publish mqttMessage = Mqtt3Publish.builder().topic(context.getResolvedPublishTopic()).qos(mqttQos)
                .payload(payload.getBytes()).build();
        mqttClient.publish(mqttMessage);

        if (context.getMapping().getDebug() || context.getServiceConfiguration().logPayload) {
            log.info("Tenant {} - Published outbound message: {} for mapping: {} on topic: {}, {}", tenant, payload,
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