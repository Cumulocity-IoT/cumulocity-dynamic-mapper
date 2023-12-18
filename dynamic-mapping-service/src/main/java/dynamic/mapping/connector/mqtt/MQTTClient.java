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
import java.util.concurrent.ExecutorService;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

import dynamic.mapping.connector.core.ConnectorPropertyType;
import dynamic.mapping.connector.core.ConnectorSpecification;
import dynamic.mapping.connector.core.client.AConnectorClient;
import dynamic.mapping.model.Mapping;
import dynamic.mapping.model.MappingServiceRepresentation;
import dynamic.mapping.processor.inbound.AsynchronousDispatcherInbound;
import dynamic.mapping.processor.model.C8YRequest;
import dynamic.mapping.processor.model.ProcessingContext;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.mutable.MutableInt;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import com.cumulocity.microservice.context.credentials.Credentials;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import dynamic.mapping.configuration.ConnectorConfiguration;
import dynamic.mapping.configuration.ConnectorConfigurationComponent;
import dynamic.mapping.connector.core.ConnectorProperty;
import dynamic.mapping.core.C8YAgent;
import dynamic.mapping.core.MappingComponent;
import dynamic.mapping.core.Status;

@Slf4j
// @EnableScheduling
// @Configuration
// @Service
// This is instantiated manually not using Spring Boot anymore.
public class MQTTClient extends AConnectorClient {

    public MQTTClient(Credentials credentials, String tenant, MappingComponent mappingComponent,
            ConnectorConfigurationComponent connectorConfigurationComponent,
            ConnectorConfiguration connectorConfiguration, C8YAgent c8YAgent, ExecutorService cachedThreadPool,
            ObjectMapper objectMapper, String additionalSubscriptionIdTest,
            MappingServiceRepresentation mappingServiceRepresentation) {
        // setConfigProperties();
        this.credentials = credentials;
        this.tenant = tenant;
        this.mappingComponent = mappingComponent;
        this.connectorConfigurationComponent = connectorConfigurationComponent;
        this.configuration = connectorConfiguration;
        // ensure the client knows its identity even if configuration is set to null
        this.connectorIdent = connectorConfiguration.ident;
        this.connectorName = connectorConfiguration.name;
        this.c8yAgent = c8YAgent;
        this.cachedThreadPool = cachedThreadPool;
        this.objectMapper = objectMapper;
        this.additionalSubscriptionIdTest = additionalSubscriptionIdTest;
        this.mappingServiceRepresentation = mappingServiceRepresentation;
    }

    private static final int WAIT_PERIOD_MS = 10000;

    private Credentials credentials = null;

    @Getter
    private static final String connectorType = "MQTT";

    private String connectorIdent = null;
    private String connectorName = null;

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

    private MQTTCallback mqttCallback = null;

    private MqttClient mqttClient;

