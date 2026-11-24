/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
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

package dynamic.mapper.processor.model;

import java.util.Map;

import dynamic.mapper.model.Mapping;

/**
 * Context interface for Java Extension data preparation following SMART function pattern.
 *
 * <p>This interface extends {@link DataPrepContext} to provide additional methods
 * needed for extension development, including mapping configuration, inventory cache access,
 * and utility methods for warnings and logs.</p>
 *
 * <p>The context provides read-only access to:</p>
 * <ul>
 *   <li>State management (via {@link DataPrepContext})</li>
 *   <li>Inventory lookups via {@link #getManagedObjectAsMap(ExternalId)}</li>
 *   <li>Mapping configuration</li>
 *   <li>Tenant and testing information</li>
 *   <li>Client ID from connector message</li>
 * </ul>
 *
 * <p>Example usage in an inbound extension:</p>
 * <pre>
 * {@code
 * @Override
 * public CumulocityObject[] onMessage(Message<byte[]> message, JavaExtensionContext context) {
 *     // Get device from inventory cache
 *     ExternalId extId = new ExternalId("myDevice", "c8y_Serial");
 *     Map<String, Object> device = context.getManagedObjectAsMap(extId);
 *
 *     // Access client ID
 *     String clientId = context.getClientId();
 *
 *     // Use native state for tracking (the Value-based setState/getState of DataPrepContext throw here)
 *     context.setNativeState("lastProcessed", System.currentTimeMillis());
 *
 *     // Add warnings if needed
 *     context.addWarning("Device not found, using implicit creation");
 *
 *     return new CumulocityObject[] { ... };
 * }
 * }
 * </pre>
 *
 * @see DataPrepContext
 * @see Message
 * @see CumulocityObject
 * @see DeviceMessage
 */
public interface JavaExtensionContext extends DataPrepContext {

    /**
     * Lookup managed object from inventory cache by external ID (Java-friendly method).
     *
     * <p>This method provides the same functionality as {@link #getManagedObjectByExternalId(ExternalId)}
     * but returns a standard Java Map instead of a GraalVM Value object, making it suitable
     * for pure Java Extensions.</p>
     *
     * <p>This is a cache-first lookup - it checks the inventory cache first and only
     * fetches from the Cumulocity API if there's a cache miss.</p>
     *
     * <p>Example usage:</p>
     * <pre>
     * {@code
     * ExternalId externalId = new ExternalId("sensor-001", "c8y_Serial");
     * Map<String, Object> device = context.getManagedObjectAsMap(externalId);
     * if (device != null) {
     *     String deviceName = (String) device.get("name");
     *     Object assetParents = device.get("assetParents");
     * }
     * }
     * </pre>
     *
     * @param externalId The external ID of the device to lookup
     * @return Map containing the device properties from cache, or null if not found
     * @since 6.2
     */
    Map<String, Object> getManagedObjectAsMap(ExternalId externalId);

    /**
     * Lookup managed object from the inventory cache by internal Cumulocity id, for example the {@code sourceId}
     * an outbound message carries. Cache-first like {@link #getManagedObjectAsMap(ExternalId)}.
     *
     * <p>The result is a copy: changing it does not change what other messages see.</p>
     *
     * @param c8ySourceId the internal id of the managed object
     * @return Map containing the device properties, or null if not found
     */
    Map<String, Object> getManagedObjectAsMap(String c8ySourceId);

    /**
     * Adds {@code by} to the number held under {@code key} (or stores it if there is none) in one atomic step and
     * returns the new value, so concurrent messages never lose a count. Unlike {@link #setNativeState} it is written
     * at once, not after the send succeeded: a redelivered message counts again. In a test run it counts on
     * throw-away state.
     *
     * @throws IllegalArgumentException   if the key holds something that is not a number
     * @throws dynamic.mapper.processor.flow.FlowStateStore.StateLimitExceededException if the mapping already holds
     *                                    the maximum number of state keys
     */
    long incrementNativeState(String key, long by);

    /**
     * Reports whether the same key was already seen for this mapping within the window, and records it otherwise.
     * Atomic: of two copies of a message processed at the same time, exactly one gets {@code false}. Shares its
     * store with the {@code $isDuplicate} filter function and {@code context.isDuplicate} of Smart Functions. A
     * fingerprint recorded for a message that then fails is given back, so a retry is not dropped as a repeat.
     * Fails open (returns false, adds a warning) for a null key or a window outside 1 to 3600 seconds, and always
     * returns false in a test run.
     *
     * @param key           anything that identifies the message, for example {@code externalId + "|" + time}
     * @param windowSeconds 1 to 3600
     */
    boolean isDuplicate(String key, int windowSeconds);

