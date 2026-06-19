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

import org.graalvm.polyglot.Value;

/**
 * Flow context for JavaScript execution
 */
public interface DataPrepContext {

    String WARNINGS = "_WARNINGS_";
    String LOGS = "_LOGS_";

    /**
     * Sets a value in the context's state.
     * 
     * @param key   The key for the state item.
     * @param value The value to set for the given key.
     */
    void setState(String key, Value value);

    /**
     * Retrieves a value from the context's state.
     *
     * @param key The key of the state item to retrieve.
     * @return The value associated with the key, or null if not found.
     */
    Value getState(String key);

    /**
     * Retrieves a value from the context's state, returning a default if absent.
     *
     * <p>Called by V2 Smart Functions as {@code context.getState('key', defaultValue)}.
     * GraalVM resolves the 2-arg overload at runtime; the 1-arg variant is preserved
     * for backward compatibility with V1 functions.</p>
     *
     * <p>The {@code defaultValue} parameter is typed as {@code Object} (not {@code Value})
     * because GraalVM's polyglot overload resolution reliably converts any JS primitive to
     * {@code Object}, whereas conversion to {@code org.graalvm.polyglot.Value} is not
     * guaranteed when matching method overloads from JavaScript.</p>
     *
     * @param key          The key of the state item to retrieve.
     * @param defaultValue Any Java or JavaScript value to return when the key is absent.
     * @return The stored value wrapped as a GraalVM Value, or the default wrapped as a Value.
     */
    Value getState(String key, Object defaultValue);

    /**
     * Retrieves all values from the context's state.
     * 
     * @return The value associated with all keys, or null if not found.
     */
    Value getStateAll();

    /**
     * Retrieves the keys from the context's state.
     * 
     * @return The keys stored in the the context's state, or null if not found.
     */
    Value getStateKeySet();

    /**
     * Retrieves the mapping configuration for the current invocation.
     *
     * <p>The config is read-only and contains mapping metadata such as
     * {@code mappingId}, {@code mappingName}, {@code tenant}, {@code topic},
     * {@code targetAPI}, {@code debug}, {@code clientId} and related flags.
     * It is populated by the enrichment processor before the Smart Function is called
     * and does <em>not</em> persist across invocations (unlike state).</p>
     *
     * <p>For outbound SMART_FUNCTION, the config also contains {@code externalId} — the
     * resolved external identifier of the source device. This field is only present when
     * the mapping has {@code useExternalId} enabled and a non-empty {@code externalIdType}
     * configured. Use it to build broker topics directly in JavaScript:
     * <pre>
     *   const externalId = context.getConfig().externalId;
     *   return [{ topic: `measurements/${externalId}`, payload: { ... } }];
     * </pre>
     * </p>
     *
     * <p>Implementations that do not support GraalVM (e.g. Java extensions) may return
     * {@code null}; use {@link JavaExtensionContext#getConfigAsMap()} for Java-native access.</p>
     *
     * @return A GraalVM {@link Value} wrapping the config map, or {@code null} if not available.
     */
    default Value getConfig() {
        return null;
    }

    /**
     * Convenience shortcut that returns the resolved external identifier of the source device
     * for outbound Smart Functions. Equivalent to reading {@code context.getConfig().externalId}.
     * Returns {@code null} when the mapping has no {@code useExternalId} / {@code externalIdType}
     * configured, or when no config has been injected.
     *
     * @return the resolved external id string, or {@code null}
     */
    default String getExternalId() {
        return null;
    }

    /**
     * Lookup DTM Asset properties
     *
     * @param assetId The asset ID to lookup.
     * @return A Value containing the asset properties as a JS object.
     */
    Value getDTMAsset(String assetId);

    /**
     * Lookup Inventory Device properties by internal Cumulocity device ID.
     *
     * @param c8ySourceId The internal Cumulocity device ID to lookup.
     * @return A Value containing the device properties as a JS object.
     */
    Value getManagedObject(String c8ySourceId);

    /**
     * Lookup Inventory Device properties by external id.
     *
     * @param externalId The externalId to lookup.
     * @return A Value containing the device properties as a JS object.
     */
    Value getManagedObjectByExternalId(ExternalId externalId);

    /**
     * Lookup Inventory Device properties by external id.
     *
     * @param externalIdValue A Value object containing externalId and type properties
     * @return A Value containing the device properties as a JS object.
     */
    Value getManagedObjectByExternalId(Value externalIdValue);

    /**
     * Log message
     *
     * @param message Message to log
     *
     */
    public void addLogMessage(String message);

    /**
     * Alias for {@link #addLogMessage(String)} for backward compatibility with
     * JavaScript mappings that use {@code context.logMessage(...)}.
     *
     * @param message Message to log
     */
    default void logMessage(String message) {
        addLogMessage(message);
    }

    /**
     * Testing cycle indicator
     *
     * @return Is context used in a testing cycle
     *
     */
    public Boolean getTesting();

    /**
     * Get the client ID from the connector message
     *
     * @return The client ID from the inbound message, or null if not available
     * @since 6.2
     */
    String getClientId();

    void clearState();
}