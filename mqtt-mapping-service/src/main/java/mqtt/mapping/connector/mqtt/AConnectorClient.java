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

package mqtt.mapping.connector.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import mqtt.mapping.configuration.ConnectorConfiguration;
import mqtt.mapping.configuration.ConnectorConfigurationComponent;
import mqtt.mapping.connector.core.ConnectorPropertyDefinition;
import mqtt.mapping.connector.core.callback.ConnectorMessage;
import mqtt.mapping.core.C8YAgent;
import mqtt.mapping.core.MappingComponent;
import mqtt.mapping.core.ConnectorStatus;
import mqtt.mapping.model.Mapping;
import mqtt.mapping.processor.inbound.AsynchronousDispatcher;
import mqtt.mapping.processor.model.ProcessingContext;
import org.apache.commons.lang3.mutable.MutableInt;
import org.eclipse.paho.client.mqttv3.MqttException;

import java.time.Duration;
import java.time.Instant;
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

@Slf4j
// This is instantiated manually not using Spring Boot anymore.
public abstract class AConnectorClient {

    public static final Long KEY_MONITORING_UNSPECIFIED = -1L;

    public static final String STATUS_MAPPING_EVENT_TYPE = "mqtt_status_event";

    @Getter
    @Setter
    public String tenant;

    @Getter
    public MappingComponent mappingComponent;

    @Getter
    public ConnectorConfigurationComponent connectorConfigurationComponent;

    @Getter
    public C8YAgent c8yAgent;

    @Getter
    public AsynchronousDispatcher dispatcher;

    public ObjectMapper objectMapper;

    @Getter
    public ExecutorService cachedThreadPool;

    private Future<?> connectTask;
    private ScheduledExecutorService housekeepingExecutor = Executors
            .newScheduledThreadPool(Runtime.getRuntime().availableProcessors());

    private Future<?> initializeTask;

    // @Getter
    // @Setter
    // keeps track of number of active mappings per subscriptionTopic
    public Map<String, MutableInt> activeSubscriptions = new HashMap<>();

    private Instant start = Instant.now();

    @Getter
    @Setter
    public ConnectorConfiguration configuration;

    private ScheduledFuture<?> housekeepingTask;

    public void submitInitialize() {
        // test if init task is still running, then we don't need to start another task
        log.info("Tenant {} - Called initialize(): {}", initializeTask == null || initializeTask.isDone(), tenant);
        if ((initializeTask == null || initializeTask.isDone())) {
            initializeTask = cachedThreadPool.submit(() -> initialize());
        }
    }

    public abstract boolean initialize();

    public abstract Map<String, ConnectorPropertyDefinition> getConfigProperties();

    public void reloadConfiguration() {
        configuration = connectorConfigurationComponent.getConnectorConfiguration(this.getConntectorIdent(), tenant);
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
    }

    public void submitDisconnect() {
        // test if connect task is still running, then we don't need to start another
        // task
        log.info("Tenant {} - Called submitDisconnect(): connectTask.isDone() {}", tenant,
                connectTask == null || connectTask.isDone());
        if (connectTask == null || connectTask.isDone()) {
            connectTask = cachedThreadPool.submit(() -> disconnect());
        }
    }

    public void submitHouskeeping() {
        log.info("Tenant {} - Called submitHousekeeping()", tenant);
        this.housekeepingTask = housekeepingExecutor.scheduleAtFixedRate(() -> runHouskeeping(), 0, 30, TimeUnit.SECONDS);
    }

    public abstract void connect();

    public abstract boolean canConnect();

    public abstract boolean shouldConnect();

    public abstract boolean isConnected();

    public abstract void disconnect();   
    
    public abstract void close();

    public abstract String getConntectorId();

    public abstract String getConntectorIdent();

    public abstract void subscribe(String topic, Integer qos) throws MqttException;

    public abstract void unsubscribe(String topic) throws Exception;

    public abstract boolean isConfigValid(ConnectorConfiguration configuration);

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
                log.info("Tenant {} - Status: connectTask: {}, initializeTask: {}, isConnected: {}", tenant,
                        statusConnectTask,
                        statusInitializeTask, isConnected());
            }
            mappingComponent.cleanDirtyMappings(tenant);
            mappingComponent.sendMappingStatus(tenant);
            mappingComponent.sendConnectorStatus(tenant, getConnectorStatus(), getConntectorIdent());
        } catch (Exception ex) {
            log.error("Error during house keeping execution: {}", ex);
        }
    }

    public ConnectorStatus getConnectorStatus() {
        ConnectorStatus connectorStatus;
        if (isConnected()) {
            connectorStatus = ConnectorStatus.connected();
        } else if (canConnect()) {
            connectorStatus = ConnectorStatus.enabled();
        } else if (isConfigValid(configuration)) {
            connectorStatus = ConnectorStatus.configured();
        } else {
            connectorStatus = ConnectorStatus.notReady();
        }
        return connectorStatus;
    }

    public List<ProcessingContext<?>> test(String topic, boolean send, Map<String, Object> payload)
            throws Exception {
        String payloadMessage = objectMapper.writeValueAsString(payload);
        ConnectorMessage message = new ConnectorMessage();
        message.setPayload(payloadMessage.getBytes());
        if (dispatcher == null)
            dispatcher = new AsynchronousDispatcher(this, c8yAgent, objectMapper, cachedThreadPool, mappingComponent);
        return dispatcher.processMessage(tenant, getConntectorId(), topic, message, send).get();
    }

    public void reconnect() {
        disconnect();
        // invalidate broker client
        reloadConfiguration();
        submitInitialize();
        submitConnect();
    }

    public void deleteActiveSubscription(Mapping mapping) {
        if (getActiveSubscriptions().containsKey(mapping.subscriptionTopic)) {
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

    public void updateActiveSubscriptions(List<Mapping> updatedMappings, boolean reset) {
        if (reset) {
            activeSubscriptions = new HashMap<String, MutableInt>();
        }
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
}