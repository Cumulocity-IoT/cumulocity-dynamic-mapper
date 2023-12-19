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
 *
 * @authors Christof Strack, Stefan Witschel
 */

package dynamic.mapping.connector.core.client;

import static java.util.Map.entry;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import dynamic.mapping.connector.core.ConnectorSpecification;
import dynamic.mapping.model.Mapping;
import dynamic.mapping.model.MappingServiceRepresentation;
import dynamic.mapping.processor.inbound.AsynchronousDispatcherInbound;

import org.apache.commons.lang3.mutable.MutableInt;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.joda.time.DateTime;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import dynamic.mapping.configuration.ConnectorConfiguration;
import dynamic.mapping.configuration.ConnectorConfigurationComponent;
import dynamic.mapping.configuration.ServiceConfiguration;
import dynamic.mapping.connector.core.callback.ConnectorMessage;
import dynamic.mapping.core.C8YAgent;
import dynamic.mapping.core.ConnectorStatus;
import dynamic.mapping.core.MappingComponent;
import dynamic.mapping.core.Status;
import dynamic.mapping.processor.model.ProcessingContext;

@Slf4j
public abstract class AConnectorClient {

    public static final String STATUS_CONNECTOR_EVENT_TYPE = "d11r_connectorStatusEvent";
    public static final String STATUS_SUBSCRIPTION_EVENT_TYPE = "d11r_subscriptionEvent";
    public static final String CONNECTOR_FRAGMENT = "d11r_connector";

    @Getter
    @Setter
    public String tenant;

    @Getter
    public MappingComponent mappingComponent;

    @Getter
    public MappingServiceRepresentation mappingServiceRepresentation;

    @Getter
    public ConnectorConfigurationComponent connectorConfigurationComponent;

    @Getter
    public C8YAgent c8yAgent;

    @Getter
    public AsynchronousDispatcherInbound dispatcher;

    public ObjectMapper objectMapper;

    @Getter
    public ExecutorService cachedThreadPool;

    private Future<?> connectTask;
    private ScheduledExecutorService housekeepingExecutor = Executors
            .newScheduledThreadPool(Runtime.getRuntime().availableProcessors());

    private Future<?> initializeTask;
    private ScheduledFuture<?> housekeepingTask;

    public Map<String, MutableInt> activeSubscriptions = new HashMap<>();

    private Instant start = Instant.now();

    @Getter
    @Setter
    public ConnectorConfiguration configuration;


    @Getter
    @Setter
    public ServiceConfiguration serviceConfiguration;

    @Getter
    @Setter
    public ConnectorStatus connectorStatus = ConnectorStatus.unknown();

    public void submitInitialize() {
        // test if init task is still running, then we don't need to start another task
        log.info("Tenant {} - Called initialize(): {}", initializeTask == null || initializeTask.isDone(), tenant);
        if ((initializeTask == null || initializeTask.isDone())) {
            initializeTask = cachedThreadPool.submit(() -> initialize());
        }
    }

    public abstract boolean initialize();

    public abstract ConnectorSpecification getSpecification();

    public void loadConfiguration() {
        configuration = connectorConfigurationComponent.getConnectorConfiguration(this.getConnectorIdent(), tenant);
        connectorStatus.updateStatus(Status.CONFIGURED);
        connectorStatus.clearMessage();
        sendConnectorLifecycle();
        // log.info("Tenant {} - DANGEROUS-LOG reload configuration: {} , {}", tenant,
        // configuration,
        // configuration.properties);
    }

    public void submitConnect() {
        // test if connect task is still running, then we don't need to start another
        // task
        log.info("Tenant {} - Called connect(): connectTask.isDone() {}", tenant,
                connectTask == null || connectTask.isDone());
        if (connectTask == null || connectTask.isDone()) {
            connectTask = cachedThreadPool.submit(() -> connect());
        }
        connectorStatus.updateStatus(Status.CONNECTING);
        connectorStatus.clearMessage();
        sendConnectorLifecycle();
    }

    public void submitDisconnect() {
        // test if connect task is still running, then we don't need to start another
        // task
        log.info("Tenant {} - Called submitDisconnect(): connectTask.isDone() {}", tenant,
                connectTask == null || connectTask.isDone());
        if (connectTask == null || connectTask.isDone()) {
            connectTask = cachedThreadPool.submit(() -> disconnect());
        }
        connectorStatus.updateStatus(Status.DISCONNECTING);
        connectorStatus.clearMessage();
        sendConnectorLifecycle();
    }

    public void submitHouskeeping() {
        log.info("Tenant {} - Called submitHousekeeping()", tenant);
        this.housekeepingTask = housekeepingExecutor.scheduleAtFixedRate(() -> runHouskeeping(), 0, 30,
                TimeUnit.SECONDS);
    }

    /***
     * Connect to the broker
     ***/
    public abstract void connect();

    /***
     * Should return true when connector is enabled and provided properties are
     * valid
     ***/
    public abstract boolean shouldConnect();

