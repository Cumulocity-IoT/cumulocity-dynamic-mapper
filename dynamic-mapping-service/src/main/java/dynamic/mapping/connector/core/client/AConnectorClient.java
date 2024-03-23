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
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import dynamic.mapping.connector.core.ConnectorSpecification;
import dynamic.mapping.model.Mapping;
import dynamic.mapping.model.MappingServiceRepresentation;
import dynamic.mapping.processor.inbound.AsynchronousDispatcherInbound;

import org.apache.commons.lang3.mutable.MutableInt;
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
import dynamic.mapping.configuration.ServiceConfigurationComponent;
import dynamic.mapping.connector.core.callback.ConnectorMessage;
import dynamic.mapping.connector.mqtt.ConnectorType;
import dynamic.mapping.core.C8YAgent;
import dynamic.mapping.core.ConfigurationRegistry;
import dynamic.mapping.core.ConnectorStatusEvent;
import dynamic.mapping.core.MappingComponent;
import dynamic.mapping.core.ConnectorStatus;
import dynamic.mapping.processor.model.ProcessingContext;

@Slf4j
public abstract class AConnectorClient {

    protected String connectorIdent;

    protected String connectorName;

    @Getter
    @Setter
    public ConnectorType connectorType;

    @Getter
    @Setter
    protected String tenant;

    @Getter
    protected MappingComponent mappingComponent;

    @Getter
    protected MappingServiceRepresentation mappingServiceRepresentation;

    @Getter
    protected ConnectorConfigurationComponent connectorConfigurationComponent;

    @Getter
    protected ServiceConfigurationComponent serviceConfigurationComponent;

    @Getter
    protected C8YAgent c8yAgent;

    @Getter
    @Setter
    protected AsynchronousDispatcherInbound dispatcher;

    protected ObjectMapper objectMapper;

    protected ConfigurationRegistry configurationRegistry;

    @Getter
    protected ExecutorService cachedThreadPool;

    private Future<?> connectTask;
    private ScheduledExecutorService housekeepingExecutor = Executors
            .newScheduledThreadPool(Runtime.getRuntime().availableProcessors());

    private Future<?> initializeTask;

    // structure < subscriptionTopic, numberMappings >
    public Map<String, MutableInt> activeSubscriptions = new HashMap<>();

    // structure < ident, mapping >
    // public Map<String, Mapping> subscribedMappings = new HashMap<>();
    @Getter
    private List<String> subscribedMappings = new ArrayList<>();

    private Instant start = Instant.now();

    private ConnectorStatus previousConnectorStatus = ConnectorStatus.UNKNOWN;

    @Getter
    @Setter
    public ConnectorConfiguration connectorConfiguration;

    @Getter
    @Setter
    public ServiceConfiguration serviceConfiguration;

    @Getter
    @Setter
    public ConnectorStatusEvent connectorStatus = ConnectorStatusEvent.unknown();

    public void submitInitialize() {
        // test if init task is still running, then we don't need to start another task
        log.info("Tenant {} - Called initialize(): {}", tenant, initializeTask == null || initializeTask.isDone());
        if ((initializeTask == null || initializeTask.isDone())) {
            initializeTask = cachedThreadPool.submit(() -> initialize());
        }
    }

    public abstract boolean initialize();

    public abstract ConnectorSpecification getSpecification();

    public abstract Boolean supportsWildcardsInTopic();

    public void loadConfiguration() {
        connectorConfiguration = connectorConfigurationComponent.getConnectorConfiguration(this.getConnectorIdent(),
                tenant);
        this.connectorConfiguration.copyPredefinedValues(getSpec());
        // get the latest serviceConfiguration from the Cumulocity backend in case
        // someone changed it in the meantime
        // update the in the registry
        serviceConfiguration = serviceConfigurationComponent.getServiceConfiguration(tenant);
        configurationRegistry.getServiceConfigurations().put(tenant, serviceConfiguration);

        // updateConnectorStatusAndSend(ConnectorStatus.CONFIGURED, true, true);
    }

    public abstract ConnectorSpecification getSpec();

    public void submitConnect() {
        loadConfiguration();
        // test if connect task is still running, then we don't need to start another
        // task
        log.info("Tenant {} - Called connect(): connectTask.isDone() {}", tenant,
                connectTask == null || connectTask.isDone());
        if (connectTask == null || connectTask.isDone()) {
            connectTask = cachedThreadPool.submit(() -> connect());
        }
    }

