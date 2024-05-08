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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import dynamic.mapping.connector.core.ConnectorSpecification;
import dynamic.mapping.model.Mapping;
import dynamic.mapping.model.MappingServiceRepresentation;
import dynamic.mapping.model.QOS;
import dynamic.mapping.processor.inbound.AsynchronousDispatcherInbound;

import org.apache.commons.lang3.mutable.MutableBoolean;
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
import dynamic.mapping.core.C8YAgent;
import dynamic.mapping.core.ConfigurationRegistry;
import dynamic.mapping.core.ConnectorStatusEvent;
import dynamic.mapping.core.MappingComponent;
import dynamic.mapping.core.ConnectorStatus;
import dynamic.mapping.processor.model.ProcessingContext;

@Slf4j
public abstract class AConnectorClient {

    protected static final int WAIT_PERIOD_MS = 10000;

    protected String connectorIdent;

    protected String connectorName;

    protected String additionalSubscriptionIdTest;

    protected MutableBoolean connectionState = new MutableBoolean(false);

    @Getter
    @Setter
    public ConnectorSpecification specification;

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

    // keeps track how many active mappings use this topic as subscriptionTopic:
    // structure < subscriptionTopic, numberMappings >
    public Map<String, MutableInt> activeSubscriptions = new HashMap<>();
    // keeps track if a specific mapping is deployed in this connector:
    // a) is it active,
    // b) does it comply with the capabilities of the connector, i.e. supports
    // wildcards
    // structure < ident, mapping >
    @Getter
    private Map<String, Mapping> mappingsDeployed = new ConcurrentHashMap<>();

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

    @Getter
    @Setter
    public Boolean supportsMessageContext;

    public void submitInitialize() {
        // test if init task is still running, then we don't need to start another task
        log.debug("Tenant {} - Called initialize(): {}", tenant, initializeTask == null || initializeTask.isDone());
        if ((initializeTask == null || initializeTask.isDone())) {
            initializeTask = cachedThreadPool.submit(() -> initialize());
        }
    }

    public abstract boolean initialize();

    public abstract Boolean supportsWildcardsInTopic();

    public void loadConfiguration() {
        connectorConfiguration = connectorConfigurationComponent.getConnectorConfiguration(this.getConnectorIdent(),
                tenant);
        this.connectorConfiguration.copyPredefinedValues(getSpecification());
        // get the latest serviceConfiguration from the Cumulocity backend in case
        // someone changed it in the meantime
        // update the in the registry
        serviceConfiguration = serviceConfigurationComponent.getServiceConfiguration(tenant);
        configurationRegistry.getServiceConfigurations().put(tenant, serviceConfiguration);

        // updateConnectorStatusAndSend(ConnectorStatus.CONFIGURED, true, true);
    }

    public void submitConnect() {
        loadConfiguration();
        // test if connect task is still running, then we don't need to start another
        // task
        log.debug("Tenant {} - Called connect(): connectTask.isDone() {}", tenant,
                connectTask == null || connectTask.isDone());
        if (connectTask == null || connectTask.isDone()) {
            connectTask = cachedThreadPool.submit(() -> connect());
        }
    }

    public void submitDisconnect() {
        loadConfiguration();
        // test if connect task is still running, then we don't need to start another
        // task
        log.debug("Tenant {} - Called submitDisconnect(): connectTask.isDone() {}", tenant,
                connectTask == null || connectTask.isDone());
        if (connectTask == null || connectTask.isDone()) {
            connectTask = cachedThreadPool.submit(() -> disconnect());
        }
    }

    public void submitHousekeeping() {
        log.debug("Tenant {} - Called submitHousekeeping()", tenant);
        housekeepingExecutor.scheduleAtFixedRate(() -> runHousekeeping(), 0, 30,
                TimeUnit.SECONDS);
    }

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
     * Should return true when connector is enabled and provided properties are
     * valid
     **/
    public boolean shouldConnect() {
        return isConfigValid(connectorConfiguration) && connectorConfiguration.isEnabled();
    }

    /**
     * Returns true if the connector is currently connected
     **/
    public abstract boolean isConnected();

    /**
     * Disconnect the broker
     **/
    public abstract void disconnect();

    /**
     * Close the connection to broker and release all resources
     **/
    public abstract void close();

    /**
     * Returning the unique ID identifying the connector instance
     **/
    public abstract String getConnectorIdent();

    /**
     * Returning the name of the connector instance
     **/
    public abstract String getConnectorName();

