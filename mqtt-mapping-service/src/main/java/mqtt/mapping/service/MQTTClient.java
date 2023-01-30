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

package mqtt.mapping.service;

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
import java.util.AbstractMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.mutable.MutableInt;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import mqtt.mapping.configuration.ConfigurationConnection;
import mqtt.mapping.configuration.ConnectionConfigurationComponent;
import mqtt.mapping.configuration.ServiceConfiguration;
import mqtt.mapping.configuration.ServiceConfigurationComponent;
import mqtt.mapping.core.C8YAgent;
import mqtt.mapping.core.MappingComponent;
import mqtt.mapping.core.Operation;
import mqtt.mapping.core.ServiceOperation;
import mqtt.mapping.core.ServiceStatus;
import mqtt.mapping.model.Direction;
import mqtt.mapping.model.InnerNode;
import mqtt.mapping.model.Mapping;
import mqtt.mapping.model.MappingNode;
import mqtt.mapping.model.ResolveException;
import mqtt.mapping.model.TreeNode;
//import mqtt.mapping.processor.SynchronousDispatcher;
import mqtt.mapping.processor.AsynchronousDispatcher;
import mqtt.mapping.processor.model.ProcessingContext;

@Slf4j
@Configuration
@EnableScheduling
@Service
public class MQTTClient {

    private static final String ADDITION_TEST_DUMMY = "";
    private static final int WAIT_PERIOD_MS = 10000;
    public static final Long KEY_MONITORING_UNSPECIFIED = -1L;
    private static final String STATUS_MQTT_EVENT_TYPE = "mqtt_status_event";

    private ConfigurationConnection connectionConfiguration;
    private Certificate cert;

    @Getter
    private ServiceConfiguration serviceConfiguration;

    @Autowired
    private ConnectionConfigurationComponent connectionConfigurationComponent;

    @Autowired
    private ServiceConfigurationComponent serviceConfigurationComponent;

    @Autowired
    private MappingComponent mappingStatusComponent;

    private MqttClient mqttClient;

    @Autowired
    private C8YAgent c8yAgent;

    // @Autowired
    // private SynchronousDispatcher dispatcher;

    @Autowired
    private AsynchronousDispatcher dispatcher;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    @Qualifier("cachedThreadPool")
    private ExecutorService cachedThreadPool;

    private Future<Boolean> connectTask;
    private Future<Boolean> initializeTask;

    private Map<String, MutableInt> activeSubscriptionCache = new HashMap<String, MutableInt>();

    @Getter
    private Map<String, Mapping> activeIncomingMappings = new HashMap<String, Mapping>();

    @Getter
    private Map<String, Mapping> activeOutgoingMappings = new HashMap<String, Mapping>();

    private TreeNode mappingTree = InnerNode.createRootNode();;

    private Instant start = Instant.now();

    @Data
    @AllArgsConstructor
    public static class Certificate {
        private String fingerprint;
        private String certInPemFormat;
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

    public void reloadConfiguration() {
        serviceConfiguration = c8yAgent.loadServiceConfiguration();
        connectionConfiguration = c8yAgent.loadConnectionConfiguration();
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

    private boolean connect() throws Exception {
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

                        mqttClient = new MqttClient(broker, connectionConfiguration.getClientId() + ADDITION_TEST_DUMMY,
                                new MemoryPersistence());
                        mqttClient.setCallback(dispatcher);
                        MqttConnectOptions connOpts = new MqttConnectOptions();
                        connOpts.setCleanSession(true);
                        connOpts.setAutomaticReconnect(false);
                        if (!StringUtils.isEmpty(connectionConfiguration.user)
                                && !StringUtils.isEmpty(connectionConfiguration.password)) {
                            connOpts.setUserName(connectionConfiguration.getUser());
                            connOpts.setPassword(connectionConfiguration.getPassword().toCharArray());
                        }
                        if (connectionConfiguration.useSelfSignedCertificate) {
                            log.debug("Using certificate: {}", cert.certInPemFormat);

                            try {
                                KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
                                trustStore.load(null, null);
                                trustStore.setCertificateEntry("Custom CA",
                                        (X509Certificate) CertificateFactory.getInstance("X509")
                                                .generateCertificate(new ByteArrayInputStream(
                                                        cert.certInPemFormat.getBytes(Charset.defaultCharset()))));

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
                                throw new Exception(e);
                            }
                        }
                        mqttClient.connect(connOpts);
                        log.info("Successfully connected to broker {}", mqttClient.getServerURI());
                        c8yAgent.createEvent("Successfully connected to broker " + mqttClient.getServerURI(),
                                STATUS_MQTT_EVENT_TYPE,
                                DateTime.now(), null);

                    }
                } catch (MqttException e) {
                    log.error("Error on reconnect: {}", e.getMessage());
                    log.debug("Stacktrace:", e);
                }
                firstRun = false;
            }

