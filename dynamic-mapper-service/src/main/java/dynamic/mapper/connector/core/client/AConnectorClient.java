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

package dynamic.mapper.connector.core.client;

import static java.util.Map.entry;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.commons.lang3.mutable.MutableBoolean;
import org.apache.commons.lang3.mutable.MutableInt;
import org.joda.time.DateTime;

import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.configuration.ConnectorConfiguration;
import dynamic.mapper.configuration.ConnectorId;
import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.ConnectorSpecification;
import dynamic.mapper.connector.core.callback.GenericMessageCallback;
import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.core.ConnectorStatus;
import dynamic.mapper.core.ConnectorStatusEvent;
import dynamic.mapper.model.DeploymentMapEntry;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.LoggingEventType;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.Qos;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.service.ConnectorConfigurationService;
import dynamic.mapper.service.MappingService;
import dynamic.mapper.service.ServiceConfigurationService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class AConnectorClient {

    @Data
    @AllArgsConstructor
    public static class Certificate {
        private String fingerprint;
        private String certInPemFormat;
    }

    private static final int HOUSEKEEPING_INTERVAL_SECONDS = 30;

    protected static final int WAIT_PERIOD_MS = 10000;

    protected String connectorIdentifier;

    protected String connectorName;

    protected ConnectorId connectorId;

    protected String additionalSubscriptionIdTest;

    protected MutableBoolean connectionState = new MutableBoolean(false);

    @Getter
    @Setter
    public ConnectorSpecification connectorSpecification;

    @Getter
    @Setter
    public ConnectorType connectorType;

    @Getter
    @Setter
    public boolean singleton;

    @Getter
    @Setter
    protected String tenant;

    @Getter
    protected MappingService mappingService;

    @Getter
    protected ConnectorConfigurationService connectorConfigurationService;

    @Getter
    protected ServiceConfigurationService serviceConfigurationService;

    @Getter
    protected C8YAgent c8yAgent;

    @Getter
    @Setter
    protected GenericMessageCallback dispatcher;

    protected ObjectMapper objectMapper;

    protected ConfigurationRegistry configurationRegistry;

    @Getter
    protected ExecutorService virtualThreadPool;

    private Future<?> connectTask;
    private ScheduledExecutorService housekeepingExecutor = Executors
            .newScheduledThreadPool(Runtime.getRuntime().availableProcessors());

    private Future<?> initializeTask;
    private CompletableFuture<Void> disconnectTask;

    // keeps track how many active mappings use this topic as mappingTopic:
    // structure < mappingTopic, numberMappings >
    protected Map<String, MutableInt> countSubscriptionsPerTopicInbound = new HashMap<>();

    // keeps track if a specific mapping is deployed in this connector:
    // a) is it active,
    // b) does it comply with the capabilities of the connector, i.e. supports
    // wildcards
    // structure < identifier, mapping >
    protected Map<String, Mapping> mappingsDeployedInbound = new ConcurrentHashMap<>();

    // structure < identifier, mapping >
    protected Map<String, Mapping> mappingsDeployedOutbound = new ConcurrentHashMap<>();

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
    public ConnectorStatusEvent connectorStatus;

    @Getter
    @Setter
    public Boolean supportsMessageContext;

    public static final String MQTT_PROTOCOL_MQTT = "mqtt://";

    public static final String MQTT_PROTOCOL_MQTTS = "mqtts://";

    public static final String MQTT_PROTOCOL_WS = "ws://";

    public static final String MQTT_PROTOCOL_WSS = "wss://";

    public static final String MQTT_VERSION_3_1_1 = "3.1.1";

    public static final String MQTT_VERSION_5_0 = "5.0";

    public abstract boolean initialize();

    public abstract Boolean supportsWildcardsInTopic();

    /**
     * Connect to the broker
     **/
    public abstract void connect();

    /**
     * This method if specifically for Kafka, since it does not have the concept of
     * a client. Kafka rather supports consumer on topic level. They can fail to
     * connect
     **/
    public abstract void monitorSubscriptions();

    /**
     * Returns true if the connector is currently connected
     **/
    public abstract boolean isConnected();

    /**
     * Disconnect the broker
     **/
    public abstract void disconnect();

    /**
     * Close the connection to server and release all resources
     **/
    public abstract void close();

    /**
     * Returning the unique ID identifying the connector instance
     **/
    public abstract String getConnectorIdentifier();

    /**
     * Returning the name of the connector instance
     **/
    public abstract String getConnectorName();

    /**
     * Subscribe to a topic on the Broker
     **/
    public abstract void subscribe(String topic, Qos qos) throws ConnectorException;

    /**
     * Unsubscribe a topic on the Broker
     **/
    public abstract void unsubscribe(String topic) throws Exception;

    /**
     * Checks if the provided configuration is valid
     **/
    public abstract boolean isConfigValid(ConnectorConfiguration configuration);

    /**
     * This method should publish Cumulocity received Messages to the Connector
     * using the provided ProcessContext
     * Relevant for Outbound Communication
     **/
    public abstract void publishMEAO(ProcessingContext<?> context);

    public CompletableFuture<Void> submitInitialize() {
        if (initializeTask == null || initializeTask.isDone()) {
            log.debug("{} - Initializing...", tenant);
            initializeTask = CompletableFuture
                    .runAsync(this::initialize, virtualThreadPool)
                    .whenComplete((result, throwable) -> {
                        if (throwable != null) {
                            log.error("{} - Initialization failed: {}", tenant, getConnectorName(), throwable);
                            // Handle the exception (e.g., retry, notify, etc.)
                        } else {
                            log.debug("{} - Initialized successfully: {}", getConnectorName(), tenant);
                        }
                    });
        }
        return (CompletableFuture<Void>) initializeTask;
    }

    public CompletableFuture<Void> submitConnect() {
        loadConfiguration();
        if (connectTask == null || connectTask.isDone()) {
            log.debug("{} - Connecting...", tenant);
            connectTask = CompletableFuture
                    .runAsync(this::connect, virtualThreadPool)
                    .whenComplete((result, throwable) -> {
                        if (throwable != null) {
                            log.error("{} - Connection failed: {}", tenant, getConnectorName(), throwable);
                            // Handle the exception (e.g., retry, notify, etc.)
                        } else {
                            log.debug("{} - Connected successfully: {}", getConnectorName(), tenant);
                        }
                    });
        }
        return (CompletableFuture<Void>) connectTask;
    }

    public CompletableFuture<Void> submitDisconnect() {
        loadConfiguration();

        // Cancel connect task if it's running
        if (connectTask != null && (!connectTask.isDone() || !connectTask.isCancelled())) {
            log.debug("{} - Cancelling ongoing connection for: {}", tenant, getConnectorName());
            connectTask.cancel(true);
        }

        if (disconnectTask == null || disconnectTask.isDone()) {
            log.debug("{} - Disconnecting...", tenant);
            disconnectTask = CompletableFuture
                    .runAsync(this::disconnect, virtualThreadPool)
                    .whenComplete((result, throwable) -> {
                        if (throwable != null) {
                            log.error("{} - Disconnection failed: {}", tenant, getConnectorName(), throwable);
                            // Handle the exception (e.g., cleanup, notify, etc.)
                        } else {
                            log.debug("{} - Disconnected successfully: {}", getConnectorName(), tenant);
                        }
                    });
        }
        return (CompletableFuture<Void>) disconnectTask;
    }

    public void submitHousekeeping() {
        log.debug("{} - Starting housekeeping for {} ...", tenant, connectorName);
        housekeepingExecutor.scheduleAtFixedRate(
                this::runHousekeeping,
                0,
                HOUSEKEEPING_INTERVAL_SECONDS,
                TimeUnit.SECONDS);
    }

    public void loadConfiguration() {
        connectorConfiguration = connectorConfigurationService
                .getConnectorConfiguration(getConnectorIdentifier(), tenant);
        connectorConfiguration.copyPredefinedValues(getConnectorSpecification());

        serviceConfiguration = serviceConfigurationService.getServiceConfiguration(tenant);
        configurationRegistry.addServiceConfiguration(tenant, serviceConfiguration);
    }

    /**
     * Should return true when connector is enabled and provided properties are
     * valid
     **/
    public boolean shouldConnect() {
        return isConfigValid(connectorConfiguration) && connectorConfiguration.isEnabled();
    }

    /**
     * This method is called when a mapping is created or an existing mapping is
     * updated.
     * It maintains a list of the active subscriptions for this connector.
     * When a mapping idd deleted or deactivated, then it is verified how many other
     * mapping use the same mappingTopic. If there are no other mapping using
     * the same mappingTopic the mappingTopic is unsubscribed.
     * Only inactive mappings can be updated except activation/deactivation.
     **/

    /**
     * This method is maintains the list of mappings that are active for this
     * connector.
     * If a connector does not support wildcards in this topic subscriptions, i.e.
     * Kafka, the mapping can't be activated for this connector
     **/
    public void initializeSubscriptionsInbound(List<Mapping> updatedMappings, boolean reset, boolean cleanSession) {
        mappingsDeployedInbound = new ConcurrentHashMap<>();
        if (reset) {
            countSubscriptionsPerTopicInbound = new HashMap<>();
        }

        if (isConnected()) {
            Map<String, MutableInt> updatedCountSubscriptions = new HashMap<>();
            updatedMappings.forEach(mapping -> {
                if (isMappingValidForDeployment(mapping) && mapping.active) {
                    updateSubscriptionCacheInbound(mapping, updatedCountSubscriptions);
//                    if (mapping.mappingType.equals(MappingType.CODE_BASED)) {
//                        configurationRegistry.updateGraalsSourceMapping(tenant, mapping.id, mapping.getCode());
//                    }
                }
            });
            // Update subscriptions only in case of a cleanSession
            // TODO: how do we maintain our internal caches activeSubscriptionsInbound, ...
            // in case of cleanSession=false?
            // if (cleanSession){
            handleSubscriptionUpdatesInbound(updatedCountSubscriptions, updatedMappings);
            // }

            countSubscriptionsPerTopicInbound = updatedCountSubscriptions;
            log.info("{} - Updated subscriptions for connector: {}, active subscriptions: {}",
                    tenant, getConnectorName(), getCountSubscriptionsPerTopicInboundView().size());
        }
    }

    /**
     * Checks if the mapping is valid for deployment in this connector.
     * It checks if the mapping contains wildcards and if the connector supports
     * wildcards in the topic.
     * It also checks if the mapping is active and deployed in this connector.
     **/
    private boolean isMappingValidForDeployment(Mapping mapping) {
        boolean containsWildcards = mapping.mappingTopic.matches(".*[#+].*");
        boolean validDeployment = supportsWildcardsInTopic() || !containsWildcards;

        if (!validDeployment) {
            log.warn("{} - Mapping {} contains wildcards, not supported by connector {}",
                    tenant, mapping.getId(), connectorName);
            return false;
        }

        List<String> deploymentMapEntry = mappingService.getDeploymentMapEntry(tenant, mapping.identifier);
        boolean isDeployed = deploymentMapEntry != null &&
                deploymentMapEntry.contains(getConnectorIdentifier());

        // return validDeployment && isDeployed;
        return validDeployment && isDeployed;
    }

    private void updateSubscriptionCacheInbound(Mapping mapping, Map<String, MutableInt> subscriptionCache) {
        subscriptionCache.computeIfAbsent(mapping.mappingTopic, k -> new MutableInt(0)).increment();
        mappingsDeployedInbound.put(mapping.identifier, mapping);
    }

    private void handleSubscriptionUpdatesInbound(Map<String, MutableInt> updatedSubscriptionCache,
            List<Mapping> updatedMappings) {
        // Unsubscribe from unused topics
        unsubscribeUnusedTopics(updatedSubscriptionCache);
        // Subscribe to new topics
        subscribeToNewTopics(updatedSubscriptionCache, updatedMappings);
    }

    private void unsubscribeUnusedTopics(Map<String, MutableInt> updatedSubscriptionCache) {
        getCountSubscriptionsPerTopicInboundView().keySet().stream()
                .filter(topic -> !updatedSubscriptionCache.containsKey(topic))
                .forEach(topic -> {
                    try {
                        log.debug("{} - Unsubscribing from topic: [{}]", tenant, topic);
                        unsubscribe(topic);
                    } catch (Exception exp) {
                        log.error("{} - Error unsubscribing from topic: [{}]", tenant, topic, exp);
                    }
                });
    }

    private void subscribeToNewTopics(Map<String, MutableInt> updatedSubscriptionCache,
            List<Mapping> updatedMappings) {
        updatedSubscriptionCache.keySet().stream()
                .filter(topic -> !getCountSubscriptionsPerTopicInboundView().containsKey(topic))
                .forEach(topic -> {
                    Qos qos = determineMaxQosInbound(topic, updatedMappings);
                    try {
                        subscribe(topic, qos);
                        log.info("{} - Subscribed to topic:[{}] for connector: {}, QoS: {}",
                                tenant, topic,
                                connectorName, qos);
                    } catch (ConnectorException exp) {
                        log.error("{} - Error subscribing to topic:[{}]", tenant, topic, exp);
                    }
                });
    }

    public Qos determineMaxQosInbound(String topic, List<Mapping> mappings) {
        int qosOrdinal = mappings.stream()
                .filter(m -> m.mappingTopic.equals(topic) && m.active)
                .map(m -> m.qos.ordinal())
                .max(Integer::compareTo)
                .orElse(0);
        return Qos.values()[qosOrdinal];
    }

    public Qos determineMaxQosInbound(List<Mapping> mappings) {
        int qosOrdinal = mappings.stream()
                .filter(m -> m.active)
                .map(m -> m.qos.ordinal())
                .max(Integer::compareTo)
                .orElse(0);
        return Qos.values()[qosOrdinal];
    }

    public Qos determineMaxQosOutbound(List<Mapping> mappings) {
        int qosOrdinal = mappings.stream()
                .filter(m -> m.active)
                .map(m -> m.qos.ordinal())
                .max(Integer::compareTo)
                .orElse(0);
        return Qos.values()[qosOrdinal];
    }

    /**
     * This method is called when a mapping is created or an existing mapping is
     * updated.
     * It maintains a list of the active subscriptions for this connector.
     * When a mapping id deleted or deactivated, then it is verified how many other
     * mapping use the same mappingTopic. If there are no other mapping using
     * the same mappingTopic the mappingTopic is unsubscribed.
     * Only inactive mappings can be updated except activation/deactivation.
     **/
    public boolean updateSubscriptionForInbound(Mapping mapping, Boolean create, Boolean activationChanged) {
        boolean result = true;
        if (isConnected()) {
            // always ensure that a mapping can be deactivated
            boolean isDeactivation = activationChanged && !mapping.active;
            if (isMappingValidForDeployment(mapping) || isDeactivation) {
                handleSubscriptionForInbound(mapping, create, activationChanged);
            } else {
                List<String> deploymentMapEntry = mappingService.getDeploymentMapEntry(tenant, mapping.identifier);
                boolean isDeployed = deploymentMapEntry != null &&
                        deploymentMapEntry.contains(getConnectorIdentifier());
                if (isDeployed) {
                    log.warn("{} - Mapping {} contains unsupported wildcards",
                            tenant, mapping.getId());
                    result = false;
                }
            }
        }
        return result;
    }

    private void handleSubscriptionForInbound(Mapping mapping, Boolean create, Boolean activationChanged) {
        initializeSubscriptionIfNeeded(mapping);

        if (mapping.active) {
            handleActivationMapping(mapping, create);
        } else if (activationChanged) {
            handleDeactivationMapping(mapping);
        }
    }

    private void handleActivationMapping(Mapping mapping, Boolean create) {
        mappingsDeployedInbound.put(mapping.identifier, mapping);
        MutableInt subscriptionCount = getCountSubscriptionsPerTopicInboundView().get(mapping.mappingTopic);

        if (create || subscriptionCount.intValue() == 0) {
            try {

                // log.info("{} - Subscribing to topic: [{}], qos: {}",
                // tenant, mapping.mappingTopic, mapping.qos);
                subscribe(mapping.mappingTopic, mapping.qos);
                log.info("{} - Subscribed to topic:[{}] for connector: {}, QoS: {}", tenant,
                        mapping.mappingTopic,
                        connectorName, mapping.qos);// use qos from mapping
            } catch (ConnectorException exp) {
                log.error("{} - Error subscribing to topic:[{}]",
                        tenant, mapping.mappingTopic, exp);
            }
        }
        subscriptionCount.increment();
    }

    private void handleDeactivationMapping(Mapping mapping) {
        MutableInt subscriptionCount = getCountSubscriptionsPerTopicInboundView().get(mapping.mappingTopic);
        subscriptionCount.decrement();

        if (subscriptionCount.intValue() <= 0) {
            try {
                log.info("{} - Unsubscribing from topic: [{}]",
                        tenant, mapping.mappingTopic);
                unsubscribe(mapping.mappingTopic);
                getCountSubscriptionsPerTopicInbound().remove(mapping.mappingTopic);
            } catch (Exception exp) {
                log.error("{} - Error unsubscribing from topic: [{}]",
                        tenant, mapping.mappingTopic, exp);
            }
        }
        mappingsDeployedInbound.remove(mapping.identifier);
    }

    public void deleteActiveSubscription(Mapping mapping) {
        if (mapping.direction == Direction.INBOUND) {
            if (getCountSubscriptionsPerTopicInboundView().containsKey(mapping.mappingTopic) && isConnected()) {
                handleInboundSubscriptionDeletion(mapping);
            }
            mappingsDeployedInbound.remove(mapping.identifier);
        } else {
            mappingsDeployedOutbound.remove(mapping.identifier);
        }
    }

    private void handleInboundSubscriptionDeletion(Mapping mapping) {
        MutableInt activeSubs = getCountSubscriptionsPerTopicInboundView().get(mapping.mappingTopic);
        activeSubs.decrement();

        if (activeSubs.intValue() <= 0) {
            try {
                unsubscribe(mapping.mappingTopic);
            } catch (Exception e) {
                log.error("{} - Error unsubscribing from topic: [{}]",
                        tenant, mapping.mappingTopic, e);
            }
        }
    }

    // Status Management Methods
    public void updateConnectorStatusAndSend(ConnectorStatus status, boolean clearMessage, boolean send) {
        connectorStatus.updateStatus(status, clearMessage);
        if (send) {
            sendConnectorLifecycle();
        }
    }

    protected void updateConnectorStatusToFailed(Exception e) {
        String errorMessage = buildErrorMessage(e);
        connectorStatus.setMessage(errorMessage);
        updateConnectorStatusAndSend(ConnectorStatus.FAILED, false, true);
    }

    private String buildErrorMessage(Exception e) {
        StringBuilder messageBuilder = new StringBuilder()
                .append(" --- ")
                .append(e.getClass().getName())
                .append(": ")
                .append(e.getMessage());

        Optional.ofNullable(e.getCause()).ifPresent(cause -> messageBuilder.append(" --- Caused by ")
                .append(cause.getClass().getName())
                .append(": ")
                .append(cause.getMessage()));

        return messageBuilder.toString();
    }

    public void sendConnectorLifecycle() {
        if (!shouldSendLifecycleEvent()) {
            return;
        }

        previousConnectorStatus = connectorStatus.getStatus();
        createAndSendLifecycleEvent();
    }

    private boolean shouldSendLifecycleEvent() {
        return serviceConfiguration.sendConnectorLifecycle &&
                !connectorStatus.getStatus().equals(previousConnectorStatus);
    }

    private void createAndSendLifecycleEvent() {
        Map<String, String> statusMap = createStatusMap();
        String message = "Connector status: " + connectorStatus.status;
        c8yAgent.createOperationEvent(
                message,
                LoggingEventType.STATUS_CONNECTOR_EVENT_TYPE,
                DateTime.now(),
                tenant,
                statusMap);
    }

    private Map<String, String> createStatusMap() {
        DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String date = dateFormat.format(new Date());
        String message = connectorStatus.getMessage();
        if ("".equals(connectorStatus.getMessage())) {
            message = "Connector status: " + connectorStatus.status;
        }

        return Map.ofEntries(
                entry("status", connectorStatus.getStatus().name()),
                entry("message", message),
                entry("connectorName", getConnectorName()),
                entry("connectorIdentifier", getConnectorIdentifier()),
                entry("date", date));
    }

    public void sendSubscriptionEvents(String topic, String action) {
        if (!serviceConfiguration.sendSubscriptionEvents) {
            return;
        }

        String message = action + " topic: " + topic;
        Map<String, String> eventMap = createSubscriptionEventMap(message);

        c8yAgent.createOperationEvent(
                message,
                LoggingEventType.STATUS_SUBSCRIPTION_EVENT_TYPE,
                DateTime.now(),
                tenant,
                eventMap);
    }

    private Map<String, String> createSubscriptionEventMap(String message) {
        DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String date = dateFormat.format(new Date());

        return Map.ofEntries(
                entry("message", message),
                entry("connectorName", getConnectorName()),
                entry("date", date));
    }

    public void connectionLost(String closeMessage, Throwable closeException) throws InterruptedException {
        logConnectionLost(closeMessage, closeException);
        Thread.sleep(WAIT_PERIOD_MS);
        reconnect();
    }

    private void logConnectionLost(String closeMessage, Throwable closeException) {
        if (closeException != null) {
            log.error("{} - Connection lost: [{},{}], {}",
                    tenant,
                    getConnectorName(),
                    getConnectorIdentifier(),
                    closeException.getMessage(),
                    closeException);
        } else if (closeMessage != null) {
            log.info("{} - Connection lost: [{},{}], {}",
                    tenant,
                    getConnectorName(),
                    getConnectorIdentifier(),
                    closeMessage);
        }
    }

    public Future<?> reconnect() {
        try {
            // Blocking to wait for disconnect
            submitDisconnect().get();
            // Blocking to wait for initialization
            submitInitialize().get();
            return submitConnect();
        } catch (Exception e) {
            log.error("{} - Error during reconnect: ", tenant, e);
        }
        return null;
    }

    /**
     * This method is triggered every 30 seconds. It performs the following tasks:
     * 1. synchronizes snooped payloads with the mapping in the inventory
     * 2. send an connector lifecycle update
     * 3. monitor and removes failed subscriptions. This is required for the Kafka
     * connector
     **/
    public void runHousekeeping() {
        try {
            performHousekeepingTasks();
        } catch (Exception ex) {
            log.error("{} - Error during house keeping execution: ",
                    tenant,
                    ex);
        }
    }

    private void performHousekeepingTasks() throws Exception {
        Instant now = Instant.now();
        logHousekeepingStatus(now);
        connectorSpecificHousekeeping(tenant);

        mappingService.cleanDirtyMappings(tenant);
        mappingService.sendMappingStatus(tenant);

        updateConnectorStatusIfNeeded();
        monitorSubscriptions();
    }

    protected abstract void connectorSpecificHousekeeping(String tenant);

    private void logHousekeepingStatus(Instant now) {
        if (Duration.between(start, now).getSeconds() < 1800) {
            String connectTaskStatus = getTaskStatus(connectTask);
            String initializeTaskStatus = getTaskStatus(initializeTask);

            log.debug("{} - Status: connectTask: {}, initializeTask: {}, isConnected: {}",
                    tenant,
                    connectTaskStatus,
                    initializeTaskStatus,
                    isConnected());
        }
    }

    private String getTaskStatus(Future<?> task) {
        if (task == null)
            return "stopped";
        return task.isDone() ? "stopped" : "running";
    }

    private void updateConnectorStatusIfNeeded() {
        if (ConnectorStatus.DISCONNECTED.equals(connectorStatus.status) &&
                isConfigValid(connectorConfiguration)) {
            updateConnectorStatusAndSend(ConnectorStatus.CONFIGURED, true, true);
        } else {
            sendConnectorLifecycle();
        }
    }

    public void stopHousekeepingAndClose() {
        List<Runnable> stoppedTasks = this.housekeepingExecutor.shutdownNow();
        close();
        log.info("{} - Shutdown housekeepingTasks: {}",
                tenant,
                stoppedTasks);
    }

    public boolean hasError() {
        return !connectorStatus.status.equals(ConnectorStatus.FAILED);
    }

    public void collectSubscribedMappingsAll(Map<String, DeploymentMapEntry> mappingsDeployed) {
        ConnectorConfiguration cleanedConfiguration = getConnectorConfiguration()
                .getCleanedConfig(connectorSpecification);

        collectInboundMappings(mappingsDeployed, cleanedConfiguration);
        collectOutboundMappings(mappingsDeployed, cleanedConfiguration);
    }

    private void collectInboundMappings(Map<String, DeploymentMapEntry> mappingsDeployed,
            ConnectorConfiguration cleanedConfiguration) {
        List<String> subscribedMappingsInbound = new ArrayList<>(mappingsDeployedInbound.keySet());
        updateDeploymentMap(subscribedMappingsInbound, mappingsDeployed, cleanedConfiguration);
    }

    private void collectOutboundMappings(Map<String, DeploymentMapEntry> mappingsDeployed,
            ConnectorConfiguration cleanedConfiguration) {
        List<String> subscribedMappingsOutbound = new ArrayList<>(mappingsDeployedOutbound.keySet());
        updateDeploymentMap(subscribedMappingsOutbound, mappingsDeployed, cleanedConfiguration);
    }

    private void updateDeploymentMap(List<String> mappingIds,
            Map<String, DeploymentMapEntry> mappingsDeployed,
            ConnectorConfiguration cleanedConfiguration) {
        mappingIds.forEach(mappingIdentifier -> {
            DeploymentMapEntry mappingDeployed = mappingsDeployed.computeIfAbsent(
                    mappingIdentifier,
                    k -> new DeploymentMapEntry(mappingIdentifier));
            mappingDeployed.getConnectors().add(cleanedConfiguration);
        });
    }

    public boolean activationChanged(Mapping mapping) {
        Optional<Mapping> activeMappingOptional = findActiveMappingInbound(mapping);
        if (activeMappingOptional.isPresent()) {
            Mapping activeMapping = activeMappingOptional.get();
            return !mapping.active.equals(activeMapping.active);
        }
        return false;
    }

    private Optional<Mapping> findActiveMappingInbound(Mapping mapping) {
        Map<String, Mapping> cacheMappings = mappingService.getCacheMappingInbound(tenant);
        if (cacheMappings == null) {
            return Optional.empty();
        }

        return cacheMappings.values().stream()
                .filter(m -> m.id.equals(mapping.id))
                .findFirst();
    }

    /**
     * This method is called when a mapping is created or an existing mapping is
     * updated.
     * It maintains a list of the active subscriptions for this connector.
     * When a mapping id deleted or deactivated, then it is verified how many other
     * mapping use the same mappingTopic. If there are no other mapping using
     * the same mappingTopic the mappingTopic is unsubscribed.
     * Only inactive mappings can be updated except activation/deactivation.
     **/

    public void updateSubscriptionForOutbound(Mapping mapping, Boolean create, Boolean activationChanged) {
        boolean isDeployed = isDeployedInConnector(mapping);

        if (mapping.active && isDeployed) {
            mappingsDeployedOutbound.put(mapping.identifier, mapping);
        } else {
            mappingsDeployedOutbound.remove(mapping.identifier);
        }
    }

    private boolean isDeployedInConnector(Mapping mapping) {
        List<String> deploymentMapEntry = mappingService.getDeploymentMapEntry(tenant, mapping.identifier);
        return deploymentMapEntry != null && deploymentMapEntry.contains(getConnectorIdentifier());
    }

    public void initializeSubscriptionsOutbound(List<Mapping> updatedMappings) {
        mappingsDeployedOutbound = new ConcurrentHashMap<>();

        updatedMappings.stream()
                .filter(mapping -> mapping.getActive() && isDeployedInConnector(mapping))
                .forEach(mapping -> {
                    mappingsDeployedOutbound.put(mapping.identifier, mapping);
//                    if (mapping.mappingType.equals(MappingType.CODE_BASED)) {
//                        configurationRegistry.updateGraalsSourceMapping(tenant, mapping.id, mapping.getCode());
//                    }
                });
    }

    // Event Logging Methods
    protected void logEventProcessing(String eventType, String details) {
        log.debug("{} - Processing {} event: {}", tenant, eventType, details);
    }

    protected void logEventSuccess(String eventType, String details) {
        log.info("{} - Successfully processed {} event: {}", tenant, eventType, details);
    }

    protected void logEventError(String eventType, String details, Exception e) {
        log.error("{} - Error processing {} event: {}", tenant, eventType, details, e);
    }

    // Event Validation Methods
    protected boolean validateEventData(Object data, String eventType) {
        if (data == null) {
            log.warn("{} - Received null data for {} event", tenant, eventType);
            return false;
        }
        return true;
    }

    // Utility Methods
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    /**
     * Retrieves the active subscriptions
     */
    public Map<String, MutableInt> getCountSubscriptionsPerTopicInbound() {
        return countSubscriptionsPerTopicInbound;
    }

    /**
     * Gets a read-only view of active subscriptions (for external use)
     */
    public Map<String, MutableInt> getCountSubscriptionsPerTopicInboundView() {
        return Collections.unmodifiableMap(countSubscriptionsPerTopicInbound);
    }

    /**
     * Safely adds a subscription
     */
    protected void addTopicToSubscriptionInbound(String topic, MutableInt count) {
        countSubscriptionsPerTopicInbound.put(topic, count);
    }

    /**
     * Safely removes a subscription
     */
    protected void removeTopicFromSubscriptionInbound(String topic) {
        countSubscriptionsPerTopicInbound.remove(topic);
    }

    private void initializeSubscriptionIfNeeded(Mapping mapping) {
        if (!countSubscriptionsPerTopicInbound.containsKey(mapping.mappingTopic)) {
            addTopicToSubscriptionInbound(mapping.mappingTopic, new MutableInt(0));
        }
    }

    /**
     * Creates a formatted timestamp for logging and events
     */
    protected String getCurrentTimestamp() {
        return DATE_FORMATTER.format(Instant.now());
    }

    /**
     * Safely builds a path combining parent path and level
     */
    protected static String buildPath(String parentPath, String level) {
        if (parentPath == null || parentPath.isEmpty()) {
            return level;
        }
        return parentPath.endsWith("/") ? parentPath + level : parentPath + "/" + level;
    }

    /**
     * Creates a path for monitoring purposes
     */
    protected String createPathMonitoring(List<String> levels, int currentLevel) {
        if (levels == null || levels.isEmpty()) {
            return "";
        }

        return IntStream.range(0, levels.size())
                .mapToObj(i -> formatPathLevel(levels.get(i), i == currentLevel))
                .collect(Collectors.joining("/"));
    }

    private String formatPathLevel(String level, boolean isCurrent) {
        return isCurrent ? String.format("__%s__", level) : level;
    }

    /**
     * Safely executes an operation with timeout
     */
    protected <T> Optional<T> executeWithTimeout(Callable<T> operation, long timeout, TimeUnit unit) {
        try {
            Future<T> future = virtualThreadPool.submit(operation);
            return Optional.ofNullable(future.get(timeout, unit));
        } catch (Exception e) {
            log.warn("Operation timed out or failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Safely closes resources
     */
    protected void safeClose(AutoCloseable... resources) {
        for (AutoCloseable resource : resources) {
            try {
                if (resource != null) {
                    resource.close();
                }
            } catch (Exception e) {
                log.warn("Error closing resource: {}", e.getMessage());
            }
        }
    }

    /**
     * Validates string configuration values
     */
    protected boolean isValidStringConfig(String... values) {
        return Arrays.stream(values)
                .allMatch(value -> value != null && !value.trim().isEmpty());
    }

    /**
     * Creates a deep copy of a map
     */
    @SuppressWarnings("unchecked")
    protected <K, V> Map<K, V> deepCopyMap(Map<K, V> original) {
        try {
            String json = objectMapper.writeValueAsString(original);
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.error("Error creating deep copy of map: {}", e.getMessage());
            return new HashMap<>(original);
        }
    }

    /**
     * Retries an operation with exponential backoff
     */
    protected <T> Optional<T> retryOperation(Supplier<T> operation, int maxAttempts, long initialDelay) {
        int attempt = 0;
        long delay = initialDelay;

        while (attempt < maxAttempts) {
            try {
                T result = operation.get();
                if (result != null) {
                    return Optional.of(result);
                }
            } catch (Exception e) {
                log.warn("Attempt {} failed: {}", attempt + 1, e.getMessage());
            }

            attempt++;
            if (attempt < maxAttempts) {
                try {
                    Thread.sleep(delay);
                    delay *= 2; // Exponential backoff
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Validates and sanitizes topic names
     */
    protected String sanitizeTopic(String topic) {
        if (topic == null || topic.trim().isEmpty()) {
            throw new IllegalArgumentException("Topic cannot be null or empty");
        }
        return topic.trim().replaceAll("\s+", "_");
    }

    /**
     * Checks if a string contains valid JSON
     */
    protected boolean isValidJson(String jsonString) {
        try {
            objectMapper.readTree(jsonString);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Creates a thread-safe cache with expiration
     */
    protected <K, V> Map<K, V> createExpiringCache(long duration, TimeUnit unit) {
        return Collections.synchronizedMap(
                new LinkedHashMap<K, V>() {
                    private static final long serialVersionUID = 1L;
                    private final long expiration = unit.toMillis(duration);

                    @Override
                    protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                        return System.currentTimeMillis() - getCreationTime(eldest) > expiration;
                    }

                    private long getCreationTime(Map.Entry<K, V> entry) {
                        // Assuming the value has a timestamp field or similar
                        // Adjust according to your needs
                        return System.currentTimeMillis();
                    }
                });
    }

    /**
     * Formats byte size to human-readable format
     */
    protected String formatByteSize(long bytes) {
        if (bytes < 1024)
            return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    /**
     * Validates IP address format
     */
    protected boolean isValidIpAddress(String ip) {
        if (ip == null || ip.isEmpty()) {
            return false;
        }
        try {
            String[] parts = ip.split(".");
            if (parts.length != 4) {
                return false;
            }
            return Arrays.stream(parts)
                    .map(Integer::parseInt)
                    .allMatch(part -> part >= 0 && part <= 255);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Safely parses string to enum
     */
    protected <T extends Enum<T>> Optional<T> safeValueOf(Class<T> enumClass, String value) {
        try {
            return Optional.of(Enum.valueOf(enumClass, value.toUpperCase()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public abstract List<Direction> supportedDirections();

    public boolean isMappingInboundDeployed(String identifier) {
        return mappingsDeployedInbound.containsKey(identifier);
    }

    public boolean isMappingOutboundDeployed(String identifier) {
        return mappingsDeployedOutbound.containsKey(identifier);
    }

}
