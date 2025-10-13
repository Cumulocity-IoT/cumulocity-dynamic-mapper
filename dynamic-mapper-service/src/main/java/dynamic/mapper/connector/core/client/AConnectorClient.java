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

import dynamic.mapper.configuration.ConnectorConfiguration;
import dynamic.mapper.configuration.ConnectorId;
import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.ConnectorSpecification;
import dynamic.mapper.connector.core.callback.GenericMessageCallback;
import dynamic.mapper.connector.core.registry.ConnectorRegistry;
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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;

import org.apache.commons.lang3.mutable.MutableInt;
import org.joda.time.DateTime;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Base class for connector clients.
 * Simplified with extracted managers for subscriptions and connection state.
 */
@Slf4j
public abstract class AConnectorClient {

    @Data
    @AllArgsConstructor
    public static class Certificate {
        private String fingerprint;
        private String certInPemFormat;
    }

    // Constants
    protected static final int HOUSEKEEPING_INTERVAL_SECONDS = 30;
    protected static final int WAIT_PERIOD_MS = 10000;
    protected static final int CONNECTION_TIMEOUT_SECONDS = 30;

    public static final String MQTT_PROTOCOL_MQTT = "mqtt://";
    public static final String MQTT_PROTOCOL_MQTTS = "mqtts://";
    public static final String MQTT_PROTOCOL_WS = "ws://";
    public static final String MQTT_PROTOCOL_WSS = "wss://";
    public static final String MQTT_VERSION_3_1_1 = "3.1.1";
    public static final String MQTT_VERSION_5_0 = "5.0";

    // Identity
    @Getter
    protected String connectorIdentifier;
    @Getter
    protected String additionalSubscriptionIdTest;
    @Getter
    protected String connectorName;
    @Getter
    protected Boolean supportsMessageContext;
    @Getter
    protected ConnectorId connectorId;
    @Getter
    @Setter
    protected String tenant;
    @Getter
    @Setter
    protected ConnectorType connectorType;
    @Getter
    @Setter
    protected boolean singleton;

    // Configuration
    @Getter
    @Setter
    protected ConnectorConfiguration connectorConfiguration;
    @Getter
    @Setter
    protected ConnectorSpecification connectorSpecification;
    @Getter
    @Setter
    protected ServiceConfiguration serviceConfiguration;
    @Getter
    @Setter
    protected Certificate cert;

    // Dependencies
    @Getter
    protected ConfigurationRegistry configurationRegistry;
    @Getter
    protected ConnectorRegistry connectorRegistry;
    @Getter
    protected ExecutorService virtualThreadPool;
    @Getter
    protected MappingService mappingService;
    @Getter
    protected ServiceConfigurationService serviceConfigurationService;
    @Getter
    protected ConnectorConfigurationService connectorConfigurationService;
    @Getter
    protected C8YAgent c8yAgent;
    @Getter
    @Setter
    protected GenericMessageCallback dispatcher;

    // Managers
    protected MappingSubscriptionManager mappingSubscriptionManager;
    @Getter
    protected ConnectionStateManager connectionStateManager;

    // Lifecycle tasks
    private CompletableFuture<Void> initializeTask;
    private CompletableFuture<Void> connectTask;
    private CompletableFuture<Void> disconnectTask;
    private ScheduledExecutorService housekeepingExecutor;

    // Abstract methods to be implemented by subclasses
    public abstract boolean initialize();

    public abstract void connect();

    public abstract void disconnect();

    public abstract void close();

    public abstract boolean isConfigValid(ConnectorConfiguration configuration);

    public abstract void publishMEAO(ProcessingContext<?> context);

    public abstract Boolean supportsWildcardInTopic(Direction direction);

    public abstract List<Direction> supportedDirections();

    /**
     * This method if specifically for Kafka, since it does not have the concept of
     * a client. Kafka rather supports consumer on topic level. They can fail to
     * connect
     **/
    public abstract void monitorSubscriptions();

    // Helper
    @Getter
    protected ObjectMapper objectMapper;