            // try {
            // Thread.sleep(WAIT_PERIOD_MS / 10);
            // } catch (InterruptedException e) {
            // log.error("Error on reconnect: ", e);
            // }

            try {
                subscribe("$SYS/#", 0);
                activeSubscriptionCache = new HashMap<String, MutableInt>();
                activeIncomingMappings = new HashMap<String, Mapping>();
                activeOutgoingMappings = new HashMap<String, Mapping>();
                rebuildIncomingMappingCache();
                rebuildOutgoingMappingCache();
                successful = true;
            } catch (MqttException e) {
                log.error("Error on reconnect, retrying ... {]", e.getMessage());
                log.debug("Stacktrace:", e);
                successful = false;

            }

        }
        return true;
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
                activeSubscriptionCache.entrySet().forEach(entry -> {
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
                log.debug("Disconnected from MQTT broker II: {}", mqttClient.getServerURI());
            }
        } catch (MqttException e) {
            log.error("Error on disconnecting MQTT Client: ", e);
        }
    }

    public void disconnectFromBroker() {
        connectionConfiguration = connectionConfigurationComponent.enableConnection(false);
        disconnect();
        c8yAgent.sendStatusService(getServiceStatus());
    }

    public void connectToBroker() {
        connectionConfiguration = connectionConfigurationComponent.enableConnection(true);
        submitConnect();
        c8yAgent.sendStatusService(getServiceStatus());
    }

    private TreeNode rebuildMappingTree(List<Mapping> mappings) {
        InnerNode in = InnerNode.createRootNode();
        mappings.forEach(m -> {
            try {
                in.addMapping(m);
            } catch (ResolveException e) {
                log.error("Could not add mapping {}, ignoring mapping", m);
            }
        });
        return in;
    }

    public void subscribe(String topic, Integer qos) throws MqttException {

        log.debug("Subscribing on topic: {}", topic);
        c8yAgent.createEvent("Subscribing on topic " + topic, STATUS_MQTT_EVENT_TYPE, DateTime.now(), null);
        if (qos != null)
            mqttClient.subscribe(topic, qos);
        else
            mqttClient.subscribe(topic);
        log.debug("Successfully subscribed on topic: {}", topic);

    }

    private void unsubscribe(String topic) throws MqttException {
        log.info("Unsubscribing from topic: {}", topic);
        c8yAgent.createEvent("Unsubscribing on topic " + topic, STATUS_MQTT_EVENT_TYPE, DateTime.now(), null);
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
            c8yAgent.cleanDirtyMappings();
            c8yAgent.sendStatusMapping();
            c8yAgent.sendStatusService(getServiceStatus());
        } catch (Exception ex) {
            log.error("Error during house keeping execution: {}", ex);
        }
    }

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

    public TreeNode getActiveMappingTree() {
        return mappingTree;
    }

    public void runOperation(ServiceOperation operation) throws Exception {
        if (operation.getOperation().equals(Operation.RELOAD_MAPPINGS)) {
            rebuildIncomingMappingCache();
            rebuildOutgoingMappingCache();
        } else if (operation.getOperation().equals(Operation.CONNECT)) {
            connectToBroker();
        } else if (operation.getOperation().equals(Operation.DISCONNECT)) {
            disconnectFromBroker();
        } else if (operation.getOperation().equals(Operation.REFRESH_STATUS_MAPPING)) {
            c8yAgent.sendStatusMapping();
        } else if (operation.getOperation().equals(Operation.RESET_STATUS_MAPPING)) {
            mappingStatusComponent.resetMappingStatus();
        } else if (operation.getOperation().equals(Operation.RELOAD_EXTENSIONS)) {
            c8yAgent.reloadExtensions();
        } else if (operation.getOperation().equals(Operation.ACTIVATE_MAPPING)) {
            c8yAgent.setActivationMapping(operation.getParameter());
        }
    }

    public List<ProcessingContext<?>> test(String topic, boolean send, Map<String, Object> payload)
            throws Exception {
        String payloadMessage = objectMapper.writeValueAsString(payload);
        MqttMessage mqttMessage = new MqttMessage();
        mqttMessage.setPayload(payloadMessage.getBytes());
        return dispatcher.processMessage(topic, mqttMessage, send).get();
    }

    public void saveServiceConfiguration(ServiceConfiguration configuration) throws JsonProcessingException {
        serviceConfiguration = configuration;
        serviceConfigurationComponent.saveServiceConfiguration(configuration);
    }

    public List<Mapping> resolveMappings(String topic) throws ResolveException {
        List<TreeNode> resolvedMappings = getActiveMappingTree()
                .resolveTopicPath(Mapping.splitTopicIncludingSeparatorAsList(topic));
        return resolvedMappings.stream().filter(tn -> tn instanceof MappingNode)
                .map(mn -> ((MappingNode) mn).getMapping()).collect(Collectors.toList());
    }

    public void reconnect() {
        disconnect();
        // invalidate broker client
        connectionConfiguration = null;
        submitInitialize();
        submitConnect();
    }

    public void deleteFromMappingCache(Mapping mr) {
        if (Direction.OUTGOING.equals(mr.direction)) {
            // TODO update activeOutgoingMapping
        } else {
            // find mapping for given id to work with the subscriptionTopic of the mapping
            Optional<Mapping> activeIncomingMapping = activeIncomingMappings.values().stream()
                    .filter(m -> m.id.equals(mr.id)).findFirst();
            if (!activeIncomingMapping.isPresent()) {
                return;
            }
            Mapping mapping = activeIncomingMapping.get();
            if (activeSubscriptionCache.containsKey(mapping.subscriptionTopic)) {
                MutableInt activeSubs = activeSubscriptionCache.get(mapping.subscriptionTopic);
                activeSubs.subtract(1);
                if (activeSubs.intValue() <= 0) {
                    try {
                        mqttClient.unsubscribe(mapping.subscriptionTopic);
                    } catch (MqttException e) {
                        log.error("Exception when unsubscribing from topic: {}, {}", mapping.subscriptionTopic, e);
                    }
                }
            }
            deleteFromMappingTree(mapping);
            activeIncomingMappings.remove(mr);
        }
    }

    public void upsertInMappingCache(Mapping mapping) {
        // test if subsctiptionTopic has changed
        Mapping activeMapping = null;
        Boolean create = true;
        Boolean subscriptionTopicChanged = false;
        if (Direction.OUTGOING.equals(mapping.direction)) {
            // TODO update activeOutgoingMapping
        } else {
            Optional<Mapping> activeMappingOptional = activeIncomingMappings.values().stream()
                    .filter(m -> m.id.equals(mapping.id))
                    .findFirst();

            if (activeMappingOptional.isPresent()) {
                create = false;
                activeMapping = activeMappingOptional.get();
                subscriptionTopicChanged = !mapping.subscriptionTopic.equals(activeMapping.subscriptionTopic);
            }

            if (!activeSubscriptionCache.containsKey(mapping.subscriptionTopic)) {
                activeSubscriptionCache.put(mapping.subscriptionTopic, new MutableInt(0));
            }
            MutableInt updatedMappingSubs = activeSubscriptionCache.get(mapping.subscriptionTopic);

            // consider unsubscribing from previous subscription topic if it has changed
            if (create) {
                updatedMappingSubs.add(1);
                log.info("Subscribing to topic: {}, qos: {}", mapping.subscriptionTopic, mapping.qos.ordinal());
                try {
                    subscribe(mapping.subscriptionTopic, mapping.qos.ordinal());
                } catch (MqttException e1) {
                    log.error("Exception when subscribing to topic: {}, {}", mapping.subscriptionTopic, e1);
                }
            } else if (subscriptionTopicChanged) {
                MutableInt activeMappingSubs = activeSubscriptionCache.get(activeMapping.subscriptionTopic);
                activeMappingSubs.subtract(1);
                if (activeMappingSubs.intValue() <= 0) {
                    try {
                        mqttClient.unsubscribe(mapping.subscriptionTopic);
                    } catch (MqttException e) {
                        log.error("Exception when unsubscribing from topic: {}, {}", mapping.subscriptionTopic, e);
                    }
                }
                updatedMappingSubs.add(1);
                if (!activeSubscriptionCache.containsKey(mapping.subscriptionTopic)) {
                    log.info("Subscribing to topic: {}, qos: {}", mapping.subscriptionTopic, mapping.qos.ordinal());
                    try {
                        subscribe(mapping.subscriptionTopic, mapping.qos.ordinal());
                    } catch (MqttException e1) {
                        log.error("Exception when subscribing to topic: {}, {}", mapping.subscriptionTopic, e1);
                    }
                }
            }

            deleteFromMappingTree(mapping);
            addToMappingTree(mapping);
            activeIncomingMappings.put(mapping.id, mapping);
        }
    }

    private void addToMappingTree(Mapping mapping) {
        try {
            ((InnerNode) mappingTree).addMapping(mapping);
        } catch (ResolveException e) {
            log.error("Could not add mapping {}, ignoring mapping", mapping);
        }
    }

    private void deleteFromMappingTree(Mapping mapping) {
        try {
            ((InnerNode) mappingTree).deleteMapping(mapping);
        } catch (ResolveException e) {
            log.error("Could not delete mapping {}, ignoring mapping", mapping);
        }
    }

    public void rebuildOutgoingMappingCache() {
        //TODO add implementation
        activeOutgoingMappings = new HashMap<String, Mapping>();
    }

    public void rebuildIncomingMappingCache() {
        List<Mapping> updatedMappings = c8yAgent.getMappings();
        Map<String, MutableInt> updatedSubscriptionCache = new HashMap<String, MutableInt>();
        updatedMappings.forEach(mapping -> {
            if (!updatedSubscriptionCache.containsKey(mapping.subscriptionTopic)) {
                updatedSubscriptionCache.put(mapping.subscriptionTopic, new MutableInt(0));
            }
            MutableInt activeSubs = updatedSubscriptionCache.get(mapping.subscriptionTopic);
            activeSubs.add(1);
        });

        // unsubscribe topics not used
        activeSubscriptionCache.keySet().forEach((topic) -> {
            if (!updatedSubscriptionCache.containsKey(topic)) {
                log.info("Unsubscribe from topic: {}", topic);
                try {
                    unsubscribe(topic);
                } catch (MqttException e1) {
                    log.error("Exception when unsubscribing from topic: {}, {}", topic, e1);
                }
            }
        });

        // subscribe to new topics
        updatedSubscriptionCache.keySet().forEach((topic) -> {
            if (!activeSubscriptionCache.containsKey(topic)) {
                int qos = updatedMappings.stream().filter(m -> m.subscriptionTopic.equals(topic))
                        .map(m -> m.qos.ordinal()).reduce(Integer::max).orElse(0);
                log.info("Subscribing to topic: {}, qos: {}", topic, qos);
                try {
                    subscribe(topic, qos);
                } catch (MqttException e1) {
                    log.error("Exception when subscribing to topic: {}, {}", topic, e1);
                }
            }
        });
        activeSubscriptionCache = updatedSubscriptionCache;
        activeIncomingMappings = updatedMappings.stream()
                .collect(Collectors.toMap(Mapping::getId, Function.identity()));
        // update mappings tree
        mappingTree = rebuildMappingTree(updatedMappings);
    }

    public Map<String, Integer> getActiveSubscriptions() {
        return activeSubscriptionCache.entrySet().stream()
                .map(entry -> new AbstractMap.SimpleEntry<String, Integer>(entry.getKey(), entry.getValue().getValue()))
                .collect(Collectors.toMap(Entry::getKey, Entry::getValue));
    }

}