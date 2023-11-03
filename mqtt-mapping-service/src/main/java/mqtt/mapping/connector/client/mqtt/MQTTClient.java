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

package mqtt.mapping.connector.client.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import mqtt.mapping.configuration.ConfigurationConnection;
import mqtt.mapping.configuration.ConnectionConfigurationComponent;
import mqtt.mapping.connector.ConnectorProperty;
import mqtt.mapping.connector.IConnectorClient;
import mqtt.mapping.connector.callback.ConnectorMessage;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

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
@Configuration
@EnableScheduling
@Service
public class MQTTClient implements IConnectorClient {

    public MQTTClient() {
        setConfigProperties();
    }

    private static final int WAIT_PERIOD_MS = 10000;

    private static final String CONNECTOR_ID = "MQTT";

    private Map<String, ConnectorProperty> configProps = new HashMap<>();


    private String tenantId = null;
    public static final Long KEY_MONITORING_UNSPECIFIED = -1L;
    private static final String STATUS_MQTT_EVENT_TYPE = "mqtt_status_event";

    private ConfigurationConnection connectionConfiguration;
    private IConnectorClient.Certificate cert;


    private ConnectionConfigurationComponent connectionConfigurationComponent;

    private MQTTCallback mqttCallback = null;

    @Autowired
    public void setConnectionConfigurationComponent(ConnectionConfigurationComponent connectionConfigurationComponent) {
        this.connectionConfigurationComponent = connectionConfigurationComponent;
    }

    private MappingComponent mappingComponent;

    @Autowired
    public void setMappingComponent(MappingComponent mappingStatusComponent) {
        this.mappingComponent = mappingStatusComponent;
    }

    private MqttClient mqttClient;

    private C8YAgent c8yAgent;

    @Autowired
    public void setC8yAgent(@Lazy C8YAgent c8yAgent) {
        this.c8yAgent = c8yAgent;
    }

    // @Autowired
    // private SynchronousDispatcher dispatcher;

    private AsynchronousDispatcher dispatcher;

    //@Autowired
    //public void setDispatcher(AsynchronousDispatcher dispatcher) {
    //    this.dispatcher = dispatcher;
    //}

    private ObjectMapper objectMapper;

    @Autowired
    public void setObjectMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Qualifier("cachedThreadPool")
    private ExecutorService cachedThreadPool;

    @Autowired
    public void setCachedThreadPool(ExecutorService cachedThreadPool) {
        this.cachedThreadPool = cachedThreadPool;
    }

    private Future<?> connectTask;
    private Future<?> initializeTask;

    @Getter
    @Setter
    // keeps track of number of active mappings per subscriptionTopic
    private Map<String, Map<String, Integer>> activeSubscriptions;

    private Instant start = Instant.now();