    /**
     * Initialize managers
     */
    protected void initializeManagers() {
        this.mappingSubscriptionManager = new MappingSubscriptionManager(
                tenant,
                connectorName,
                new MappingSubscriptionManager.SubscriptionCallback() {
                    @Override
                    public void subscribe(String topic, Qos qos) throws ConnectorException {
                        AConnectorClient.this.subscribe(topic, qos);
                    }

                    @Override
                    public void unsubscribe(String topic) throws ConnectorException {
                        AConnectorClient.this.unsubscribe(topic);
                    }
                });

        this.connectionStateManager = new ConnectionStateManager(
                tenant,
                connectorName,
                connectorIdentifier,
                this::sendConnectorLifecycle, connectorRegistry);

        this.housekeepingExecutor = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "housekeeping-" + connectorIdentifier);
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Submit initialization task
     */
    public CompletableFuture<Void> submitInitialize() {
        if (initializeTask == null || initializeTask.isDone()) {
            log.debug("{} - Initializing connector: {}", tenant, connectorName);
            initializeTask = CompletableFuture
                    .runAsync(() -> {
                        try {
                            boolean success = initialize();
                            if (!success) {
                                throw new ConnectorException("Initialization failed");
                            }
                            // Add this: Set status to CONFIGURED after successful initialization
                            if (isConfigValid(connectorConfiguration)) {
                                connectionStateManager.updateStatus(ConnectorStatus.CONFIGURED, true, true);
                            }
                        } catch (Exception e) {
                            log.error("{} - Initialization failed: {}", tenant, e.getMessage(), e);
                            connectionStateManager.updateStatusWithError(e);
                            throw new CompletionException(e);
                        }
                    }, virtualThreadPool);
        }
        return initializeTask;
    }

    /**
     * Submit connection task
     */
    public CompletableFuture<Void> submitConnect() {
        loadConfiguration();

        if (connectTask == null || connectTask.isDone()) {
            log.debug("{} - Connecting connector: {}", tenant, connectorName);
            connectTask = CompletableFuture
                    .runAsync(() -> {
                        try {
                            connectionStateManager.updateStatus(ConnectorStatus.CONNECTING, true, true);
                            connect();
                        } catch (Exception e) {
                            log.error("{} - Connection failed: {}", tenant, e.getMessage(), e);
                            connectionStateManager.updateStatusWithError(e);
                            throw new CompletionException(e);
                        }
                    }, virtualThreadPool);
        }
        return connectTask;
    }

    /**
     * Submit disconnection task
     */
    public CompletableFuture<Void> submitDisconnect() {
        // Cancel ongoing connect task
        if (connectTask != null && !connectTask.isDone()) {
            log.debug("{} - Cancelling ongoing connection", tenant);
            connectTask.cancel(true);
        }

        if (disconnectTask == null || disconnectTask.isDone()) {
            log.debug("{} - Disconnecting connector: {}", tenant, connectorName);
            disconnectTask = CompletableFuture
                    .runAsync(() -> {
                        try {
                            connectionStateManager.updateStatus(ConnectorStatus.DISCONNECTING, true, true);
                            disconnect();
                            connectionStateManager.setConnected(false);
                        } catch (Exception e) {
                            log.error("{} - Disconnection failed: {}", tenant, e.getMessage(), e);
                            throw new CompletionException(e);
                        }
                    }, virtualThreadPool);
        }
        return disconnectTask;
    }

    /**
     * Start housekeeping tasks
     */
    public void submitHousekeeping() {
        log.debug("{} - Starting housekeeping for connector: {}", tenant, connectorName);
        housekeepingExecutor.scheduleAtFixedRate(
                this::runHousekeeping,
                HOUSEKEEPING_INTERVAL_SECONDS,
                HOUSEKEEPING_INTERVAL_SECONDS,
                TimeUnit.SECONDS);
    }