    public boolean initialize() {
        loadConfiguration();
        boolean useSelfSignedCertificate = (Boolean) configuration.getProperties().get("useSelfSignedCertificate");
        if (useSelfSignedCertificate) {
            String nameCertificate = (String) configuration.getProperties().get("nameCertificate");
            cert = c8yAgent.loadCertificateByName(nameCertificate, this.credentials);
        }
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
                    boolean useTLS = (Boolean) configuration.getProperties().getOrDefault("useTLS", false);
                    boolean useSelfSignedCertificate = (Boolean) configuration.getProperties()
                            .getOrDefault("useSelfSignedCertificate", false);
                    String prefix = useTLS ? "ssl://" : "tcp://";
                    String mqttHost = (String) configuration.getProperties().get("mqttHost");
                    String clientId = (String) configuration.getProperties().get("clientId");
                    int mqttPort = (Integer) configuration.getProperties().get("mqttPort");
                    String user = (String) configuration.getProperties().get("user");
                    String password = (String) configuration.getProperties().get("password");
                    String broker = prefix + mqttHost + ":"
                            + mqttPort;
                    // mqttClient = new MqttClient(broker, MqttClient.generateClientId(), new
                    // MemoryPersistence());

                    // before we create a new mqttClient, test if there already exists on and try to
                    // close it
                    if (mqttClient != null) {
                        mqttClient.close(true);
                    }
                    if (dispatcher == null)
                        this.dispatcher = new AsynchronousDispatcherInbound(this, c8yAgent, objectMapper,
                                cachedThreadPool,
                                mappingComponent);
                    mqttClient = new MqttClient(broker,
                            clientId + additionalSubscriptionIdTest,
                            new MemoryPersistence());
                    mqttCallback = new MQTTCallback(dispatcher, tenant, MQTTClient.getConnectorType());
                    mqttClient.setCallback(mqttCallback);
                    MqttConnectOptions connOpts = new MqttConnectOptions();
                    connOpts.setCleanSession(true);
                    connOpts.setAutomaticReconnect(false);
                    // log.info("Tenant {} - DANGEROUS-LOG password: {}", tenant, password);
                    if (!StringUtils.isEmpty(user)
                            && !StringUtils.isEmpty(password)) {
                        connOpts.setUserName(user);
                        connOpts.setPassword(password.toCharArray());
                    }
                    if (useSelfSignedCertificate) {
                        log.debug("Using certificate: {}", cert.getCertInPemFormat());

                        try {
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
                            SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();

                            // where options is the MqttConnectOptions object
                            connOpts.setSocketFactory(sslSocketFactory);
                        } catch (NoSuchAlgorithmException | CertificateException | IOException | KeyStoreException
                                | KeyManagementException e) {
                            log.error("Tenant {} - Exception when configuring socketFactory for TLS!", tenant, e);
                        }
                    }
                    mqttClient.connect(connOpts);
                    log.info("Tenant {} - Successfully connected to broker {}", tenant,
                            mqttClient.getServerURI());
                    connectorStatus.updateStatus(Status.CONNECTED);
                    connectorStatus.clearMessage();
                    sendConnectorStatusAsEvent();
                } catch (MqttException e) {
                    log.error("Error on reconnect: {}", e.getMessage());
                    updateConnectorStatusWithErrorMessage(e);
                    sendConnectorStatusAsEvent();
                    if (c8yAgent.getServiceConfiguration().logErrorConnect) {
                        log.error("Stacktrace:", e);
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
                    } catch (Exception e) {
                        log.warn(
                                "Error on subscribing to topic $SYS/#, this might not be supported by the mqtt broker {} {}",
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
                log.error("Tenant {} - Error on reconnect, retrying ... {} {}", tenant, e.getMessage(), e);
                updateConnectorStatusWithErrorMessage(e);
                sendConnectorStatusAsEvent();
                if (c8yAgent.getServiceConfiguration().logErrorConnect) {
                    log.error("Stacktrace:", e);
                }
                successful = false;
            }
        }
    }

    private void updateConnectorStatusWithErrorMessage(Exception e) {
        String msg = " --- " + e.getClass().getName() + ": "
                + e.getMessage();
        if (!(e.getCause() == null)) {
            msg = msg + " --- Caused by " + e.getCause().getClass().getName() + ": " + e.getCause().getMessage();
        }
        connectorStatus.setMessage(msg);
        connectorStatus.updateStatus(Status.FAILED);
    }

    @Override
    public void close() {
        if (mqttClient != null) {
            try {
                mqttClient.close();
            } catch (MqttException e) {
                log.error("Tenant {} - Error on closing mqttClient {} {}", tenant, e.getMessage(), e);
            }
        }
    }

    @Override
    public boolean shouldConnect() {
        return isConfigValid(configuration) && configuration.isEnabled();
    }

