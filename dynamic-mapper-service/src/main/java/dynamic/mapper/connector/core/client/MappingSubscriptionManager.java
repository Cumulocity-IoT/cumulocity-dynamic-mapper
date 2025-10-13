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

import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.Qos;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.mutable.MutableInt;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages subscription state for a connector.
 * Thread-safe subscription tracking and lifecycle management.
 */
@Slf4j
public class MappingSubscriptionManager {

    private final String tenant;
    private final String connectorName;

    // Track subscription counts per topic
    private final Map<String, MutableInt> subscriptionCounts = new ConcurrentHashMap<>();

    // Track deployed mappings
    // keeps track if a specific mapping is deployed in this connector:
    // a) is it active,
    // b) does it comply with the capabilities of the connector, i.e. supports
    // wildcards
    // structure < identifier, mapping >
    private final Map<String, Mapping> deployedMappingsInbound = new ConcurrentHashMap<>();
    private final Map<String, Mapping> deployedMappingsOutbound = new ConcurrentHashMap<>();

    // Callbacks for actual subscribe/unsubscribe operations
    private final SubscriptionCallback subscriptionCallback;

    public interface SubscriptionCallback {
        void subscribe(String topic, Qos qos) throws ConnectorException;

        void unsubscribe(String topic) throws ConnectorException;
    }

    public MappingSubscriptionManager(String tenant, String connectorName, SubscriptionCallback callback) {
        this.tenant = tenant;
        this.connectorName = connectorName;
        this.subscriptionCallback = callback;
    }

    /**
     * Add or update a subscription for a topic
     */
    public void addSubscription(Mapping mapping, Qos qos) throws ConnectorException {
        String topic = mapping.getMappingTopic();
        MutableInt count = subscriptionCounts.computeIfAbsent(topic, k -> new MutableInt(0));

        boolean isNewSubscription = count.intValue() == 0;
        count.increment();

        deployedMappingsInbound.put(mapping.getIdentifier(), mapping);

        if (isNewSubscription) {
            subscriptionCallback.subscribe(topic, qos);
            log.info("{} - Subscribed to topic: [{}] for connector: {}, QoS: {}",
                    tenant, topic, connectorName, qos);
        } else {
            log.debug("{} - Incremented subscription count for topic: [{}] to {}",
                    tenant, topic, count.intValue());
        }
    }

    /**
     * Remove a subscription for a topic
     */
    public void removeSubscription(Mapping mapping) throws ConnectorException {
        String topic = mapping.getMappingTopic();
        MutableInt count = subscriptionCounts.get(topic);
        if (count == null) {
            log.warn("{} - Attempted to remove non-existent subscription for topic: [{}]", tenant, topic);
            return;
        }

        count.decrement();
        deployedMappingsInbound.remove(mapping.getIdentifier());

        if (count.intValue() <= 0) {
            subscriptionCounts.remove(topic);
            subscriptionCallback.unsubscribe(topic);
            log.info("{} - Unsubscribed from topic: [{}] for connector: {}", tenant, topic, connectorName);
        } else {
            log.debug("{} - Decremented subscription count for topic: [{}] to {}",
                    tenant, topic, count.intValue());
        }
    }

    /**
     * Update all subscriptions based on new mapping list
     */
    public void updateSubscriptions(List<Mapping> updatedMappings,
            boolean reset,
            boolean isConnected,
            MappingValidator validator) {
        if (!isConnected) {
            log.debug("{} - Not connected, skipping subscription update", tenant);
            return;
        }

        if (reset) {
            subscriptionCounts.clear();
            deployedMappingsInbound.clear();
        }

        Map<String, MutableInt> newSubscriptions = new HashMap<>();
        Map<String, Qos> topicQosMap = new HashMap<>();

        // Build new subscription state
        updatedMappings.stream()
                .filter(Mapping::getActive)
                .filter(validator::isValid)
                .forEach(mapping -> {
                    String topic = mapping.getMappingTopic();
                    newSubscriptions.computeIfAbsent(topic, k -> new MutableInt(0)).increment();
                    deployedMappingsInbound.put(mapping.getIdentifier(), mapping);

                    // Track max QoS per topic
                    Qos currentQos = topicQosMap.getOrDefault(topic, Qos.AT_MOST_ONCE);
                    if (mapping.getQos().ordinal() > currentQos.ordinal()) {
                        topicQosMap.put(topic, mapping.getQos());
                    }
                });

        // Remove old subscriptions
        unsubscribeUnusedTopics(newSubscriptions);

        // Add new subscriptions
        subscribeToNewTopics(newSubscriptions, topicQosMap);

        subscriptionCounts.putAll(newSubscriptions);

        log.info("{} - Updated subscriptions for connector: {}, active topics: {}",
                tenant, connectorName, subscriptionCounts.size());
    }