    /**
     * Run housekeeping tasks
     */
    private void runHousekeeping() {
        try {
            performHousekeeping();
        } catch (Exception e) {
            log.error("{} - Error during housekeeping: {}", tenant, e.getMessage(), e);
        }
    }

    public void initializeSubscriptionsAfterConnect() {

        // Rebuild caches
        mappingService.rebuildMappingCaches(tenant, connectorId);
        List<Mapping> outboundMappings = new ArrayList<>(
                mappingService.getCacheOutboundMappings(tenant).values());
        List<Mapping> inboundMappings = new ArrayList<>(
                mappingService.getCacheInboundMappings(tenant).values());

        // Initialize subscriptions
        initializeSubscriptionsInbound(inboundMappings, true);
        initializeSubscriptionsOutbound(outboundMappings);

        log.info("{} - Initialized {} inbound and {} outbound mappings",
                tenant, inboundMappings.size(), outboundMappings.size());
    }

    /**
     * Perform housekeeping tasks - can be overridden
     */
    protected void performHousekeeping() {
        ConnectorStatus currentStatus = connectionStateManager.getCurrentStatus();

        // Update connector status if needed - check for UNKNOWN or DISCONNECTED
        if ((ConnectorStatus.UNKNOWN.equals(currentStatus) ||
                ConnectorStatus.DISCONNECTED.equals(currentStatus)) &&
                isConfigValid(connectorConfiguration)) {
            connectionStateManager.updateStatus(ConnectorStatus.CONFIGURED, true, true);
        }

        // Delegate to subclass-specific housekeeping
        connectorSpecificHousekeeping(tenant);
    }

    /**
     * Connector-specific housekeeping - to be implemented by subclasses
     */
    protected abstract void connectorSpecificHousekeeping(String tenant);

    /**
     * Initialize subscriptions for inbound mappings
     */
    public void initializeSubscriptionsInbound(List<Mapping> mappings, boolean reset) {
        List<Mapping> mappingsEffective = mappings.stream()
                .filter(this::isDeployedInConnector)
                .toList();
        mappingSubscriptionManager.updateSubscriptionsInbound(
                mappingsEffective,
                reset,
                isConnected(),
                this::isMappingValidForDeployment);
    }

    /**
     * Initialize subscriptions for outbound mappings
     */
    public void initializeSubscriptionsOutbound(List<Mapping> mappings) {
        // Clear existing outbound mappings
        // (This happens automatically in updateOutboundMappings, but you could also
        // clear manually)

        // Add active, valid mappings
        mappings.stream()
                .filter(Mapping::getActive)
                .filter(this::isMappingValidForDeployment)
                .filter(this::isDeployedInConnector)
                .forEach(mapping -> mappingSubscriptionManager.addOutboundMapping(mapping.getIdentifier(), mapping));

        log.info("{} - Initialized {} outbound mappings for connector: {}",
                tenant,
                mappingSubscriptionManager.getOutboundMappingCount(),
                connectorName);
    }

    /**
     * Update subscription for inbound mapping
     * Called when a mapping is created, updated, or its activation state changes
     * returns true if successful, false if connector is not connected or mapping is
     * invalid
     */
    public boolean updateSubscriptionForInbound(Mapping mapping, Boolean create, Boolean activationChanged) {
        boolean result = true;

        if (!isConnected()) {
            log.debug("{} - Not connected, skipping subscription update for mapping: {}",
                    tenant, mapping.getIdentifier());
            return true;
        }

        // Always allow deactivation
        boolean isDeactivation = activationChanged && !mapping.getActive();

        if (!isMappingValidForDeployment(mapping) && !isDeactivation) {
            boolean isDeployed = isDeployedInConnector(mapping);

            if (isDeployed) {
                log.warn("{} - Mapping {} contains unsupported wildcards",
                        tenant, mapping.getId());
                result = false;
            }
            return result;
        }

        try {
            handleSubscriptionUpdateInbound(mapping, create, activationChanged);
        } catch (Exception e) {
            log.error("{} - Error updating subscription for mapping {}: {}",
                    tenant, mapping.getIdentifier(), e.getMessage(), e);
            result = false;
        }

        return result;
    }

