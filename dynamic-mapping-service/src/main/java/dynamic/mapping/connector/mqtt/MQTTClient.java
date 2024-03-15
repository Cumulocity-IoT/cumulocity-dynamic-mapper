/*
 * Copyright (c) 2022 Software AG, Darmstadt, Germany and/or Software AG USA Inc., Reston, VA, USA,
 * and/or its subsidiaries and/or its affiliates and/or their licensors.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @authors Christof Strack, Stefan Witschel
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.validation.constraints.NotNull;

import dynamic.mapping.connector.core.ConnectorPropertyType;
import dynamic.mapping.connector.core.ConnectorSpecification;
import dynamic.mapping.connector.core.client.AConnectorClient;
import dynamic.mapping.connector.core.client.ConnectorException;
import dynamic.mapping.model.Mapping;
import dynamic.mapping.processor.inbound.AsynchronousDispatcherInbound;
import dynamic.mapping.processor.model.C8YRequest;
import dynamic.mapping.processor.model.ProcessingContext;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.mutable.MutableInt;
import org.springframework.beans.BeanInstantiationException;

import com.hivemq.client.internal.mqtt.message.MqttMessage;
import com.hivemq.client.mqtt.MqttClientSslConfig;
import com.hivemq.client.mqtt.MqttClientSslConfigBuilder;
import com.hivemq.client.mqtt.MqttGlobalPublishFilter;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.lifecycle.MqttClientAutoReconnect;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;
import com.hivemq.client.mqtt.mqtt3.Mqtt3BlockingClient;
import com.hivemq.client.mqtt.mqtt3.Mqtt3Client;
import com.hivemq.client.mqtt.mqtt3.Mqtt3ClientConfig;
import com.hivemq.client.mqtt.mqtt3.message.Mqtt3Message;
import com.hivemq.client.mqtt.mqtt3.message.auth.Mqtt3SimpleAuth;
import com.hivemq.client.mqtt.mqtt3.message.auth.Mqtt3SimpleAuthBuilder;
import com.hivemq.client.mqtt.mqtt3.message.auth.Mqtt3SimpleAuthBuilder.Complete;
import com.hivemq.client.mqtt.mqtt3.message.connect.connack.Mqtt3ConnAck;
import com.hivemq.client.mqtt.mqtt3.message.connect.connack.Mqtt3ConnAckReturnCode;
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish;
import com.hivemq.client.mqtt.mqtt3.message.subscribe.Mqtt3Subscribe;
import com.hivemq.client.mqtt.mqtt3.message.subscribe.Mqtt3SubscribeBuilder;
import com.hivemq.client.mqtt.mqtt3.message.subscribe.Mqtt3SubscribeBuilderBase;
import com.hivemq.client.mqtt.mqtt3.message.subscribe.Mqtt3Subscription;
import com.hivemq.client.mqtt.mqtt3.message.subscribe.suback.Mqtt3SubAck;
import com.hivemq.client.mqtt.mqtt5.Mqtt5Client;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import dynamic.mapping.configuration.ConnectorConfiguration;
import dynamic.mapping.connector.core.ConnectorProperty;
import dynamic.mapping.core.ConfigurationRegistry;
import dynamic.mapping.core.ConnectorStatus;

@Slf4j
// This is instantiated manually not using Spring Boot anymore.
public class MQTTClient extends AConnectorClient {

    public MQTTClient(ConfigurationRegistry configurationRegistry,
            ConnectorConfiguration connectorConfiguration,
            AsynchronousDispatcherInbound dispatcher, String additionalSubscriptionIdTest, String tenant) {
        this.configurationRegistry = configurationRegistry;
        this.mappingComponent = configurationRegistry.getMappingComponent();
        this.serviceConfigurationComponent = configurationRegistry.getServiceConfigurationComponent();
        this.connectorConfigurationComponent = configurationRegistry.getConnectorConfigurationComponent();
        this.connectorConfiguration = connectorConfiguration;
        // ensure the client knows its identity even if configuration is set to null
        this.connectorIdent = connectorConfiguration.ident;
        this.connectorName = connectorConfiguration.name;
        this.c8yAgent = configurationRegistry.getC8yAgent();
        this.cachedThreadPool = configurationRegistry.getCachedThreadPool();
        this.objectMapper = configurationRegistry.getObjectMapper();
        this.additionalSubscriptionIdTest = additionalSubscriptionIdTest;
        this.mappingServiceRepresentation = configurationRegistry.getMappingServiceRepresentations().get(tenant);
        this.serviceConfiguration = configurationRegistry.getServiceConfigurations().get(tenant);
        this.dispatcher = dispatcher;
        this.tenant = tenant;
    }

    private static final int WAIT_PERIOD_MS = 10000;

    @Getter
    private static final String connectorType = "MQTT";

    @Getter
    public static ConnectorSpecification spec;
    static {
        Map<String, ConnectorProperty> configProps = new HashMap<>();
        configProps.put("mqttHost", new ConnectorProperty(true, 0, ConnectorPropertyType.STRING_PROPERTY));
        configProps.put("mqttPort", new ConnectorProperty(true, 1, ConnectorPropertyType.NUMERIC_PROPERTY));
        configProps.put("user", new ConnectorProperty(false, 2, ConnectorPropertyType.STRING_PROPERTY));
        configProps.put("password",
                new ConnectorProperty(false, 3, ConnectorPropertyType.SENSITIVE_STRING_PROPERTY));
        configProps.put("clientId", new ConnectorProperty(true, 4, ConnectorPropertyType.STRING_PROPERTY));
        configProps.put("useTLS", new ConnectorProperty(false, 5, ConnectorPropertyType.BOOLEAN_PROPERTY));
        configProps.put("useSelfSignedCertificate",
                new ConnectorProperty(false, 6, ConnectorPropertyType.BOOLEAN_PROPERTY));
        configProps.put("fingerprintSelfSignedCertificate",
                new ConnectorProperty(false, 7, ConnectorPropertyType.STRING_PROPERTY));
        configProps.put("nameCertificate", new ConnectorProperty(false, 8, ConnectorPropertyType.STRING_PROPERTY));
        spec = new ConnectorSpecification(connectorType, true, configProps);
    }

    private String additionalSubscriptionIdTest;

    private AConnectorClient.Certificate cert;

    private SSLSocketFactory sslSocketFactory;

    private MQTTCallback mqttCallback = null;

    private Mqtt3BlockingClient mqttClient;

    // private Mqtt3ClientConfiguration sslConfiguration;

    public boolean initialize() {
        loadConfiguration();
        Boolean useSelfSignedCertificate = (Boolean) connectorConfiguration.getProperties()
                .getOrDefault("useSelfSignedCertificate", false);
        log.info("Tenant {} - Testing connector for useSelfSignedCertificate: {} ", tenant, useSelfSignedCertificate);
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
                TrustManager[] trustManagers = tmf.getTrustManagers();

                SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
                sslContext.init(null, trustManagers, null);
                sslSocketFactory = sslContext.getSocketFactory();
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
                getConnectorName());
        return true;
    }

    @Override
    public ConnectorSpecification getSpecification() {
        return MQTTClient.spec;
    }

    @Override
    public void connect() {
        log.info("Tenant {} - Establishing the MQTT connection now - phase I: (isConnected:shouldConnect) ({}:{})",
                tenant, isConnected(),
                shouldConnect());
        if (isConnected())
            disconnect();
        // stay in the loop until successful
        boolean successful = false;
        while (!successful) {
            loadConfiguration();
            var firstRun = true;
            while (!isConnected() && shouldConnect()) {
                log.info("Tenant {} - Establishing the MQTT connection now - phase II: {}", tenant,
                        shouldConnect());
                if (!firstRun) {
                    try {
                        Thread.sleep(WAIT_PERIOD_MS);
                    } catch (InterruptedException e) {
                        // ignore errorMessage
                        // log.error("Tenant {} - Error on reconnect: {}", tenant, e.getMessage());
                    }
                }
                try {
                    boolean useTLS = (Boolean) connectorConfiguration.getProperties().getOrDefault("useTLS", false);
                    boolean useSelfSignedCertificate = (Boolean) connectorConfiguration.getProperties()
                            .getOrDefault("useSelfSignedCertificate", false);
                    String prefix = useTLS ? "ssl://" : "tcp://";
                    String mqttHost = (String) connectorConfiguration.getProperties().get("mqttHost");
                    String clientId = (String) connectorConfiguration.getProperties().get("clientId");
                    int mqttPort = (Integer) connectorConfiguration.getProperties().get("mqttPort");
                    String user = (String) connectorConfiguration.getProperties().get("user");
                    String password = (String) connectorConfiguration.getProperties().get("password");
                    String broker = prefix + mqttHost + ":"
                            + mqttPort;
                    // mqttClient = new MqttClient(broker, MqttClient.generateClientId(), new
                    // MemoryPersistence());
                    Mqtt3SimpleAuthBuilder simpleAuthBuilder = Mqtt3SimpleAuth.builder();
                    Complete simpleAuthComplete = null;
                    if (!StringUtils.isEmpty(user)) {
                        simpleAuthComplete = simpleAuthBuilder.username(user);
                    }
                    if (!StringUtils.isEmpty(password) && simpleAuthComplete != null) {
                        simpleAuthComplete = simpleAuthComplete.password(password.getBytes());
                    }
                    if (useSelfSignedCertificate) {
                        MqttClientSslConfigBuilder sslConfigBuilder = MqttClientSslConfig.builder();

                        // use sample from
                        // https://github.com/micronaut-projects/micronaut-mqtt/blob/ac2720937871b8907ad429f7ea5b8b4664a0776e/mqtt-hivemq/src/main/java/io/micronaut/mqtt/hivemq/v3/client/Mqtt3ClientFactory.java#L118
                        // and https://hivemq.github.io/hivemq-mqtt-client/docs/client-configuration/
                        MqttClientSslConfig sslConfig = null;
                        mqttClient = Mqtt3Client.builder().serverHost(mqttHost).serverPort(mqttPort)
                                .identifier(clientId + additionalSubscriptionIdTest).sslConfig(sslConfig)
                                .buildBlocking();
                        ;
                        log.debug("Tenant {} - Using certificate: {}", tenant, cert.getCertInPemFormat());
                        // connOpts.setSocketFactory(sslSocketFactory);
                    } else if (simpleAuthComplete != null) {
                        Mqtt3SimpleAuth simpleAuth = simpleAuthComplete.build();
                        mqttClient = Mqtt3Client.builder().serverHost(mqttHost).serverPort(mqttPort)
                                .identifier(clientId + additionalSubscriptionIdTest)
                                // .automaticReconnect(MqttClientAutoReconnect.builder()
                                // .initialDelay(3000, TimeUnit.MILLISECONDS)
                                // .maxDelay(10000, TimeUnit.MILLISECONDS).build())
                                .simpleAuth(simpleAuth).buildBlocking();
                    } else {
                        mqttClient = Mqtt3Client.builder().serverHost(mqttHost).serverPort(mqttPort)
                                .identifier(clientId + additionalSubscriptionIdTest)
                                // .automaticReconnect(MqttClientAutoReconnect.builder()
                                // .initialDelay(3000, TimeUnit.MILLISECONDS)
                                // .maxDelay(10000, TimeUnit.MILLISECONDS).build())
                                .buildBlocking();
                    }
                    //Registering Callback
                    Mqtt3AsyncClient mqtt3AsyncClient = mqttClient.toAsync();
                    mqtt3AsyncClient.publishes(MqttGlobalPublishFilter.ALL, mqttCallback);

                    // MqttConnectOptions connOpts = new MqttConnectOptions();
                    // connOpts.setCleanSession(true);
                    // connOpts.setAutomaticReconnect(false);
                    // log.info("Tenant {} - DANGEROUS-LOG password: {}", tenant, password);
                    Mqtt3ConnAck ack = mqttClient.connect();
                    if (!ack.getReturnCode().equals(Mqtt3ConnAckReturnCode.SUCCESS)) {
                        throw new ConnectorException("Tenant " + tenant + " - Error connecting to broker:"
                                + mqttClient.getConfig().getServerHost() + ". Errorcode: "
                                + ack.getReturnCode().toString());
                    }
                    log.info("Tenant {} - Successfully connected to broker {}", tenant,
                            mqttClient.getConfig().getServerHost());
                    connectorStatus.updateStatus(ConnectorStatus.CONNECTED, true);
                    sendConnectorLifecycle();
                } catch (ConnectorException e) {
                    log.error("Tenant {} - Error on reconnect: {}", tenant, e.getMessage());
                    updateConnectorStatusToFailed(e);
                    sendConnectorLifecycle();
                    if (serviceConfiguration.logConnectorErrorInBackend) {
                        log.error("Tenant {} - Stacktrace:", tenant, e);
                    }
                }
                firstRun = false;
            }

            try {
                // test if the mqtt connection is configured and enabled
                if (shouldConnect()) {
                    try {
                        // is not working for broker.emqx.io
                        subscribe("$SYS/#", 0);
                        ;
                    } catch (ConnectorException e) {
                        log.warn(
                                "Tenant {} - Error on subscribing to topic $SYS/#, this might not be supported by the mqtt broker {} {}",
                                e.getMessage(), e);
                    }

                    mappingComponent.rebuildMappingOutboundCache(tenant);
                    // in order to keep MappingInboundCache and ActiveSubscriptionMappingInbound in
                    // sync, the ActiveSubscriptionMappingInbound is build on the
                    // reviously used updatedMappings
                    List<Mapping> updatedMappings = mappingComponent.rebuildMappingInboundCache(tenant);
                    updateActiveSubscriptions(updatedMappings, true);
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

    private void updateConnectorStatusToFailed(Exception e) {
        String msg = " --- " + e.getClass().getName() + ": "
                + e.getMessage();
        if (!(e.getCause() == null)) {
            msg = msg + " --- Caused by " + e.getCause().getClass().getName() + ": " + e.getCause().getMessage();
        }
        connectorStatus.setMessage(msg);
        connectorStatus.updateStatus(ConnectorStatus.FAILED, false);
    }

    @Override
    public void close() {

    }

    @Override
    public boolean isConfigValid(ConnectorConfiguration configuration) {
        if (configuration == null)
            return false;
        // if using selfsignied certificate additional properties have to be set
        Boolean useSelfSignedCertificate = (Boolean) configuration.getProperties()
                .getOrDefault("useSelfSignedCertificate", false);
        if (useSelfSignedCertificate && (configuration.getProperties().get("fingerprintSelfSignedCertificate") == null
                || configuration.getProperties().get("nameCertificate") == null)) {
            return false;
        }
        // check if all required properties are set
        for (String property : MQTTClient.getSpec().getProperties().keySet()) {
            if (MQTTClient.getSpec().getProperties().get(property).required
                    && configuration.getProperties().get(property) == null) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean isConnected() {
        // log.info("Tenant {} - TESTING isConnected I:,s {}, {}", tenant, mqttClient,
        // getConnectorIdent(),
        // getConnectorName());
        // if (mqttClient != null)
        // log.info("Tenant {} - TESTING isConnected II: {}", tenant,
        // mqttClient.isConnected());
        // else
        // log.info("Tenant {} - TESTING isConnected II: {}, mqttClient is null",
        // tenant);
        return mqttClient != null ? mqttClient.getState().isConnected() : false;
    }

    @Override
    public void disconnect() {
        log.info("Tenant {} - Disconnecting from MQTT broker: {}", tenant,
                (mqttClient == null ? null : mqttClient.getConfig().getServerHost()));
        try {
            if (isConnected()) {
                log.debug("Tenant {} - Disconnected from MQTT broker I: {}", tenant,
                        mqttClient.getConfig().getServerHost());
                activeSubscriptions.entrySet().forEach(entry -> {
                    // only unsubscribe if still active subscriptions exist
                    String topic = entry.getKey();
                    MutableInt activeSubs = entry.getValue();
                    if (activeSubs.intValue() > 0) {
                        try {
                            mqttClient.unsubscribe(topic);
                        } catch (ConnectorException e) {
                            log.error("Tenant {} - Exception when unsubscribing from topic: {}: ", tenant, topic, e);
                        }

                    }
                });
                mqttClient.unsubscribe("$SYS");
                mqttClient.disconnect();
                connectorStatus.updateStatus(ConnectorStatus.DISCONNECTED, true);
                sendConnectorLifecycle();
                log.info("Tenant {} - Disconnected from MQTT broker II: {}", tenant,
                        mqttClient.getConfig().getServerHost());
            }
        } catch (ConnectorException e) {
            log.error("Tenant {} - Error on disconnecting MQTT Client: ", tenant, e);
            updateConnectorStatusToFailed(e);
            sendConnectorLifecycle();
        }
    }

    @Override
    public String getConnectorIdent() {
        return connectorIdent;
    }

    @Override
    public void subscribe(String topic, Integer qos) throws ConnectorException {
        log.debug("Tenant {} - Subscribing on topic: {}", tenant, topic);
        sendSubscriptionEvents(topic, "Subscribing");
        if (qos != null) {
            //We don't need to add a handler on subscribe using hive client
            //mqttClient.subscribeWith().topicFilter(topic).qos(MqttQos.fromCode(qos)).send();
            Mqtt3AsyncClient asyncMqttClient = mqttClient.toAsync();
            asyncMqttClient.subscribeWith().topicFilter(topic).qos(MqttQos.fromCode(qos)).send().thenRun(() -> {
                log.debug("Tenant {} - Successfully subscribed on topic: {}", tenant, topic);
            }).exceptionally(throwable -> {
                log.error("Tenant {} - Failed to subscribe on topic {} with error: ",tenant,topic,throwable.getMessage());
                return null;
            });
        } else {
            //We don't need to add a handler on subscribe using hive client
            Mqtt3AsyncClient asyncMqttClient = mqttClient.toAsync();
            asyncMqttClient.subscribeWith().topicFilter(topic).qos(MqttQos.fromCode(qos)).send().thenRun(() -> {
                log.debug("Tenant {} - Successfully subscribed on topic: {}", tenant, topic);
            }).exceptionally(throwable -> {
                log.error("Tenant {} - Failed to subscribe on topic {} with error: ",tenant,topic,throwable.getMessage());
                return null;
            });
        }
    }

    public void unsubscribe(String topic) throws Exception {
        log.debug("Tenant {} - Unsubscribing from topic: {}", tenant, topic);
        sendSubscriptionEvents(topic, "Unsubscribing");
        mqttClient.unsubscribe(topic);
    }

    public void publishMEAO(ProcessingContext<?> context) {
        C8YRequest currentRequest = context.getCurrentRequest();
        String payload = currentRequest.getRequest();
        Mqtt3Publish mqttMessage = Mqtt3Publish.builder().topic(context.getTopic()).payload(payload.getBytes()).build();
        mqttClient.publish(mqttMessage);

        log.info("Tenant {} - Published outbound message: {} for mapping: {} on topic: {}", tenant, payload,
                context.getMapping().name, context.getResolvedPublishTopic());
    }

    @Override
    public String getConnectorName() {
        return connectorName;
    }
}