    /***
     * Returns true if the connector is currently connected
     ***/
    public abstract boolean isConnected();

    /***
     * Disconnect the broker
     ***/
    public abstract void disconnect();

    /***
     * Close the connection to broker and release all resources
     ***/
    public abstract void close();

    /***
     * Returning the unique ID identifying the connector instance
     ***/
    public abstract String getConnectorIdent();

    /***
     * Returning the name of the connector instance
     ***/
    public abstract String getConnectorName();

    /***
     * Subscribe to a topic on the Broker
     ***/
    public abstract void subscribe(String topic, Integer qos) throws MqttException;

    /***
     * Unsubscribe a topic on the Broker
     ***/
    public abstract void unsubscribe(String topic) throws Exception;

    /***
     * Checks if the provided configuration is valid
     ***/
    public abstract boolean isConfigValid(ConnectorConfiguration configuration);

    /***
     * This method should publish Cumulocity received Messages to the Connector
     * using the provided ProcessContext
     * Relevant for Outbound Communication
     ***/
    public abstract void publishMEAO(ProcessingContext<?> context);

    public void runHouskeeping() {
        try {
            Instant now = Instant.now();
            // only log this for the first 180 seconds to reduce log amount
            if (Duration.between(start, now).getSeconds() < 1800) {
                String statusConnectTask = (connectTask == null ? "stopped"
                        : connectTask.isDone() ? "stopped" : "running");
                String statusInitializeTask = (initializeTask == null ? "stopped"
                        : initializeTask.isDone() ? "stopped" : "running");
                log.debug("Tenant {} - Status: connectTask: {}, initializeTask: {}, isConnected: {}", tenant,
                        statusConnectTask,
                        statusInitializeTask, isConnected());
            }
            mappingComponent.cleanDirtyMappings(tenant);
            mappingComponent.sendMappingStatus(tenant);
            mappingComponent.sendConnectorLifecycle(tenant, getConnectorStatus(), getConnectorIdent(), getConnectorName());
            sendConnectorLifecycle();
        } catch (Exception ex) {
            log.error("Error during house keeping execution: {}", ex);
        }
    }

    public boolean hasError() {
        return !(connectorStatus.status).equals(Status.FAILED);
    }

    public List<ProcessingContext<?>> test(String topic, boolean send, Map<String, Object> payload)
            throws Exception {
        String payloadMessage = objectMapper.writeValueAsString(payload);
        ConnectorMessage message = new ConnectorMessage();
        message.setPayload(payloadMessage.getBytes());
        if (dispatcher == null)
            dispatcher = new AsynchronousDispatcherInbound(this, c8yAgent, objectMapper, cachedThreadPool,
                    mappingComponent);
        return dispatcher.processMessage(tenant, this.getConnectorIdent(), topic, message, send).get();
    }

    public void reconnect() {
        disconnect();
        submitInitialize();
        submitConnect();
    }

    public void deleteActiveSubscription(Mapping mapping) {
        if (getActiveSubscriptions().containsKey(mapping.subscriptionTopic) && isConnected()) {
            MutableInt activeSubs = getActiveSubscriptions()
                    .get(mapping.subscriptionTopic);
            activeSubs.subtract(1);
            if (activeSubs.intValue() <= 0) {
                try {
                    unsubscribe(mapping.subscriptionTopic);
                } catch (Exception e) {
                    log.error("Exception when unsubscribing from topic: {}, {}", mapping.subscriptionTopic,
                            e);
                }
            }
        }
    }

    public void upsertActiveSubscription(Mapping mapping) {
        if (isConnected()) {
            // test if subscriptionTopic has changed
            Mapping activeMapping = null;
            Boolean create = true;
            Boolean subscriptionTopicChanged = false;
            Optional<Mapping> activeMappingOptional = mappingComponent.getCacheMappingInbound().get(tenant).values()
                    .stream()
                    .filter(m -> m.id.equals(mapping.id))
                    .findFirst();

            if (activeMappingOptional.isPresent()) {
                create = false;
                activeMapping = activeMappingOptional.get();
                subscriptionTopicChanged = !mapping.subscriptionTopic.equals(activeMapping.subscriptionTopic);
            }

            if (!getActiveSubscriptions().containsKey(mapping.subscriptionTopic)) {
                getActiveSubscriptions().put(mapping.subscriptionTopic, new MutableInt(0));
            }
            MutableInt updatedMappingSubs = getActiveSubscriptions()
                    .get(mapping.subscriptionTopic);

            // consider unsubscribing from previous subscription topic if it has changed
            if (create) {
                updatedMappingSubs.add(1);
                ;
                log.info("Tenant {} - Subscribing to topic: {}, qos: {}", tenant, mapping.subscriptionTopic,
                        mapping.qos.ordinal());
                try {
                    subscribe(mapping.subscriptionTopic, mapping.qos.ordinal());
                } catch (MqttException e1) {
                    log.error("Exception when subscribing to topic: {}, {}", mapping.subscriptionTopic, e1);
                }
            } else if (subscriptionTopicChanged && activeMapping != null) {
                MutableInt activeMappingSubs = getActiveSubscriptions()
                        .get(activeMapping.subscriptionTopic);
                activeMappingSubs.subtract(1);
                if (activeMappingSubs.intValue() <= 0) {
                    try {
                        unsubscribe(mapping.subscriptionTopic);
                    } catch (Exception e) {
                        log.error("Exception when unsubscribing from topic: {}, {}", mapping.subscriptionTopic, e);
                    }
                }
                updatedMappingSubs.add(1);
                if (!getActiveSubscriptions().containsKey(mapping.subscriptionTopic)) {
                    log.info("Tenant {} - Subscribing to topic: {}, qos: {}", tenant, mapping.subscriptionTopic,
                            mapping.qos.ordinal());
                    try {
                        subscribe(mapping.subscriptionTopic, mapping.qos.ordinal());
                    } catch (MqttException e1) {
                        log.error("Exception when subscribing to topic: {}, {}", mapping.subscriptionTopic, e1);
                    }
                }
            }
        }
    }