    private void handleSubscriptionUpdateInbound(Mapping mapping, Boolean create, Boolean activationChanged)
            throws ConnectorException {
        boolean isDeployed = isDeployedInConnector(mapping);
        if (mapping.getActive() && isDeployed) {
            mappingSubscriptionManager.addSubscription(mapping, mapping.getQos());
        } else if (activationChanged) {
            mappingSubscriptionManager.removeSubscription(mapping);
        }
    }

    /**
     * Update subscription for outbound mapping
     * Called when a mapping is created, updated, or its activation state changes
     */
    public void updateSubscriptionForOutbound(Mapping mapping, Boolean create, Boolean activationChanged) {
        boolean isDeployed = isDeployedInConnector(mapping);

        if (mapping.getActive() && isDeployed) {
            mappingSubscriptionManager.addOutboundMapping(mapping.getIdentifier(), mapping);
            log.debug("{} - Added outbound mapping: {}", tenant, mapping.getIdentifier());
        } else {
            mappingSubscriptionManager.removeOutboundMapping(mapping.getIdentifier());
            log.debug("{} - Removed outbound mapping: {}", tenant, mapping.getIdentifier());
        }
    }

    /**
     * Delete active subscription for a mapping
     * Called when a mapping is deleted
     */
    public void deleteActiveSubscription(Mapping mapping) {
        if (mapping.getDirection() == Direction.INBOUND) {
            deleteInboundSubscription(mapping);
        } else {
            deleteOutboundSubscription(mapping);
        }
    }

    private void deleteInboundSubscription(Mapping mapping) {
        String topic = mapping.getMappingTopic();

        if (mappingSubscriptionManager.getSubscriptionCounts().containsKey(topic) && isConnected()) {
            MutableInt count = mappingSubscriptionManager.getSubscriptionCounts().get(topic);
            count.decrement();

            if (count.intValue() <= 0) {
                try {
                    unsubscribe(topic);
                    mappingSubscriptionManager.getSubscriptionCounts().remove(topic);
                    log.info("{} - Unsubscribed from topic: [{}] after mapping deletion", tenant, topic);
                } catch (Exception e) {
                    log.error("{} - Error unsubscribing from topic: [{}]", tenant, topic, e);
                }
            }
        }

        mappingSubscriptionManager.removeOutboundMapping(mapping.getIdentifier());
        log.info("{} - Deleted inbound subscription for mapping: {}", tenant, mapping.getIdentifier());
    }

    private void deleteOutboundSubscription(Mapping mapping) {
        mappingSubscriptionManager.removeOutboundMapping(mapping.getIdentifier());
        log.info("{} - Deleted outbound subscription for mapping: {}", tenant, mapping.getIdentifier());
    }

    /**
     * Get subscription counts per topic for inbound mappings
     */
    public Map<String, MutableInt> getCountSubscriptionsPerTopicInbound() {
        return mappingSubscriptionManager.getSubscriptionCounts();
    }

    /**
     * Get read-only view of subscription counts (for external use)
     */
    public Map<String, MutableInt> getCountSubscriptionsPerTopicInboundView() {
        return mappingSubscriptionManager.getSubscriptionCounts();
    }

    /**
     * Check if a mapping's activation state has changed
     */
    public boolean activationChanged(Mapping mapping) {
        Optional<Mapping> activeMappingOptional = findActiveMappingInbound(mapping);
        if (activeMappingOptional.isPresent()) {
            Mapping activeMapping = activeMappingOptional.get();
            return !mapping.getActive().equals(activeMapping.getActive());
        }
        return false;
    }

    private Optional<Mapping> findActiveMappingInbound(Mapping mapping) {
        Map<String, Mapping> cacheMappings = mappingService.getCacheMappingInbound(tenant);
        if (cacheMappings == null) {
            return Optional.empty();
        }

        return cacheMappings.values().stream()
                .filter(m -> m.getId().equals(mapping.getId()))
                .findFirst();
    }

