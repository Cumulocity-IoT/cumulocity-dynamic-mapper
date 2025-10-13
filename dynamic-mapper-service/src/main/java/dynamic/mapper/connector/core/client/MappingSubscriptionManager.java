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
 * Manages the active/applied mappings for a specific connector instance.
 * 
 * <p>This class determines which mappings are <strong>currently applied</strong> on a connector
 * by evaluating three conditions:
 * <ol>
 *   <li>The mapping must be <strong>active</strong> (enabled)</li>
 *   <li>The mapping must be <strong>deployed to this connector</strong> (configured in deployment map)</li>
 *   <li>The mapping must be <strong>compatible</strong> with the connector's capabilities 
 *       (e.g., wildcard support, validated via {@link MappingValidator})</li>
 * </ol>
 * 
 * <p>For <strong>inbound mappings</strong> (device → platform), this class also manages the actual
 * MQTT topic subscriptions, ensuring that:
 * <ul>
 *   <li>Topics are only subscribed to when at least one mapping needs them</li>
 *   <li>Reference counting prevents premature unsubscription when multiple mappings share a topic</li>
 *   <li>QoS levels are properly maintained (using the highest QoS among mappings for a topic)</li>
 * </ul>
 * 
 * <p>For <strong>outbound mappings</strong> (platform → device), this class tracks which mappings
 * are applied, but no subscription management is needed.
 * 
 * <p><strong>Thread-safety:</strong> All operations are thread-safe using {@link ConcurrentHashMap}.
 * 
 * @see dynamic.mapper.service.deployment.DeploymentMapService
 */
@Slf4j
public class MappingSubscriptionManager {

    private final String tenant;
    private final String connectorName;

    // Track reference count per subscribed topic (for shared subscriptions)
    private final Map<String, MutableInt> subscriptionCounts = new ConcurrentHashMap<>();

    // Track mappings that are currently applied on this connector
    // Structure: <mapping identifier, mapping>
    private final Map<String, Mapping> effectiveMappingsInbound = new ConcurrentHashMap<>();
    private final Map<String, Mapping> effectiveMappingsOutbound = new ConcurrentHashMap<>();

    // Callback interface for actual connector subscribe/unsubscribe operations
    private final SubscriptionCallback subscriptionCallback;

    /**
     * Callback interface for connector-specific subscription operations.
     * Implementations handle the actual protocol-level subscribe/unsubscribe.
     */
    public interface SubscriptionCallback {
        /**
         * Subscribe to a topic with specified QoS level
         * 
         * @param topic the topic to subscribe to
         * @param qos the quality of service level
         * @throws ConnectorException if subscription fails
         */
        void subscribe(String topic, Qos qos) throws ConnectorException;

        /**
         * Unsubscribe from a topic
         * 
         * @param topic the topic to unsubscribe from
         * @throws ConnectorException if unsubscription fails
         */
        void unsubscribe(String topic) throws ConnectorException;
    }

    /**
     * Creates a new mapping subscription manager for a connector.
     * 
     * @param tenant the tenant identifier
     * @param connectorName the name of the connector
     * @param callback the callback for actual subscription operations
     */
    public MappingSubscriptionManager(String tenant, String connectorName, SubscriptionCallback callback) {
        this.tenant = tenant;
        this.connectorName = connectorName;
        this.subscriptionCallback = callback;
    }

    // ===== Inbound Subscription Management =====