    private void unsubscribeUnusedTopics(Map<String, MutableInt> newSubscriptions) {
        subscriptionCounts.keySet().stream()
                .filter(topic -> !newSubscriptions.containsKey(topic))
                .forEach(topic -> {
                    try {
                        subscriptionCallback.unsubscribe(topic);
                        log.info("{} - Unsubscribed from unused topic: [{}]", tenant, topic);
                    } catch (Exception e) {
                        log.error("{} - Error unsubscribing from topic: [{}]", tenant, topic, e);
                    }
                });
    }

    private void subscribeToNewTopics(Map<String, MutableInt> newSubscriptions,
            Map<String, Qos> topicQosMap) {
        newSubscriptions.keySet().stream()
                .filter(topic -> !subscriptionCounts.containsKey(topic))
                .forEach(topic -> {
                    try {
                        Qos qos = topicQosMap.getOrDefault(topic, Qos.AT_MOST_ONCE);
                        subscriptionCallback.subscribe(topic, qos);
                        log.info("{} - Subscribed to new topic: [{}], QoS: {}", tenant, topic, qos);
                    } catch (ConnectorException e) {
                        log.error("{} - Error subscribing to topic: [{}]", tenant, topic, e);
                    }
                });
    }

    // ===== Outbound Mapping Management =====

    /**
     * Add or update an outbound mapping
     */
    public void addOutboundMapping(String identifier, Mapping mapping) {
        deployedMappingsOutbound.put(identifier, mapping);
        log.debug("{} - Added outbound mapping: {}", tenant, identifier);
    }

    /**
     * Remove an outbound mapping
     */
    public void removeOutboundMapping(String identifier) {
        if (deployedMappingsOutbound.remove(identifier) != null) {
            log.debug("{} - Removed outbound mapping: {}", tenant, identifier);
        }
    }

    /**
     * Update all outbound mappings based on new mapping list
     */
    public void updateOutboundMappings(List<Mapping> updatedMappings, MappingValidator validator) {
        deployedMappingsOutbound.clear();

        updatedMappings.stream()
                .filter(Mapping::getActive)
                .filter(validator::isValid)
                .forEach(mapping -> {
                    deployedMappingsOutbound.put(mapping.getIdentifier(), mapping);
                    log.debug("{} - Deployed outbound mapping: {}", tenant, mapping.getIdentifier());
                });

        log.info("{} - Updated outbound mappings for connector: {}, active mappings: {}",
                tenant, connectorName, deployedMappingsOutbound.size());
    }

    // ===== Read-only Access Methods =====

    /**
     * Get subscription counts (modifiable)
     */
    public Map<String, MutableInt> getSubscriptionCounts() {
        return subscriptionCounts;
    }

    /**
     * Get subscription counts (read-only view)
     */
    public Map<String, MutableInt> getSubscriptionCountsView() {
        return Collections.unmodifiableMap(subscriptionCounts);
    }

    /**
     * Get deployed inbound mappings (read-only view)
     */
    public Map<String, Mapping> getDeployedMappingsInbound() {
        return Collections.unmodifiableMap(deployedMappingsInbound);
    }

    /**
     * Get deployed outbound mappings (read-only view)
     */
    public Map<String, Mapping> getDeployedMappingsOutbound() {
        return Collections.unmodifiableMap(deployedMappingsOutbound);
    }

    /**
     * Check if inbound mapping is deployed
     */
    public boolean isMappingInboundDeployed(String identifier) {
        return deployedMappingsInbound.containsKey(identifier);
    }

    /**
     * Check if outbound mapping is deployed
     */
    public boolean isMappingOutboundDeployed(String identifier) {
        return deployedMappingsOutbound.containsKey(identifier);
    }

    /**
     * Get inbound mapping by identifier
     */
    public Mapping getInboundMapping(String identifier) {
        return deployedMappingsInbound.get(identifier);
    }

    /**
     * Get outbound mapping by identifier
     */
    public Mapping getOutboundMapping(String identifier) {
        return deployedMappingsOutbound.get(identifier);
    }

    /**
     * Get count of active subscriptions
     */
    public int getSubscriptionCount() {
        return subscriptionCounts.size();
    }

    /**
     * Get count of deployed inbound mappings
     */
    public int getInboundMappingCount() {
        return deployedMappingsInbound.size();
    }

    /**
     * Get count of deployed outbound mappings
     */
    public int getOutboundMappingCount() {
        return deployedMappingsOutbound.size();
    }

    /**
     * Clear all subscriptions and mappings
     */
    public void clear() {
        subscriptionCounts.clear();
        deployedMappingsInbound.clear();
        deployedMappingsOutbound.clear();
        log.debug("{} - Cleared all subscriptions and mappings for connector: {}", tenant, connectorName);
    }

    @FunctionalInterface
    public interface MappingValidator {
        boolean isValid(Mapping mapping);
    }
}