    @Override
    public boolean isConfigValid(ConnectorConfiguration configuration) {
        if (configuration == null)
            return false;
        // check if all required properties are set
        for (String property : MQTTClient.getSpec().getProperties().keySet()) {
            if (MQTTClient.getSpec().getProperties().get(property).required
                    && configuration.getProperties().get(property) == null) {
                return false;
            }
        }
        Boolean useSelfSignedCertificate = (Boolean) configuration.getProperties()
                .getOrDefault("useSelfSignedCertificate", false);
        Boolean sslConfiguredCorrectly = (useSelfSignedCertificate && cert != null) || !useSelfSignedCertificate;
        return sslConfiguredCorrectly;
    }

    @Override
    public boolean isConnected() {
        return mqttClient != null ? mqttClient.isConnected() : false;
    }

    @Override
    public void disconnect() {
        log.info("Tenant {} - Diconnecting from MQTT broker: {}", tenant,
                (mqttClient == null ? null : mqttClient.getServerURI()));
        try {
            if (isConnected()) {
                log.debug("Tenant {} - Disconnected from MQTT broker I: {}", tenant, mqttClient.getServerURI());
                activeSubscriptions.entrySet().forEach(entry -> {
                    // only unsubscribe if still active subscriptions exist
                    String topic = entry.getKey();
                    MutableInt activeSubs = entry.getValue();
                    if (activeSubs.intValue() > 0) {
                        try {
                            mqttClient.unsubscribe(topic);
                        } catch (MqttException e) {
                            log.error("Exception when unsubscribing from topic: {}, {}", topic, e);
                        }

                    }
                });
                mqttClient.unsubscribe("$SYS");
                mqttClient.disconnect();
                connectorStatus.updateStatus(Status.DISCONNECTED);
                connectorStatus.clearMessage();
                sendConnectorStatusAsEvent();
                log.info("Tenant {} - Disconnected from MQTT broker II: {}", tenant, mqttClient.getServerURI());
            }
        } catch (MqttException e) {
            log.error("Tenant {} - Error on disconnecting MQTT Client: ", tenant, e);
            updateConnectorStatusWithErrorMessage(e);
            sendConnectorStatusAsEvent();
        }
    }

    @Override
    public String getConnectorIdent() {
        return connectorIdent;
    }

    public void disconnectFromBroker() {
        configuration = connectorConfigurationComponent.enableConnection(this.getConnectorIdent(), false);
        submitDisconnect();
        mappingComponent.sendConnectorStatus(tenant, getConnectorStatus(), getConnectorIdent(), getConnectorName());
    }

    public void connectToBroker() {
        configuration = connectorConfigurationComponent.enableConnection(this.getConnectorIdent(), true);
        submitConnect();
        mappingComponent.sendConnectorStatus(tenant, getConnectorStatus(), getConnectorIdent(), getConnectorName());
    }

    @Override
    public void subscribe(String topic, Integer qos) throws MqttException {
        log.debug("Subscribing on topic: {}", topic);
        sendSubscriptionEvent(topic, "Subscribing");
        if (qos != null)
            mqttClient.subscribe(topic, qos);
        else
            mqttClient.subscribe(topic);
        log.debug("Successfully subscribed on topic: {}", topic);
    }

    public void unsubscribe(String topic) throws Exception {
        log.info("Tenant {} - Unsubscribing from topic: {}", tenant, topic);
        sendSubscriptionEvent(topic, "Unsubscribing");
        mqttClient.unsubscribe(topic);
    }

    public void publishMEAO(ProcessingContext<?> context) {
        MqttMessage mqttMessage = new MqttMessage();
        C8YRequest currentRequest = context.getCurrentRequest();
        String payload = currentRequest.getRequest();
        mqttMessage.setPayload(payload.getBytes());
        try {
            mqttClient.publish(context.getResolvedPublishTopic(), mqttMessage);
        } catch (MqttException e) {
            throw new RuntimeException(e);
        }
        log.info("Tenant {} - Published outbound message: {} for mapping: {} on topic: {}", tenant, payload,
                context.getMapping().name, context.getResolvedPublishTopic());
    }

    @Override
    public String getConnectorName() {
        return connectorName;
    }

}