    /**
     * Subscribe to a topic on the Broker
     **/
    public abstract void subscribe(String topic, QOS qos) throws ConnectorException;

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

    /**
     * This method is triggered every 30 seconds. It performs the following tasks:
     * 1. synchronizes snooped payloads with the mapping in the inventory
     * 2. send an connector lifecycle update
     * 3. monitor and removes failed subscriptions. This is required for the Kafka
     * connector
     **/
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
            // remove failed subscriptions
            monitorSubscriptions();
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
        message.setSupportsMessageContext(getSupportsMessageContext());
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
            mappingsDeployed.remove(mapping.ident);
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

    public boolean subscriptionTopicChanged(Mapping mapping) {
        Boolean subscriptionTopicChanged = false;
        Mapping activeMapping = null;
        Optional<Mapping> activeMappingOptional = mappingComponent.getCacheMappingInbound().get(tenant).values()
                .stream()
                .filter(m -> m.id.equals(mapping.id))
                .findFirst();

        if (activeMappingOptional.isPresent()) {
            activeMapping = activeMappingOptional.get();
            subscriptionTopicChanged = !mapping.subscriptionTopic.equals(activeMapping.subscriptionTopic);
        }
        return subscriptionTopicChanged;
    }

    public boolean activationChanged(Mapping mapping) {
        Boolean activationChanged = false;
        Mapping activeMapping = null;
        Optional<Mapping> activeMappingOptional = mappingComponent.getCacheMappingInbound().get(tenant).values()
                .stream()
                .filter(m -> m.id.equals(mapping.id))
                .findFirst();

        if (activeMappingOptional.isPresent()) {
            activeMapping = activeMappingOptional.get();
            activationChanged = mapping.active != activeMapping.active;
        }
        return activationChanged;
    }