    /**
     * Check if mapping is deployed in this connector
     */
    private boolean isDeployedInConnector(Mapping mapping) {
        List<String> deploymentMapEntry = mappingService.getDeploymentMapEntry(tenant, mapping.getIdentifier());
        return deploymentMapEntry != null && deploymentMapEntry.contains(getConnectorIdentifier());
    }

    /**
     * Check if mapping is valid for deployment
     */
    private boolean isMappingValidForDeployment(Mapping mapping) {
        // Check for unsupported wildcards only for inbound, ignore for outbound
        boolean containsWildcards = mapping.getDirection().equals(Direction.INBOUND)
                ? mapping.getMappingTopic().matches(".*[#+].*")
                : false;
        boolean validDeployment = supportsWildcardInTopic(mapping.getDirection()) || !containsWildcards;

        if (!validDeployment) {
            log.warn("{} - Mapping {} contains unsupported wildcards", tenant, mapping.getId());
            return false;
        }

        // Check if mapping is deployed in this connector
        // Implement deployment check logic here

        return validDeployment;
    }

    /**
     * Check if inbound mapping is deployed
     */
    public boolean isMappingInboundDeployed(String identifier) {
        return mappingSubscriptionManager.getEffectiveMappingsInbound().containsKey(identifier);
    }

    /**
     * Check if outbound mapping is deployed
     */
    public boolean isMappingOutboundDeployed(String identifier) {
        return mappingSubscriptionManager.getEffectiveMappingsOutbound().containsKey(identifier);
    }

    /**
     * Collect all subscribed mappings for deployment tracking
     */
    public void collectSubscribedMappingsAll(Map<String, DeploymentMapEntry> mappingsDeployed) {
        ConnectorConfiguration cleanedConfiguration = connectorConfiguration
                .getCleanedConfig(connectorSpecification);

        // Collect inbound mappings
        List<String> inboundMappingIds = new ArrayList<>(
                mappingSubscriptionManager.getEffectiveMappingsInbound().keySet());
        updateDeploymentMap(inboundMappingIds, mappingsDeployed, cleanedConfiguration);

        // Collect outbound mappings
        List<String> outboundMappingIds = new ArrayList<>(
                mappingSubscriptionManager.getEffectiveMappingsOutbound().keySet());
        updateDeploymentMap(outboundMappingIds, mappingsDeployed, cleanedConfiguration);
    }

    private void updateDeploymentMap(List<String> mappingIds,
            Map<String, DeploymentMapEntry> mappingsDeployed,
            ConnectorConfiguration cleanedConfiguration) {
        mappingIds.forEach(mappingIdentifier -> {
            DeploymentMapEntry mappingDeployed = mappingsDeployed.computeIfAbsent(
                    mappingIdentifier,
                    k -> new DeploymentMapEntry(mappingIdentifier));

            // Check if connector with same identifier already exists
            boolean exists = mappingDeployed.getConnectors().stream()
                    .anyMatch(c -> c.getIdentifier().equals(cleanedConfiguration.getIdentifier()));

            if (!exists) {
                mappingDeployed.getConnectors().add(cleanedConfiguration);
            }
        });
    }

    /**
     * Determine maximum QoS for inbound mappings on a specific topic
     */
    public Qos determineMaxQosInbound(String topic, List<Mapping> mappings) {
        int qosOrdinal = mappings.stream()
                .filter(m -> m.getMappingTopic().equals(topic) && m.getActive())
                .map(m -> m.getQos().ordinal())
                .max(Integer::compareTo)
                .orElse(0);
        return Qos.values()[qosOrdinal];
    }

    /**
     * Determine maximum QoS for all inbound mappings
     */
    public Qos determineMaxQosInbound(List<Mapping> mappings) {
        int qosOrdinal = mappings.stream()
                .filter(Mapping::getActive)
                .map(m -> m.getQos().ordinal())
                .max(Integer::compareTo)
                .orElse(0);
        return Qos.values()[qosOrdinal];
    }

