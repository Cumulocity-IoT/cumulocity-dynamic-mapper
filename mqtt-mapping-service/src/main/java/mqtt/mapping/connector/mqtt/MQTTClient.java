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

package mqtt.mapping.connector.mqtt;

import com.cumulocity.microservice.context.credentials.MicroserviceCredentials;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import mqtt.mapping.configuration.ConnectorConfiguration;
import mqtt.mapping.configuration.ConnectorConfigurationComponent;
import mqtt.mapping.connector.core.ConnectorProperty;
import mqtt.mapping.connector.core.ConnectorPropertyDefinition;
import mqtt.mapping.connector.core.callback.ConnectorMessage;
import mqtt.mapping.connector.core.client.IConnectorClient;
import mqtt.mapping.core.C8YAgent;
import mqtt.mapping.core.MappingComponent;
import mqtt.mapping.core.ServiceStatus;
import mqtt.mapping.model.Mapping;
import mqtt.mapping.processor.inbound.AsynchronousDispatcher;
import mqtt.mapping.processor.model.C8YRequest;
import mqtt.mapping.processor.model.ProcessingContext;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.joda.time.DateTime;
import org.springframework.scheduling.annotation.Scheduled;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
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
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

@Slf4j
//@Configuration
//@EnableScheduling
//@Service
//This is instantiated manually not using Spring Boot anymore.
public class MQTTClient implements IConnectorClient {

    public MQTTClient(MicroserviceCredentials credentials, String tenantId, MappingComponent mappingComponent, ConnectorConfigurationComponent connectorConfigurationComponent, C8YAgent c8YAgent, ExecutorService cachedThreadPool, ObjectMapper objectMapper, String additionalSubscriptionIdTest) {
        setConfigProperties();
        this.credentials = credentials;
        this.tenantId = tenantId;
        this.mappingComponent = mappingComponent;
        this.connectorConfigurationComponent = connectorConfigurationComponent;
        this.c8yAgent = c8YAgent;
        this.cachedThreadPool = cachedThreadPool;
        this.objectMapper = objectMapper;
        this.additionalSubscriptionIdTest = additionalSubscriptionIdTest;
    }

    private static final int WAIT_PERIOD_MS = 10000;

    @Getter
    private MicroserviceCredentials credentials = null;

    private static final String CONNECTOR_ID = "MQTT";

    private Map<String, ConnectorPropertyDefinition> configProps = new HashMap<>();

    private String tenantId;

    private String additionalSubscriptionIdTest;
    public static final Long KEY_MONITORING_UNSPECIFIED = -1L;
    private static final String STATUS_MQTT_EVENT_TYPE = "mqtt_status_event";

    private IConnectorClient.Certificate cert;

    private MQTTCallback mqttCallback = null;

    @Getter
    private MappingComponent mappingComponent;

    @Getter
    public ConnectorConfigurationComponent connectorConfigurationComponent;

    private MqttClient mqttClient;

    @Getter
    private C8YAgent c8yAgent;


    @Getter
    private AsynchronousDispatcher dispatcher;

    private ObjectMapper objectMapper;

    @Getter
    private ExecutorService cachedThreadPool;

    private Future<?> connectTask;
    private Future<?> initializeTask;

    @Getter
    @Setter
    // keeps track of number of active mappings per subscriptionTopic
    private Map<String, Map<String, Integer>> activeSubscriptions = new HashMap<>();
    ;

    private Instant start = Instant.now();

    private ConnectorConfiguration configuration;

    @Data
    @AllArgsConstructor
    public static class Certificate {
        private String fingerprint;
        private String certInPemFormat;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    @Override
    public String getTenantId() {
        return this.tenantId;
    }

    public void submitInitialize() {
        // test if init task is still running, then we don't need to start another task
        log.info("Tenant {} - Called initialize(): {}", initializeTask == null || initializeTask.isDone(), tenantId);
        if ((initializeTask == null || initializeTask.isDone())) {
            initializeTask = cachedThreadPool.submit(() -> initialize());
        }
    }