    public void updateActiveSubscriptions(List<Mapping> updatedMappings, boolean reset) {
        if (reset) {
            activeSubscriptions = new HashMap<String, MutableInt>();
        }
        if (isConnected()) {
            Map<String, MutableInt> updatedSubscriptionCache = new HashMap<String, MutableInt>();
            updatedMappings.forEach(mapping -> {
                if (!updatedSubscriptionCache.containsKey(mapping.subscriptionTopic)) {
                    updatedSubscriptionCache.put(mapping.subscriptionTopic, new MutableInt(0));
                }
                MutableInt activeSubs = updatedSubscriptionCache.get(mapping.subscriptionTopic);
                activeSubs.add(1);
            });

            // unsubscribe topics not used
            getActiveSubscriptions().keySet().forEach((topic) -> {
                if (!updatedSubscriptionCache.containsKey(topic)) {
                    log.info("Tenant {} - Unsubscribe from topic: {}", tenant, topic);
                    try {
                        unsubscribe(topic);
                    } catch (Exception e1) {
                        log.error("Tenant {} - Exception when unsubscribing from topic: {}, {}", topic, e1);
                        throw new RuntimeException(e1);
                    }
                }
            });

            // subscribe to new topics
            updatedSubscriptionCache.keySet().forEach((topic) -> {
                if (!getActiveSubscriptions().containsKey(topic)) {
                    int qos = updatedMappings.stream().filter(m -> m.subscriptionTopic.equals(topic))
                            .map(m -> m.qos.ordinal()).reduce(Integer::max).orElse(0);
                    log.info("Tenant {} - Subscribing to topic: {}, qos: {}", tenant, topic, qos);
                    try {
                        subscribe(topic, qos);
                    } catch (MqttException e1) {
                        log.error("Exception when subscribing to topic: {}, {}", topic, e1);
                        throw new RuntimeException(e1);
                    }
                }
            });
            activeSubscriptions = updatedSubscriptionCache;
            log.info("Tenant {} - Updating subscriptions to topics was successful", tenant);
        }
    }

    public Map<String, MutableInt> getActiveSubscriptions() {
        return activeSubscriptions;
    }

    public void stopHouskeepingAndClose() {
        List<Runnable> stoppedTask = this.housekeepingExecutor.shutdownNow();
        // release all resources
        close();
        log.info("Tenant {} - Shutdown houskeepingTasks: {}", tenant, stoppedTask);
    }

    public void sendConnectorLifecycle() {
        if (serviceConfiguration.sendConnectorLifecycle) {
            DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            Date now = new Date();
            String date = dateFormat.format(now);
            Map<String, String> stMap = Map.ofEntries(
                    entry("status", connectorStatus.getStatus().name()),
                    entry("message", connectorStatus.message),
                    entry("connectorName", getConnectorName()),
                    entry("connectorIdent", getConnectorIdent()),
                    entry("date", date));
            c8yAgent.createEvent("Connector status:" + connectorStatus.status,
                    STATUS_CONNECTOR_EVENT_TYPE,
                    DateTime.now(), mappingServiceRepresentation, tenant, stMap);
        }
    }

    public void sendSubscriptionEvents(String topic, String action) {
        if(serviceConfiguration.sendSubscriptionEvents) {
            String msg = action + " topic: " + topic;
            DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            Date now = new Date();
            String date = dateFormat.format(now);
            Map<String, String> stMap = Map.ofEntries(
                    entry("message", msg),
                    entry("connectorName", getConnectorName()),
                    entry("date", date));
            c8yAgent.createEvent(msg,
                    STATUS_SUBSCRIPTION_EVENT_TYPE,
                    DateTime.now(), mappingServiceRepresentation, tenant, stMap);
        } 
    }

    @Data
    @AllArgsConstructor
    public static class Certificate {
        private String fingerprint;
        private String certInPemFormat;
    }
}