    /**
     * Adds one item to a named group and returns the whole group, as a map of item key to value, once it is
     * complete; returns null until then. Turns several messages into one. The group is complete when its item
     * count reaches {@code expectedCount}. Atomic: of several messages processed at the same time, exactly one
     * receives a given group. A repeated {@code itemKey} replaces the earlier value. The value is copied, so
     * changing it afterwards does not change the group.
     *
     * <p>Groups are kept per mapping and in memory only, so they are lost on a restart. Unlike
     * {@link #setNativeState} the item is stored at once, not after the send succeeded: a redelivered message
     * adds its item again. Fails open (returns null, adds a warning) for a missing argument, a count outside 1 to
     * 10000, or when the mapping's state limits are reached. In a test run nothing is collected: it returns null
     * with a warning.</p>
     *
     * @return the group (item key to value), or null while it is incomplete
     */
    Map<String, Object> collectNative(String groupKey, String itemKey, Object value, int expectedCount);

    /**
     * Like {@link #collectNative(String, String, Object, int)}, with the full set of completion conditions: a
     * count, a set of item keys that must all be present, or {@code last} for the final item, plus an optional
     * {@code maxAgeSeconds} after which an incomplete group is dropped.
     */
    Map<String, Object> collectNative(String groupKey, String itemKey, Object value,
            dynamic.mapper.processor.flow.FlowStateStore.CollectOptions options);

    /**
     * Removes a collected group and returns it as it is, complete or not; null if there is none or in a test run.
     * For example, to flush the group of the previous timestamp when a newer one arrives.
     */
    Map<String, Object> takeNative(String groupKey);

    /**
     * Lists the keys of the incomplete collected groups that start with the prefix, oldest first. Pair it with
     * {@link #takeNative}. Empty in a test run.
     */
    java.util.List<String> openGroupsNative(String prefix);

    /**
     * Lookup inventory device properties by external id, as a GraalVM {@link org.graalvm.polyglot.Value}.
     * Not part of {@link DataPrepContext}: scripts call {@link #getManagedObjectByExternalId(org.graalvm.polyglot.Value)},
     * and a second overload there makes {@code null} and {@code undefined} ambiguous. Java code prefers
     * {@link #getManagedObjectAsMap(ExternalId)}.
     *
     * @param externalId The external id to lookup
     * @return the device properties, or null
     */
    org.graalvm.polyglot.Value getManagedObjectByExternalId(ExternalId externalId);

    /**
     * Get the mapping configuration for the current invocation (Java-native access).
     *
     * <p>Returns the same metadata as {@link DataPrepContext#getConfig()} but as a
     * plain Java {@link Map} instead of a GraalVM Value, making it suitable for
     * pure Java extensions.</p>
     *
     * <p>The map contains read-only fields such as {@code mappingId},
     * {@code mappingName}, {@code tenant}, {@code topic}, {@code targetAPI},
     * {@code debug} and {@code clientId}. It is populated before the extension
     * is called and does <em>not</em> persist across invocations (unlike state).</p>
     *
     * @return Map containing the mapping configuration, never {@code null}
     * @since 6.2
     */
    Map<String, Object> getConfigAsMap();

    /**
     * Get the tenant identifier.
     *
     * @return The tenant ID
     */
    String getTenant();

    /**
     * Get the mapping configuration being processed.
     *
     * <p>The mapping contains configuration such as:</p>
     * <ul>
     *   <li>Source and target templates</li>
     *   <li>Transformation type</li>
     *   <li>External ID type</li>
     *   <li>Target API</li>
     *   <li>Publish topic (for outbound)</li>
     * </ul>
     *
     * @return The mapping configuration
     */
    Mapping getMapping();

    /**
     * Add a warning message to the processing context.
     *
     * <p>Warnings are logged and may be displayed to users for debugging purposes.
     * Use this for non-fatal issues that should be brought to attention.</p>
     *
     * @param warning The warning message
     */
    void addWarning(String warning);

    /**
     * Add a log message to the processing context.
     *
     * <p>Logs are collected for debugging and audit purposes. Use this for
     * informational messages about processing steps.</p>
     *
     * @param log The log message
     */
    void addLog(String log);

    /**
     * Store a value in persistent flow state using plain Java objects (no GraalVM dependency).
     *
     * <p>State is persisted across message invocations for the same mapping so that Java
     * Extensions can accumulate data such as counters, running averages, or last-seen values.</p>
     *
     * @param key   state key (non-null)
     * @param value any serialisable Java object; {@code null} removes the key
     */
    void setNativeState(String key, Object value);

    /**
     * Retrieve a value from persistent flow state as a plain Java object.
     *
     * @param key the state key
     * @return the stored value, or {@code null} if not present
     */
    Object getNativeState(String key);

    /**
     * Return an unmodifiable view of the entire flow state map.
     *
     * @return all current state entries (never {@code null})
     */
    Map<String, Object> getNativeStateAll();

    /**
     * Get the resolved external ID for this outbound invocation.
     *
     * <p>Only populated for outbound mappings when {@code useExternalId} is enabled and
     * a non-empty {@code externalIdType} is configured. Set by the enrichment processor
     * after resolving the C8Y global ID to the configured external ID type.</p>
     *
     * @return the resolved external identifier, or {@code null} if not available
     */
    String getExternalId();
}