    /**
     * Adds (or increments) a subscription for an inbound mapping.
     * 
     * <p>If this is the first mapping for the topic, performs the actual subscription.
     * Otherwise, increments the reference count. The mapping is marked as applied/effective.
     * 
     * @param mapping the mapping to activate
     * @param qos the quality of service level for the subscription
     * @throws ConnectorException if the subscription operation fails
     */
    public void addSubscriptionInbound(Mapping mapping, Qos qos) throws ConnectorException {
        
        String topic = mapping.getMappingTopic();
        MutableInt count = subscriptionCounts.computeIfAbsent(topic, k -> new MutableInt(0));

        boolean isNewSubscription = count.intValue() == 0;
        count.increment();

        effectiveMappingsInbound.put(mapping.getIdentifier(), mapping);

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
     * Removes (or decrements) a subscription for an inbound mapping.
     * 
     * <p>Decrements the reference count for the topic. If no mappings remain for the topic,
     * performs the actual unsubscription. The mapping is removed from the applied/effective set.
     * 
     * @param mapping the mapping to deactivate
     * @throws ConnectorException if the unsubscription operation fails
     */
    public void removeSubscriptionInbound(Mapping mapping) throws ConnectorException {
        String topic = mapping.getMappingTopic();
        MutableInt count = subscriptionCounts.get(topic);
        if (count == null) {
            log.warn("{} - Attempted to remove non-existent subscription for topic: [{}]", tenant, topic);
            return;
        }

        count.decrement();
        effectiveMappingsInbound.remove(mapping.getIdentifier());

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
     * Synchronizes inbound subscriptions with a new list of mappings.
     * 
     * <p>Evaluates which mappings should be applied based on:
     * <ul>
     *   <li>Mapping active status</li>
     *   <li>Validation by the provided {@link MappingValidator} (checks deployment and compatibility)</li>
     * </ul>
     * 
     * <p>Automatically subscribes to new topics and unsubscribes from topics no longer needed.
     * For topics with multiple mappings, uses the highest QoS level among them.
     * 
     * @param updatedMappings the complete list of mappings to evaluate
     * @param reset if true, clears all existing subscriptions before applying new ones
     * @param isConnected whether the connector is currently connected (no-op if false)
     * @param validator validates if a mapping should be applied (checks deployment + compatibility)
     */
    public void updateSubscriptionsInbound(List<Mapping> updatedMappings,
            boolean reset,
            boolean isConnected,
            MappingValidator validator) {
        if (!isConnected) {
            log.debug("{} - Not connected, skipping subscription update", tenant);
            return;
        }

        if (reset) {
            subscriptionCounts.clear();
            effectiveMappingsInbound.clear();
        }

        Map<String, MutableInt> newSubscriptions = new HashMap<>();
        Map<String, Qos> topicQosMap = new HashMap<>();

        // Build new subscription state from active, valid, deployed mappings
        updatedMappings.stream()
                .filter(Mapping::getActive)
                .filter(validator::isValid)
                .forEach(mapping -> {
                    String topic = mapping.getMappingTopic();
                    newSubscriptions.computeIfAbsent(topic, k -> new MutableInt(0)).increment();
                    effectiveMappingsInbound.put(mapping.getIdentifier(), mapping);

                    // Track max QoS per topic (use highest QoS among all mappings for that topic)
                    Qos currentQos = topicQosMap.getOrDefault(topic, Qos.AT_MOST_ONCE);
                    if (mapping.getQos().ordinal() > currentQos.ordinal()) {
                        topicQosMap.put(topic, mapping.getQos());
                    }
                });

        // Remove subscriptions for topics no longer needed
        unsubscribeUnusedTopics(newSubscriptions);

        // Add subscriptions for new topics
        subscribeToNewTopics(newSubscriptions, topicQosMap);

        subscriptionCounts.putAll(newSubscriptions);

        log.info("{} - Updated subscriptions for connector: {}, active topics: {}",
                tenant, connectorName, subscriptionCounts.size());
    }

    /**
     * Unsubscribes from topics that are no longer needed.
     */
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

    /**
     * Subscribes to new topics that weren't previously subscribed.
     */
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
     * Marks an outbound mapping as applied/effective on this connector.
     * 
     * <p>No actual subscription is performed for outbound mappings; this only tracks
     * which mappings should be used when processing platform → device messages.
     * 
     * @param identifier the unique mapping identifier
     * @param mapping the mapping to apply
     */
    public void addSubscriptionOutbound(String identifier, Mapping mapping) {
        effectiveMappingsOutbound.put(identifier, mapping);
        log.debug("{} - Added outbound mapping: {}", tenant, identifier);
    }

    /**
     * Removes an outbound mapping from the applied/effective set.
     * 
     * @param identifier the unique mapping identifier
     */
    public void removeSubscriptionOutbound(String identifier) {
        if (effectiveMappingsOutbound.remove(identifier) != null) {
            log.debug("{} - Removed outbound mapping: {}", tenant, identifier);
        }
    }

    /**
     * Synchronizes outbound mappings with a new list of mappings.
     * 
     * <p>Evaluates which mappings should be applied based on:
     * <ul>
     *   <li>Mapping active status</li>
     *   <li>Validation by the provided {@link MappingValidator} (checks deployment and compatibility)</li>
     * </ul>
     * 
     * @param updatedMappings the complete list of mappings to evaluate
     * @param validator validates if a mapping should be applied (checks deployment + compatibility)
     */
    public void updateSubscriptionsOutbound(List<Mapping> updatedMappings, MappingValidator validator) {
        effectiveMappingsOutbound.clear();

        updatedMappings.stream()
                .filter(Mapping::getActive)
                .filter(validator::isValid)
                .forEach(mapping -> {
                    effectiveMappingsOutbound.put(mapping.getIdentifier(), mapping);
                    log.debug("{} - Deployed outbound mapping: {}", tenant, mapping.getIdentifier());
                });

        log.info("{} - Updated outbound mappings for connector: {}, active mappings: {}",
                tenant, connectorName, effectiveMappingsOutbound.size());
    }

    // ===== Read-only Access Methods =====

    /**
     * Gets the subscription reference counts per topic.
     * 
     * <p><strong>Warning:</strong> Returns mutable map. Use {@link #getSubscriptionCountsView()}
     * for read-only access.
     * 
     * @return mutable map of topic → reference count
     * @deprecated Use {@link #getSubscriptionCountsView()} for safer read-only access
     */
    public Map<String, MutableInt> getSubscriptionCounts() {
        return subscriptionCounts;
    }

    /**
     * Gets an unmodifiable view of subscription reference counts per topic.
     * 
     * @return read-only map of topic → reference count
     */
    public Map<String, MutableInt> getSubscriptionCountsView() {
        return Collections.unmodifiableMap(subscriptionCounts);
    }

    /**
     * Gets all currently applied inbound mappings.
     * 
     * @return read-only map of mapping identifier → mapping
     */
    public Map<String, Mapping> getEffectiveMappingsInbound() {
        return Collections.unmodifiableMap(effectiveMappingsInbound);
    }

    /**
     * Gets all currently applied outbound mappings.
     * 
     * @return read-only map of mapping identifier → mapping
     */
    public Map<String, Mapping> getEffectiveMappingsOutbound() {
        return Collections.unmodifiableMap(effectiveMappingsOutbound);
    }

    /**
     * Checks if an inbound mapping is currently applied on this connector.
     * 
     * @param identifier the unique mapping identifier
     * @return true if the mapping is active, deployed, compatible, and subscribed
     */
    public boolean isMappingInboundEffective(String identifier) {
        return effectiveMappingsInbound.containsKey(identifier);
    }

    /**
     * Checks if an outbound mapping is currently applied on this connector.
     * 
     * @param identifier the unique mapping identifier
     * @return true if the mapping is active, deployed, and compatible
     */
    public boolean isMappingOutboundEffective(String identifier) {
        return effectiveMappingsOutbound.containsKey(identifier);
    }

    /**
     * Gets the count of active topic subscriptions.
     * 
     * @return number of unique topics currently subscribed
     */
    public int getSubscriptionCount() {
        return subscriptionCounts.size();
    }

    /**
     * Gets the count of applied inbound mappings.
     * 
     * @return number of inbound mappings currently effective
     */
    public int getEffectiveInboundMappingCount() {
        return effectiveMappingsInbound.size();
    }

    /**
     * Gets the count of applied outbound mappings.
     * 
     * @return number of outbound mappings currently effective
     */
    public int getEffectiveOutboundMappingCount() {
        return effectiveMappingsOutbound.size();
    }

    /**
     * Clears all subscriptions and mappings.
     * 
     * <p><strong>Warning:</strong> This does not perform actual unsubscription operations.
     * It only clears the internal state. Typically called during connector shutdown.
     */
    public void clear() {
        subscriptionCounts.clear();
        effectiveMappingsInbound.clear();
        effectiveMappingsOutbound.clear();
        log.debug("{} - Cleared all subscriptions and mappings for connector: {}", tenant, connectorName);
    }

    /**
     * Functional interface for validating if a mapping should be applied on a connector.
     * 
     * <p>Implementations typically check:
     * <ul>
     *   <li>Whether the mapping is deployed to the connector</li>
     *   <li>Whether the mapping is compatible with connector capabilities (e.g., wildcard support)</li>
     * </ul>
     */
    @FunctionalInterface
    public interface MappingValidator {
        /**
         * Validates if a mapping should be applied.
         * 
         * @param mapping the mapping to validate
         * @return true if the mapping should be applied; false otherwise
         */
        boolean isValid(Mapping mapping);
    }
}