    @Value("${APP.additionalSubscriptionIdTest}")
    private String additionalSubscriptionIdTest;

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
        log.info("Called initialize(): {}", initializeTask == null || initializeTask.isDone());
        if ((initializeTask == null || initializeTask.isDone())) {
            initializeTask = cachedThreadPool.submit(() -> initialize());
        }
    }

    private boolean initialize() {
        var firstRun = true;
        while (!canConnect()) {
            if (!firstRun) {
                try {
                    log.info("Retrieving MQTT configuration in {}s ...",
                            WAIT_PERIOD_MS / 1000);
                    Thread.sleep(WAIT_PERIOD_MS);
                } catch (InterruptedException e) {
                    log.error("Error initializing MQTT client: ", e);
                }
            }
            reloadConfiguration();
            if (connectionConfiguration.useSelfSignedCertificate) {
                cert = c8yAgent.loadCertificateByName(connectionConfiguration.nameCertificate);
            }
            firstRun = false;
        }
        return true;
    }


    private void setConfigProperties() {
        this.configProps.put("mqttHost", ConnectorProperty.STRING_PROPERTY);
        this.configProps.put("mqttPort", ConnectorProperty.NUMERIC_PROPERTY);
        this.configProps.put("user", ConnectorProperty.STRING_PROPERTY);
        this.configProps.put("password", ConnectorProperty.SENSITIVE_STRING_PROPERTY);
        this.configProps.put("clientId", ConnectorProperty.STRING_PROPERTY);
        this.configProps.put("useTLS", ConnectorProperty.BOOLEAN_PROPERTY);
        this.configProps.put("useSelfSignedCertificate", ConnectorProperty.BOOLEAN_PROPERTY);
        this.configProps.put("fingerprintSelfSignedCertificate", ConnectorProperty.STRING_PROPERTY);
        this.configProps.put("nameCertificate", ConnectorProperty.STRING_PROPERTY);
    }

    @Override
    public Map<String, ConnectorProperty> getConfigProperties() {
        return this.configProps;
    }

    private void reloadConfiguration() {
        connectionConfiguration = connectionConfigurationComponent.loadConnectionConfiguration();
    }

    public void submitConnect() {
        // test if connect task is still running, then we don't need to start another
        // task
        log.info("Called connect(): connectTask.isDone() {}",
                connectTask == null || connectTask.isDone());
        if (connectTask == null || connectTask.isDone()) {
            connectTask = cachedThreadPool.submit(() -> connect());
        }
    }

    public void connect() {
        this.
        reloadConfiguration();
        log.info("Establishing the MQTT connection now - phase I: (isConnected:shouldConnect) ({}:{})", isConnected(),
                shouldConnect());
        if (isConnected()) {
            disconnect();
        }
        // stay in the loop until successful
        boolean successful = false;
        while (!successful) {
            var firstRun = true;
            while (!isConnected() && shouldConnect()) {
                log.info("Establishing the MQTT connection now - phase II: {}, {}",
                        ConfigurationConnection.isValid(connectionConfiguration), canConnect());
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
                        String prefix = connectionConfiguration.useTLS ? "ssl://" : "tcp://";
                        String broker = prefix + connectionConfiguration.mqttHost + ":"
                                + connectionConfiguration.mqttPort;
                        // mqttClient = new MqttClient(broker, MqttClient.generateClientId(), new
                        // MemoryPersistence());

                        // before we create a new mqttClient, test if there already exists on and try to
                        // close it
                        if (mqttClient != null) {
                            mqttClient.close(true);
                        }
                        if(dispatcher == null)
                            this.dispatcher = new AsynchronousDispatcher(this);
                        mqttClient = new MqttClient(broker,
                                connectionConfiguration.getClientId() + additionalSubscriptionIdTest,
                                new MemoryPersistence());
                        mqttCallback = new MQTTCallback(dispatcher, tenantId, getConntectorId());
                        mqttClient.setCallback(mqttCallback);
                        MqttConnectOptions connOpts = new MqttConnectOptions();
                        connOpts.setCleanSession(true);
                        connOpts.setAutomaticReconnect(false);
                        if (!StringUtils.isEmpty(connectionConfiguration.user)
                                && !StringUtils.isEmpty(connectionConfiguration.password)) {
                            connOpts.setUserName(connectionConfiguration.getUser());
                            connOpts.setPassword(connectionConfiguration.getPassword().toCharArray());
                        }
                        if (connectionConfiguration.useSelfSignedCertificate) {
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
                        log.info("Successfully connected to broker {}", mqttClient.getServerURI());
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
                log.info("Subscribing to topics was successful: {}", successful);
            } catch (Exception e) {
                log.error("Error on reconnect, retrying ... {} {}", e.getMessage(), e);
                log.debug("Stacktrace:", e);
                successful = false;
            }

        }
    }

    private boolean canConnect() {
        return ConfigurationConnection.isEnabled(connectionConfiguration)
                && (!connectionConfiguration.useSelfSignedCertificate
                        || (connectionConfiguration.useSelfSignedCertificate &&
                                cert != null));
    }

    private boolean shouldConnect() {
        return !ConfigurationConnection.isValid(connectionConfiguration)
                || ConfigurationConnection.isEnabled(connectionConfiguration);
    }

    public boolean isConnected() {
        return mqttClient != null ? mqttClient.isConnected() : false;
    }

    public void disconnect() {
        log.info("Disconnecting from MQTT broker: {}",
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
        connectionConfiguration = connectionConfigurationComponent.enableConnection(false);
        disconnect();
        mappingComponent.sendStatusService(tenantId, getServiceStatus());
    }

    public void connectToBroker() {
        connectionConfiguration = connectionConfigurationComponent.enableConnection(true);
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
        log.info("Unsubscribing from topic: {}", topic);
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
                log.info("Status: connectTask: {}, initializeTask: {}, isConnected: {}", statusConnectTask,
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
        } else if (ConfigurationConnection.isValid(connectionConfiguration)) {
            serviceStatus = ServiceStatus.configured();
        } else {
            serviceStatus = ServiceStatus.notReady();
        }
        return serviceStatus;
    }


    @Override
    public List<ProcessingContext<?>> test(String topic, boolean send, Map<String, Object> payload)
            throws Exception {
        String payloadMessage = objectMapper.writeValueAsString(payload);
        ConnectorMessage message = new ConnectorMessage();
        message.setPayload(payloadMessage.getBytes());
        if(dispatcher == null)
            dispatcher = new AsynchronousDispatcher(this);
        return dispatcher.processMessage(tenantId, getConntectorId(), topic, message, send).get();
    }

    public void reconnect() {
        disconnect();
        // invalidate broker client
        connectionConfiguration = null;
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
            log.info("Subscribing to topic: {}, qos: {}", mapping.subscriptionTopic, mapping.qos.ordinal());
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
                log.info("Subscribing to topic: {}, qos: {}", mapping.subscriptionTopic, mapping.qos.ordinal());
                try {
                    subscribe(mapping.subscriptionTopic, mapping.qos.ordinal());
                } catch (MqttException e1) {
                    log.error("Exception when subscribing to topic: {}, {}", mapping.subscriptionTopic, e1);
                }
            }
        }

    }

    @Override
    public List<Mapping> updateActiveSubscriptions(List<Mapping> updatedMappings, boolean reset) {
        if (reset) {
            activeSubscriptions.replace(tenantId, new HashMap<String, Integer>());
        }
        Map<String, Integer> updatedSubscriptionCache = new HashMap<String, Integer>();
        updatedMappings.forEach(mapping -> {
            if (!updatedSubscriptionCache.containsKey(mapping.subscriptionTopic)) {
                updatedSubscriptionCache.put(mapping.subscriptionTopic, Integer.valueOf(0));
            }
            Integer activeSubs = updatedSubscriptionCache.get(mapping.subscriptionTopic);
            activeSubs++;
        });

        // unsubscribe topics not used
        getActiveSubscriptions().get(tenantId).keySet().forEach((topic) -> {
            if (!updatedSubscriptionCache.containsKey(topic)) {
                log.info("Unsubscribe from topic: {}", topic);
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
                log.info("Subscribing to topic: {}, qos: {}", topic, qos);
                try {
                    subscribe(topic, qos);
                } catch (MqttException e1) {
                    log.error("Exception when subscribing to topic: {}, {}", topic, e1);
                    throw new RuntimeException(e1);
                }
            }
        });
        activeSubscriptions.replace(tenantId,updatedSubscriptionCache);
        return updatedMappings;
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
        log.info("Published outbound message: {} for mapping: {} on topic: {}", payload, context.getMapping().name, context.getResolvedPublishTopic());
    }

    @Override
    public Map<String, Integer> getActiveSubscriptions(String tenant) {
        return activeSubscriptions.get(tenant);
        //return getActiveSubscriptionMappingInbound().entrySet().stream()
        //        .map(entry -> new AbstractMap.SimpleEntry<String, Integer>(entry.getKey(), entry.getValue().getValue()))
        //        .collect(Collectors.toMap(Entry::getKey, Entry::getValue));
    }

}