    /**
     * This method is called when a mapping is created or an existing mapping is
     * updated.
     * It maintains a list of the active subscriptions for this connector.
     * When a mapping id deleted or deactivated, then it is verified how many other
     * mapping use the same subscriptionTopic. If there are no other mapping using
     * the same subscriptionTopic the subscriptionTopic is unsubscribed.
     * Only inactive mappings can be updated except activation/deactivation.
     **/
    public void updateActiveSubscription(Mapping mapping, Boolean create, Boolean activationChanged) {
        if (isConnected()) {
            Boolean containsWildcards = mapping.subscriptionTopic.matches(".*[#\\+].*");
            boolean validDeployment = (supportsWildcardsInTopic() || !containsWildcards);
            if (validDeployment) {
                if (!getActiveSubscriptions().containsKey(mapping.subscriptionTopic)) {
                    getActiveSubscriptions().put(mapping.subscriptionTopic, new MutableInt(0));
                }
                if (mapping.active) {
                    getMappingsDeployed().put(mapping.ident, mapping);
                } else {
                    getMappingsDeployed().remove(mapping.ident);
                }
                MutableInt updatedMappingSubs = getActiveSubscriptions()
                        .get(mapping.subscriptionTopic);

                // consider unsubscribing from previous subscription topic if it has changed
                if (create) {
                    if (mapping.active) {
                        updatedMappingSubs.add(1);
                        log.info("Tenant {} - Subscribing to topic: {}, qos: {}", tenant, mapping.subscriptionTopic,
                                mapping.qos);
                        try {
                            subscribe(mapping.subscriptionTopic, mapping.qos);
                        } catch (ConnectorException exp) {
                            log.error("Tenant {} - Exception when subscribing to topic: {}: ", tenant,
                                    mapping.subscriptionTopic, exp);
                        }
                    } else {
                        log.error("Tenant {} - Cannot update of active mapping: {}, it is not subscribed to topics ",
                                tenant,
                                mapping.name);
                    }
                } else {
                    if (mapping.active) {
                        // mapping is activated, we have to subscribe
                        if (updatedMappingSubs.intValue() == 0) {
                            log.info("Tenant {} - Subscribing to topic: {}, qos: {}", tenant,
                                    mapping.subscriptionTopic,
                                    mapping.qos.ordinal());
                            try {
                                subscribe(mapping.subscriptionTopic, mapping.qos);
                            } catch (ConnectorException exp) {
                                log.error("Tenant {} - Exception when subscribing to topic: {}: ", tenant,
                                        mapping.subscriptionTopic, exp);
                            }
                        }
                        updatedMappingSubs.add(1);
                    } else if (activationChanged) {
                        // only unsubscribe if the mapping was deactivated in this call. Otherwise the
                        // mapping was updated which does not result in any changes of the subscription
                        updatedMappingSubs.subtract(1);
                        if (updatedMappingSubs.intValue() <= 0) {
                            try {
                                log.info("Tenant {} - Unsubscribing from topic: {}, qos: {}", tenant, mapping.subscriptionTopic,
                                        mapping.qos.ordinal());
                                unsubscribe(mapping.subscriptionTopic);
                                getActiveSubscriptions().remove(mapping.subscriptionTopic);
                            } catch (Exception exp) {
                                log.error("Tenant {} - Exception when unsubscribing from topic: {}: ", tenant,
                                        mapping.subscriptionTopic, exp);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * This method is maintains the list of mappings that are active for this
     * connector.
     * If a connector does not support wildcards in this topic subscriptions, i.e.
     * Kafka, the mapping can't be activated for this connector
     **/
    public void updateActiveSubscriptions(List<Mapping> updatedMappings, boolean reset) {

        mappingsDeployed = new ConcurrentHashMap<>();
        if (reset) {
            activeSubscriptions = new HashMap<String, MutableInt>();
        }

        if (isConnected()) {
            Map<String, MutableInt> updatedSubscriptionCache = new HashMap<String, MutableInt>();
            updatedMappings.forEach(mapping -> {
                Boolean containsWildcards = mapping.subscriptionTopic.matches(".*[#\\+].*");
                boolean validDeployment = (supportsWildcardsInTopic() || !containsWildcards);
                if (validDeployment && mapping.isActive()) {
                    if (!updatedSubscriptionCache.containsKey(mapping.subscriptionTopic)) {
                        updatedSubscriptionCache.put(mapping.subscriptionTopic, new MutableInt(0));
                    }
                    MutableInt activeSubs = updatedSubscriptionCache.get(mapping.subscriptionTopic);
                    activeSubs.add(1);
                    mappingsDeployed.put(mapping.ident, mapping);
                }
            });

            // unsubscribe topics not used
            getActiveSubscriptions().keySet().forEach((subscriptionTopic) -> {
                if (!updatedSubscriptionCache.containsKey(subscriptionTopic)) {
                    log.debug("Tenant {} - Unsubscribe from topic: {}", tenant, subscriptionTopic);
                    try {
                        unsubscribe(subscriptionTopic);
                    } catch (Exception exp) {
                        log.error("Tenant {} - Exception when unsubscribing from topic: {}: ", subscriptionTopic, exp);
                        throw new RuntimeException(exp);
                    }
                }
            });

            // subscribe to new topics
            updatedSubscriptionCache.keySet().forEach((topic) -> {
                if (!getActiveSubscriptions().containsKey(topic)) {
                    int qosOrdial = updatedMappings.stream().filter(m -> m.subscriptionTopic.equals(topic))
                            .map(m -> m.qos.ordinal()).reduce(Integer::max).orElse(0);
                    QOS qos = QOS.values()[qosOrdial];
                    try {
                        subscribe(topic, qos);
                        log.info("Tenant {} - Successfully subscribed to topic: {}, qos: {}", tenant, topic, qos);
                    } catch (ConnectorException exp) {
                        log.error("Tenant {} - Exception when subscribing to topic: {}: ", tenant, topic, exp);
                        throw new RuntimeException(exp);
                    }
                }
            });
            activeSubscriptions = updatedSubscriptionCache;
            log.info("Tenant {} - Updating subscriptions to topics was successful, active Subscriptions: {}",
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
        if (closeException != null) {
            log.error("Tenant {} - Connection lost to broker {}: {}", tenant, connectorIdent,
                    closeException.getMessage());
            closeException.printStackTrace();
        }
        if (closeMessage != null)
            log.info("Tenant {} - Connection lost to broker: {}", tenant, closeMessage);
        reconnect();
    }

    public void updateConnectorStatusAndSend(ConnectorStatus status, boolean clearMessage, boolean send) {
        connectorStatus.updateStatus(status, clearMessage);
        if (send) {
            sendConnectorLifecycle();
        }
    }

    protected void updateConnectorStatusToFailed(Exception e) {
        String msg = " --- " + e.getClass().getName() + ": "
                + e.getMessage();
        if (!(e.getCause() == null)) {
            msg = msg + " --- Caused by " + e.getCause().getClass().getName() + ": " + e.getCause().getMessage();
        }
        connectorStatus.setMessage(msg);
        updateConnectorStatusAndSend(ConnectorStatus.FAILED, false, true);
    }

    @Data
    @AllArgsConstructor
    public static class Certificate {
        private String fingerprint;
        private String certInPemFormat;
    }
}