    private boolean initialize() {
        var firstRun = true;
        while (!canConnect()) {
            //this.configuration = connectorConfigurationComponent.loadConnectorConfiguration(this.getConntectorId());
            if (!firstRun) {
                try {
                    log.info("Tenant {} - Retrieving MQTT configuration in {}s ...", tenantId,
                            WAIT_PERIOD_MS / 1000);
                    Thread.sleep(WAIT_PERIOD_MS);
                } catch (InterruptedException e) {
                    log.error("Error initializing MQTT client: ", e);
                }
            }
            reloadConfiguration();

            boolean useSelfSignedCertificate = (Boolean) configuration.getProperties().get("useSelfSignedCertificate");
            String nameCertificate = (String) configuration.getProperties().get("nameCertificate");
            if (useSelfSignedCertificate) {
                cert = c8yAgent.loadCertificateByName(nameCertificate, this.credentials);
            }
            firstRun = false;
        }
        return true;
    }


    private void setConfigProperties() {
        this.configProps.put("mqttHost", new ConnectorPropertyDefinition(true, ConnectorProperty.STRING_PROPERTY));
        this.configProps.put("mqttPort", new ConnectorPropertyDefinition(true, ConnectorProperty.NUMERIC_PROPERTY));
        this.configProps.put("user", new ConnectorPropertyDefinition(false, ConnectorProperty.STRING_PROPERTY));
        this.configProps.put("password", new ConnectorPropertyDefinition((false), ConnectorProperty.SENSITIVE_STRING_PROPERTY));
        this.configProps.put("clientId", new ConnectorPropertyDefinition(true, ConnectorProperty.STRING_PROPERTY));
        this.configProps.put("useTLS", new ConnectorPropertyDefinition(false, ConnectorProperty.BOOLEAN_PROPERTY));
        this.configProps.put("useSelfSignedCertificate", new ConnectorPropertyDefinition(false, ConnectorProperty.BOOLEAN_PROPERTY));
        this.configProps.put("fingerprintSelfSignedCertificate", new ConnectorPropertyDefinition(false, ConnectorProperty.STRING_PROPERTY));
        this.configProps.put("nameCertificate", new ConnectorPropertyDefinition(false, ConnectorProperty.STRING_PROPERTY));
    }

    @Override
    public Map<String, ConnectorPropertyDefinition> getConfigProperties() {
        return this.configProps;
    }

    private void reloadConfiguration() {
        configuration = connectorConfigurationComponent.getConnectorConfiguration(this.getConntectorId(), tenantId);
    }

    public void submitConnect() {
        // test if connect task is still running, then we don't need to start another
        // task
        log.info("Tenant {} - Called connect(): connectTask.isDone() {}", tenantId,
                connectTask == null || connectTask.isDone());
        if (connectTask == null || connectTask.isDone()) {
            connectTask = cachedThreadPool.submit(() -> connect());
        }
    }