    /**
     * Determine maximum QoS for all outbound mappings
     */
    public Qos determineMaxQosOutbound(List<Mapping> mappings) {
        int qosOrdinal = mappings.stream()
                .filter(Mapping::getActive)
                .map(m -> m.getQos().ordinal())
                .max(Integer::compareTo)
                .orElse(0);
        return Qos.values()[qosOrdinal];
    }

    /**
     * Abstract subscribe method to be implemented by subclasses
     */
    protected abstract void subscribe(String topic, Qos qos) throws ConnectorException;

    /**
     * Abstract unsubscribe method to be implemented by subclasses
     */
    protected abstract void unsubscribe(String topic) throws ConnectorException;

    /**
     * Check if connector is connected
     */
    public boolean isConnected() {
        return connectionStateManager.isConnected();
    }

    /**
     * Load configuration
     */
    protected void loadConfiguration() {
        connectorConfiguration = connectorConfigurationService
                .getConnectorConfiguration(getConnectorIdentifier(), tenant);
        connectorConfiguration.copyPredefinedValues(getConnectorSpecification());

        serviceConfiguration = serviceConfigurationService.getServiceConfiguration(tenant);
        configurationRegistry.addServiceConfiguration(tenant, serviceConfiguration);
    }

    /**
     * Should the connector connect
     */
    public boolean shouldConnect() {
        return isConfigValid(connectorConfiguration) && connectorConfiguration.isEnabled();
    }

    /**
     * Send connector lifecycle event
     */
    public void sendConnectorLifecycle(ConnectorStatusEvent status) {
        if (serviceConfiguration.isSendConnectorLifecycle()) {
            Map<String, String> statusMap = createStatusMap(status);
            String message = "Connector status: " + status;
            c8yAgent.createOperationEvent(
                    message,
                    LoggingEventType.STATUS_CONNECTOR_EVENT_TYPE,
                    DateTime.now(),
                    tenant,
                    statusMap);
        }
    }

    private Map<String, String> createStatusMap(ConnectorStatusEvent status) {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String date = dateFormat.format(new Date());
        String message = status.getMessage();
        if ("".equals(status.getMessage())) {
            message = "Connector status: " + status.status;
        }

        return Map.ofEntries(
                entry("status", status.getStatus().name()),
                entry("message", message),
                entry("connectorName", getConnectorName()),
                entry("connectorIdentifier", getConnectorIdentifier()),
                entry("date", date));
    }

    /**
     * Handle connection lost
     */
    protected void connectionLost(String message, Throwable throwable) throws InterruptedException {
        log.error("{} - Connection lost: {}", tenant, message, throwable);
        connectionStateManager.setConnected(false);
        Thread.sleep(WAIT_PERIOD_MS);
        reconnect();
    }

    /**
     * Reconnect
     */
    public Future<?> reconnect() {
        try {
            submitDisconnect().get();
            submitInitialize().get();
            return submitConnect();
        } catch (Exception e) {
            log.error("{} - Error during reconnect: {}", tenant, e.getMessage(), e);
        }
        return null;
    }

    /**
     * Stop housekeeping and close
     */
    public void stopHousekeepingAndClose() {
        if (housekeepingExecutor != null && !housekeepingExecutor.isShutdown()) {
            List<Runnable> stoppedTasks = housekeepingExecutor.shutdownNow();
            log.info("{} - Stopped {} housekeeping tasks", tenant, stoppedTasks.size());
        }
        close();
    }

    /**
     * Cleanup
     */
    public void cleanup() {
        stopHousekeepingAndClose();
        if (mappingSubscriptionManager != null) {
            mappingSubscriptionManager.clear();
        }
    }

    public void sendSubscriptionEvents(String topic, String action) {
        if (!serviceConfiguration.isSendSubscriptionEvents()) {
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
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String date = dateFormat.format(new Date());

        return Map.ofEntries(
                entry("message", message),
                entry("connectorName", getConnectorName()),
                entry("date", date));
    }

}