    public void submitDisconnect() {
        loadConfiguration();
        // test if connect task is still running, then we don't need to start another
        // task
        log.info("Tenant {} - Called submitDisconnect(): connectTask.isDone() {}", tenant,
                connectTask == null || connectTask.isDone());
        if (connectTask == null || connectTask.isDone()) {
            connectTask = cachedThreadPool.submit(() -> disconnect());
        }
    }

    public void submitHousekeeping() {
        log.info("Tenant {} - Called submitHousekeeping()", tenant);
        housekeepingExecutor.scheduleAtFixedRate(() -> runHousekeeping(), 0, 30,
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
    public boolean shouldConnect() {
        return isConfigValid(connectorConfiguration) && connectorConfiguration.isEnabled();
    }

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
    public abstract void subscribe(String topic, Integer qos) throws ConnectorException;

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

    public void runHousekeeping() {
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

            // check if connector is in DISCONNECTED state and then move it to CONFIGURED
            // state.
            if (ConnectorStatus.DISCONNECTED.equals(connectorStatus.status) && isConfigValid(connectorConfiguration)) {
                updateConnectorStatusAndSend(ConnectorStatus.CONFIGURED, true, true);
            } else {
                sendConnectorLifecycle();
            }
        } catch (Exception ex) {
            log.error("Tenant {} - Error during house keeping execution: ", tenant, ex);
        }
    }

    public boolean hasError() {
        return !(connectorStatus.status).equals(ConnectorStatus.FAILED);
    }

    public List<ProcessingContext<?>> test(String topic, boolean sendPayload, Map<String, Object> payload)
            throws Exception {
        String payloadMessage = objectMapper.writeValueAsString(payload);
        ConnectorMessage message = new ConnectorMessage();
        message.setTenant(tenant);
        message.setTopic(topic);
        message.setSendPayload(sendPayload);
        message.setConnectorIdent(getConnectorIdent());
        message.setPayload(payloadMessage.getBytes());
        return dispatcher.processMessage(message).get();
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
            subscribedMappings.remove(mapping.ident);
            if (activeSubs.intValue() <= 0) {
                try {
                    unsubscribe(mapping.subscriptionTopic);
                } catch (Exception e) {
                    log.error("Tenant {} - Exception when unsubscribing from topic: {}: ", tenant,
                            mapping.subscriptionTopic,
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
                log.debug("Tenant {} - Subscribing to topic: {}, qos: {}", tenant, mapping.subscriptionTopic,
                        mapping.qos.ordinal());
                try {
                    subscribe(mapping.subscriptionTopic, mapping.qos.ordinal());
                } catch (ConnectorException exp) {
                    log.error("Tenant {} - Exception when subscribing to topic: {}: ", tenant,
                            mapping.subscriptionTopic, exp);
                }
            } else if (subscriptionTopicChanged && activeMapping != null) {
                MutableInt activeMappingSubs = getActiveSubscriptions()
                        .get(activeMapping.subscriptionTopic);
                activeMappingSubs.subtract(1);
                if (activeMappingSubs.intValue() <= 0) {
                    try {
                        unsubscribe(mapping.subscriptionTopic);
                    } catch (Exception exp) {
                        log.error("Tenant {} - Exception when unsubscribing from topic: {}: ", tenant,
                                mapping.subscriptionTopic, exp);
                    }
                }
                updatedMappingSubs.add(1);
                if (!getActiveSubscriptions().containsKey(mapping.subscriptionTopic)) {
                    log.debug("Tenant {} - Subscribing to topic: {}, qos: {}", tenant, mapping.subscriptionTopic,
                            mapping.qos.ordinal());
                    try {
                        subscribe(mapping.subscriptionTopic, mapping.qos.ordinal());
                    } catch (ConnectorException exp) {
                        log.error("Tenant {} - Exception when subscribing to topic: {}: ", tenant,
                                mapping.subscriptionTopic, exp);
                    }
                }
            }
        }
    }

    public void updateActiveSubscriptions(List<Mapping> updatedMappings, boolean reset) {
        if (reset) {
            activeSubscriptions = new HashMap<String, MutableInt>();
            subscribedMappings = new ArrayList<>();
        }
        
        if (isConnected()) {
            Map<String, MutableInt> updatedSubscriptionCache = new HashMap<String, MutableInt>();
            updatedMappings.forEach(mapping -> {
                Boolean containsWildcards = mapping.subscriptionTopic.matches(".*[#\\+].*");
                if (supportsWildcardsInTopic() || !containsWildcards) {
                    if (!updatedSubscriptionCache.containsKey(mapping.subscriptionTopic)) {
                        updatedSubscriptionCache.put(mapping.subscriptionTopic, new MutableInt(0));
                    }
                    MutableInt activeSubs = updatedSubscriptionCache.get(mapping.subscriptionTopic);
                    activeSubs.add(1);
                    subscribedMappings.add (mapping.ident);
                }
            });

            // unsubscribe topics not used
            getActiveSubscriptions().keySet().forEach((topic) -> {
                if (!updatedSubscriptionCache.containsKey(topic)) {
                    log.debug("Tenant {} - Unsubscribe from topic: {}", tenant, topic);
                    try {
                        unsubscribe(topic);
                    } catch (Exception exp) {
                        log.error("Tenant {} - Exception when unsubscribing from topic: {}: ", topic, exp);
                        throw new RuntimeException(exp);
                    }
                }
            });

            // subscribe to new topics
            updatedSubscriptionCache.keySet().forEach((topic) -> {
                if (!getActiveSubscriptions().containsKey(topic)) {
                    int qos = updatedMappings.stream().filter(m -> m.subscriptionTopic.equals(topic))
                            .map(m -> m.qos.ordinal()).reduce(Integer::max).orElse(0);
                    log.debug("Tenant {} - Subscribing to topic: {}, qos: {}", tenant, topic, qos);
                    try {
                        subscribe(topic, qos);
                    } catch (ConnectorException exp) {
                        log.error("Tenant {} - Exception when subscribing to topic: {}: ", tenant, topic, exp);
                        throw new RuntimeException(exp);
                    }
                }
            });
            activeSubscriptions = updatedSubscriptionCache;
            log.info("Tenant {} - Updating subscriptions to topics was successful, activeSubscriptions on topic {}",
                    tenant, getActiveSubscriptions().size());
        }
    }

    public Map<String, MutableInt> getActiveSubscriptions() {
        return activeSubscriptions;
    }

    public void stopHousekeepingAndClose() {
        List<Runnable> stoppedTask = this.housekeepingExecutor.shutdownNow();
        // release all resources
        close();
        log.info("Tenant {} - Shutdown housekeepingTasks: {}", tenant, stoppedTask);
    }

    public void sendConnectorLifecycle() {
        // stop sending lifecycle event if connector is disabled
        if (serviceConfiguration.sendConnectorLifecycle
                && !(connectorStatus.getStatus().equals(previousConnectorStatus))) {
            previousConnectorStatus = connectorStatus.getStatus();
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
                    C8YAgent.STATUS_CONNECTOR_EVENT_TYPE,
                    DateTime.now(), mappingServiceRepresentation, tenant, stMap);
        }
    }

    public void sendSubscriptionEvents(String topic, String action) {
        if (serviceConfiguration.sendSubscriptionEvents) {
            String msg = action + " topic: " + topic;
            DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            Date now = new Date();
            String date = dateFormat.format(now);
            Map<String, String> stMap = Map.ofEntries(
                    entry("message", msg),
                    entry("connectorName", getConnectorName()),
                    entry("date", date));
            c8yAgent.createEvent(msg,
                    C8YAgent.STATUS_SUBSCRIPTION_EVENT_TYPE,
                    DateTime.now(), mappingServiceRepresentation, tenant, stMap);
        }
    }

    public void connectionLost(String closeMessage, Throwable closeException) {
        String tenant = getTenant();
        String connectorIdent = getConnectorIdent();
        if (closeException != null)
            log.error("Tenant {} - Connection lost to broker {}: {}", tenant, connectorIdent,
                    closeException.getMessage());
        closeException.printStackTrace();
        if (closeMessage != null)
            log.info("Tenant {} - Connection lost to MQTT broker: {}", tenant, closeMessage);
        reconnect();
    }

    public void updateConnectorStatusAndSend(ConnectorStatus status, boolean clearMessage, boolean send) {
        connectorStatus.updateStatus(status, clearMessage);
        if (send) {
            sendConnectorLifecycle();
        }
    }

    @Data
    @AllArgsConstructor
    public static class Certificate {
        private String fingerprint;
        private String certInPemFormat;
    }
}