    public void connect() {
        reloadConfiguration();
        log.info("Tenant {} - Establishing the MQTT connection now - phase I: (isConnected:shouldConnect) ({}:{})", tenantId, isConnected(),
                shouldConnect());
        if (isConnected()) {
            disconnect();
        }
        // stay in the loop until successful
        boolean successful = false;
        while (!successful) {
            var firstRun = true;
            while (!isConnected() && shouldConnect()) {
                log.info("Tenant {} - Establishing the MQTT connection now - phase II: {}, {}", tenantId, isConfigValid(configuration), canConnect());
                if (!firstRun) {
                    try {
                        Thread.sleep(WAIT_PERIOD_MS);
                    } catch (InterruptedException e) {
                        log.error("Error on reconnect: {}", e.getMessage());
                        log.debug("Stacktrace:", e);
                    }
                }
                try {
                    if (canConnect()) {
                        boolean useTLS = (Boolean) configuration.getProperties().get("useTLS");
                        boolean useSelfSignedCertificate = (Boolean) configuration.getProperties().get("useSelfSignedCertificate");
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
                            this.dispatcher = new AsynchronousDispatcher(this, c8yAgent, objectMapper, cachedThreadPool, mappingComponent);
                        mqttClient = new MqttClient(broker,
                                clientId + additionalSubscriptionIdTest,
                                new MemoryPersistence());
                        mqttCallback = new MQTTCallback(dispatcher, tenantId, getConntectorId());
                        mqttClient.setCallback(mqttCallback);
                        MqttConnectOptions connOpts = new MqttConnectOptions();
                        connOpts.setCleanSession(true);
                        connOpts.setAutomaticReconnect(false);
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
                                log.error("Exception when configuring socketFactory for TLS!", e);
                            }
                        }
                        mqttClient.connect(connOpts);
                        log.info("Tenant {} - Successfully connected to broker {}", tenantId, mqttClient.getServerURI());
                        c8yAgent.createEvent("Successfully connected to broker " + mqttClient.getServerURI(),
                                STATUS_MQTT_EVENT_TYPE,
                                DateTime.now(), null, tenantId);

                    }
                } catch (MqttException e) {
                    log.error("Error on reconnect: {}", e.getMessage());
                    log.debug("Stacktrace:", e);
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

                    mappingComponent.rebuildMappingOutboundCache(tenantId);
                    // in order to keep MappingInboundCache and ActiveSubscriptionMappingInbound in
                    // sync, the ActiveSubscriptionMappingInbound is build on the
                    // reviously used updatedMappings
                    List<Mapping> updatedMappings = mappingComponent.rebuildMappingInboundCache(tenantId);
                    updateActiveSubscriptions(updatedMappings, true);
                }
                successful = true;
                log.info("Tenant {} - Subscribing to topics was successful: {}", tenantId, successful);
            } catch (Exception e) {
                log.error("Error on reconnect, retrying ... {} {}", e.getMessage(), e);
                log.debug("Stacktrace:", e);
                successful = false;
            }

        }
    }

    private boolean canConnect() {
        if (configuration == null)
            return false;
        boolean useSelfSignedCertificate = (Boolean) configuration.getProperties().get("useSelfSignedCertificate");
        return configuration.isEnabled()
                && (!useSelfSignedCertificate
                || (useSelfSignedCertificate &&
                cert != null));
    }

    private boolean shouldConnect() {
        return isConfigValid(configuration) && configuration.isEnabled();
    }

    public boolean isConnected() {
        return mqttClient != null ? mqttClient.isConnected() : false;
    }

    public void disconnect() {
        log.info("Tenant {} - isconnecting from MQTT broker: {}", tenantId,
                (mqttClient == null ? null : mqttClient.getServerURI()));
        try {
            if (isConnected()) {
                log.debug("Disconnected from MQTT broker I: {}", mqttClient.getServerURI());
                activeSubscriptions.get(tenantId).entrySet().forEach(entry -> {
                    // only unsubscribe if still active subscriptions exist
                    String topic = entry.getKey();
                    Integer activeSubs = entry.getValue();
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
                log.debug("Disconnected from MQTT broker II: {}", mqttClient.getServerURI());
            }
        } catch (MqttException e) {
            log.error("Error on disconnecting MQTT Client: ", e);
        }
    }

    @Override
    public String getConntectorId() {
        return CONNECTOR_ID;
    }

    public void disconnectFromBroker() {
        configuration = connectorConfigurationComponent.enableConnection(this.getConntectorId(), false);
        disconnect();
        mappingComponent.sendStatusService(tenantId, getServiceStatus());
    }

    public void connectToBroker() {
        configuration = connectorConfigurationComponent.enableConnection(this.getConntectorId(), true);
        submitConnect();
        mappingComponent.sendStatusService(tenantId, getServiceStatus());
    }

    public void subscribe(String topic, Integer qos) throws MqttException {

        log.debug("Subscribing on topic: {}", topic);
        c8yAgent.createEvent("Subscribing on topic " + topic, STATUS_MQTT_EVENT_TYPE, DateTime.now(), null, tenantId);
        if (qos != null)
            mqttClient.subscribe(topic, qos);
        else
            mqttClient.subscribe(topic);
        log.debug("Successfully subscribed on topic: {}", topic);

    }

    public void unsubscribe(String topic) throws MqttException {
        log.info("Tenant {} - Unsubscribing from topic: {}", tenantId, topic);
        c8yAgent.createEvent("Unsubscribing on topic " + topic, STATUS_MQTT_EVENT_TYPE, DateTime.now(), null, tenantId);
        mqttClient.unsubscribe(topic);
    }

    @Scheduled(fixedRate = 30000)
    public void runHouskeeping() {
        try {
            Instant now = Instant.now();
            // only log this for the first 180 seconds to reduce log amount
            if (Duration.between(start, now).getSeconds() < 1800) {
                String statusConnectTask = (connectTask == null ? "stopped"
                        : connectTask.isDone() ? "stopped" : "running");
                String statusInitializeTask = (initializeTask == null ? "stopped"
                        : initializeTask.isDone() ? "stopped" : "running");
                log.info("Tenant {} - Status: connectTask: {}, initializeTask: {}, isConnected: {}", tenantId, statusConnectTask,
                        statusInitializeTask, isConnected());
            }
            mappingComponent.cleanDirtyMappings(tenantId);
            mappingComponent.sendStatusMapping(tenantId);
            mappingComponent.sendStatusService(tenantId, getServiceStatus());
        } catch (Exception ex) {
            log.error("Error during house keeping execution: {}", ex);
        }
    }

    @Override
    public ServiceStatus getServiceStatus() {
        ServiceStatus serviceStatus;
        if (isConnected()) {
            serviceStatus = ServiceStatus.connected();
        } else if (canConnect()) {
            serviceStatus = ServiceStatus.activated();
        } else if (isConfigValid(configuration)) {
            serviceStatus = ServiceStatus.configured();
        } else {
            serviceStatus = ServiceStatus.notReady();
        }
        return serviceStatus;
    }

    @Override
    public boolean isConfigValid(ConnectorConfiguration configuration) {
        if (configuration == null)
            return false;
        String host = (String) configuration.getProperties().get("mqttHost");
        int port = (Integer) configuration.getProperties().get("mqttPort");
        String clientId = (String) configuration.getProperties().get("clientId");
        return !StringUtils.isEmpty(host) &&
                !(port == 0) &&
                !StringUtils.isEmpty(clientId);
    }

    @Override
    public List<ProcessingContext<?>> test(String topic, boolean send, Map<String, Object> payload)
            throws Exception {
        String payloadMessage = objectMapper.writeValueAsString(payload);
        ConnectorMessage message = new ConnectorMessage();
        message.setPayload(payloadMessage.getBytes());
        if (dispatcher == null)
            dispatcher = new AsynchronousDispatcher(this, c8yAgent, objectMapper, cachedThreadPool, mappingComponent);
        return dispatcher.processMessage(tenantId, getConntectorId(), topic, message, send).get();
    }

    public void reconnect() {
        disconnect();
        // invalidate broker client
        configuration = null;
        submitInitialize();
        submitConnect();
    }


    @Override
    public void deleteActiveSubscription(Mapping mapping) {
        if (getActiveSubscriptions().containsKey(mapping.subscriptionTopic)) {
            Integer activeSubs = getActiveSubscriptions().get(tenantId)
                    .get(mapping.subscriptionTopic);
            activeSubs--;
            if (activeSubs <= 0) {
                try {
                    mqttClient.unsubscribe(mapping.subscriptionTopic);
                } catch (MqttException e) {
                    log.error("Exception when unsubscribing from topic: {}, {}", mapping.subscriptionTopic,
                            e);
                }
            }
        }
    }

    @Override
    public void upsertActiveSubscription(Mapping mapping) {
        // test if subscriptionTopic has changed
        Mapping activeMapping = null;
        Boolean create = true;
        Boolean subscriptionTopicChanged = false;
        Optional<Mapping> activeMappingOptional = mappingComponent.getCacheMappingInbound().get(tenantId).values().stream()
                .filter(m -> m.id.equals(mapping.id))
                .findFirst();

        if (activeMappingOptional.isPresent()) {
            create = false;
            activeMapping = activeMappingOptional.get();
            subscriptionTopicChanged = !mapping.subscriptionTopic.equals(activeMapping.subscriptionTopic);
        }

        if (!getActiveSubscriptions().get(tenantId).containsKey(mapping.subscriptionTopic)) {
            getActiveSubscriptions().get(tenantId).put(mapping.subscriptionTopic, Integer.valueOf(0));
        }
        Integer updatedMappingSubs = getActiveSubscriptions().get(tenantId)
                .get(mapping.subscriptionTopic);

        // consider unsubscribing from previous subscription topic if it has changed
        if (create) {
            updatedMappingSubs++;
            log.info("Tenant {} - Subscribing to topic: {}, qos: {}", tenantId, mapping.subscriptionTopic, mapping.qos.ordinal());
            try {
                subscribe(mapping.subscriptionTopic, mapping.qos.ordinal());
            } catch (MqttException e1) {
                log.error("Exception when subscribing to topic: {}, {}", mapping.subscriptionTopic, e1);
            }
        } else if (subscriptionTopicChanged && activeMapping != null) {
            Integer activeMappingSubs = getActiveSubscriptions().get(0)
                    .get(activeMapping.subscriptionTopic);
            activeMappingSubs--;
            if (activeMappingSubs.intValue() <= 0) {
                try {
                    mqttClient.unsubscribe(mapping.subscriptionTopic);
                } catch (MqttException e) {
                    log.error("Exception when unsubscribing from topic: {}, {}", mapping.subscriptionTopic, e);
                }
            }
            updatedMappingSubs++;
            if (!getActiveSubscriptions().containsKey(mapping.subscriptionTopic)) {
                log.info("Tenant {} - Subscribing to topic: {}, qos: {}", tenantId, mapping.subscriptionTopic, mapping.qos.ordinal());
                try {
                    subscribe(mapping.subscriptionTopic, mapping.qos.ordinal());
                } catch (MqttException e1) {
                    log.error("Exception when subscribing to topic: {}, {}", mapping.subscriptionTopic, e1);
                }
            }
        }

    }

    @Override
    public void updateActiveSubscriptions(List<Mapping> updatedMappings, boolean reset) {
        if (reset) {
            activeSubscriptions.put(tenantId, new HashMap<String, Integer>());
        }
        Map<String, Integer> updatedSubscriptionCache = new HashMap<String, Integer>();
        updatedMappings.forEach(mapping -> {
            if (!updatedSubscriptionCache.containsKey(mapping.subscriptionTopic)) {
                updatedSubscriptionCache.put(mapping.subscriptionTopic, Integer.valueOf(0));
            }
            Integer activeSubs = updatedSubscriptionCache.get(mapping.subscriptionTopic);
            updatedSubscriptionCache.put(mapping.subscriptionTopic, activeSubs++);
        });

        // unsubscribe topics not used
        getActiveSubscriptions().get(tenantId).keySet().forEach((topic) -> {
            if (!updatedSubscriptionCache.containsKey(topic)) {
                log.info("Tenant {} - Unsubscribe from topic: {}", tenantId, topic);
                try {
                    unsubscribe(topic);
                } catch (MqttException e1) {
                    log.error("Exception when unsubscribing from topic: {}, {}", topic, e1);
                    throw new RuntimeException(e1);
                }
            }
        });

        // subscribe to new topics
        updatedSubscriptionCache.keySet().forEach((topic) -> {
            if (!getActiveSubscriptions().containsKey(topic)) {
                int qos = updatedMappings.stream().filter(m -> m.subscriptionTopic.equals(topic))
                        .map(m -> m.qos.ordinal()).reduce(Integer::max).orElse(0);
                log.info("Tenant {} - Subscribing to topic: {}, qos: {}", tenantId, topic, qos);
                try {
                    subscribe(topic, qos);
                } catch (MqttException e1) {
                    log.error("Exception when subscribing to topic: {}, {}", topic, e1);
                    throw new RuntimeException(e1);
                }
            }
        });
        activeSubscriptions.put(tenantId, updatedSubscriptionCache);
    }

    @Override
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
        log.info("Tenant {} - Published outbound message: {} for mapping: {} on topic: {}", tenantId, payload, context.getMapping().name, context.getResolvedPublishTopic());
    }

    @Override
    public Map<String, Integer> getActiveSubscriptions(String tenant) {
        return activeSubscriptions.get(tenant);
        //return getActiveSubscriptionMappingInbound().entrySet().stream()
        //        .map(entry -> new AbstractMap.SimpleEntry<String, Integer>(entry.getKey(), entry.getValue().getValue()))
        //        .collect(Collectors.toMap(Entry::getKey, Entry::getValue));
    }

}