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

package dynamic.mapper.processor.model;

import java.util.Map;

import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.model.Mapping;

/**
 * Context interface for Java Extension data preparation following SMART function pattern.
 *
 * <p>This interface extends {@link DataPrepContext} to provide additional methods
 * needed for extension development, including access to Cumulocity agent, mapping
 * configuration, and utility methods for warnings and logs.</p>
 *
 * <p>The context provides read-only access to:</p>
 * <ul>
 *   <li>State management (via {@link DataPrepContext})</li>
 *   <li>Inventory lookups (via {@link DataPrepContext})</li>
 *   <li>Cumulocity agent for API operations</li>
 *   <li>Mapping configuration</li>
 *   <li>Tenant and testing information</li>
 * </ul>
 *
 * <p>Example usage in an inbound extension:</p>
 * <pre>
 * {@code
 * @Override
 * public CumulocityObject[] onMessage(Message<byte[]> message, DataPreparationContext context) {
 *     // Access C8Y agent for lookups
 *     C8YAgent agent = context.getC8YAgent();
 *
 *     // Get device from inventory
 *     var device = context.getManagedObject(
 *         new ExternalId("myDevice", "c8y_Serial"));
 *
 *     // Use state for tracking
 *     context.setState("lastProcessed", ...);
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
public interface DataPreparationContext extends DataPrepContext {

    /**
     * Get the Cumulocity agent for API operations.
     *
     * <p>The C8YAgent provides methods for:</p>
     * <ul>
     *   <li>Resolving external IDs to global IDs</li>
     *   <li>Creating/updating managed objects</li>
     *   <li>Accessing inventory cache</li>
     * </ul>
     *
     * <p>Note: For inbound extensions, this provides the agent instance.
     * For outbound extensions, this may return null as direct C8Y operations
     * are not typically needed.</p>
     *
     * @return The C8YAgent instance, or null if not available
     * @deprecated Use {@link #getManagedObjectAsMap(ExternalId)} instead for safer cache access
     */
    @Deprecated
    C8YAgent getC8YAgent();

    /**
     * Lookup managed object from inventory cache by external ID (Java-friendly method).
     *
     * <p>This method provides the same functionality as {@link #getManagedObject(ExternalId)}
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
     * @since 6.1.6
     */
    Map<String, Object> getManagedObjectAsMap(ExternalId externalId);

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
}
