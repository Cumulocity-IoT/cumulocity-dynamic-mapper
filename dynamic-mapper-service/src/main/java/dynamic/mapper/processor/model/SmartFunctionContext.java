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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

import dynamic.mapper.core.InventoryEnrichmentClient;
import dynamic.mapper.service.cache.FlowStateStore;
import lombok.extern.slf4j.Slf4j;

/**
 * Simple implementation of DataPrepContext for JavaScript execution.
 *
 * <p>When constructed with a {@link FlowStateStore} and a mapping identifier,
 * state written via {@code setState} is automatically persisted across message
 * invocations. The store is written back inside {@link #clearState()}, which is
 * called by {@code ProcessingContext.close()} at the end of each message.</p>
 */
@Slf4j
public class SmartFunctionContext implements DataPrepContext {

    private final Map<String, Object> state;
    private final Context graalContext;
    private final String tenant;
    private final InventoryEnrichmentClient inventoryEnrichmentClient;
    private Boolean testing;
    private String clientId;
    private final FlowStateStore stateStore;
    private final String mappingIdentifier;

    /**
     * Creates a context without persistence (backward-compatible constructor).
     */
    public SmartFunctionContext(Context graalContext, String tenant, InventoryEnrichmentClient inventoryEnrichmentClient,
            Boolean testing) {
        this(graalContext, tenant, inventoryEnrichmentClient, testing, null, null, null);
    }

    /**
     * Creates a context with persistent state.
     *
     * @param graalContext               the GraalVM polyglot context
     * @param tenant                     the tenant identifier
     * @param inventoryEnrichmentClient  client for inventory lookups
     * @param testing                    true when running in a test cycle
     * @param stateStore                 store used to persist state across invocations (may be null)
     * @param mappingIdentifier          the mapping's short identifier used as state key (may be null)
     * @param initialState               pre-loaded state from the store (may be null)
     */
    public SmartFunctionContext(Context graalContext, String tenant, InventoryEnrichmentClient inventoryEnrichmentClient,
            Boolean testing, FlowStateStore stateStore, String mappingIdentifier, Map<String, Object> initialState) {
        this.state = (initialState != null && !initialState.isEmpty()) ? new HashMap<>(initialState) : new HashMap<>();
        this.graalContext = graalContext;
        this.tenant = tenant != null ? tenant : "unknown";
        this.inventoryEnrichmentClient = inventoryEnrichmentClient;
        this.testing = testing;
        this.stateStore = stateStore;
        this.mappingIdentifier = mappingIdentifier;
    }

    /**
     * Set the client ID from the connector message
     *
     * @param clientId The client ID from the inbound message
     */
    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    @Override
    public void setState(String key, Value value) {
        if (key == null) {
            log.warn("{} - Cannot set state with null key", tenant);
            return;
        }

        // Convert Value to Java object for safe storage
        Object javaValue = convertValueToJavaObject(value);
        state.put(key, javaValue);

        log.debug("{} - Flow state set: {}={}", tenant, key, javaValue);
    }

    @Override
    public Value getState(String key) {
        if (key == null || graalContext == null) {
            return null;
        }

        Object javaValue = state.get(key);
        if (javaValue == null) {
            return null;
        }

        // Convert back to GraalJS Value
        return graalContext.asValue(javaValue);
    }

    @Override
    public Value getDTMAsset(String assetId) {
        if (graalContext == null) {
            return null;
        }

        log.debug("{} - DTM asset lookup requested for: {}", tenant, assetId);

        // Simple placeholder implementation
        Map<String, Object> properties = new HashMap<>();
        properties.put("assetId", assetId);
        properties.put("found", false);
        properties.put("message", "DTM lookup not implemented");

        return graalContext.asValue(properties);
    }

    /**
     * Convert GraalJS Value to Java object for safe storage
     */
    private Object convertValueToJavaObject(Value value) {
        if (value == null || value.isNull()) {
            return null;
        } else if (value.isString()) {
            return value.asString();
        } else if (value.isNumber()) {
            if (value.fitsInInt()) {
                return value.asInt();
            } else if (value.fitsInLong()) {
                return value.asLong();
            } else {
                return value.asDouble();
            }
        } else if (value.isBoolean()) {
            return value.asBoolean();
        } else if (value.hasArrayElements()) {
            // Convert to simple list for basic support
            java.util.List<Object> list = new java.util.ArrayList<>();
            long size = value.getArraySize();
            for (int i = 0; i < size; i++) {
                list.add(convertValueToJavaObject(value.getArrayElement(i)));
            }
            return list;
        } else if (value.hasMembers()) {
            // Convert to simple map for basic support
            Map<String, Object> map = new HashMap<>();
            for (String key : value.getMemberKeys()) {
                map.put(key, convertValueToJavaObject(value.getMember(key)));
            }
            return map;
        } else {
            // Fallback to string representation
            return value.toString();
        }
    }

    @Override
    public Value getStateAll() {
        if (graalContext == null) {
            return null;
        }

        Object javaValue = state;
        if (javaValue == null) {
            return null;
        }

        // Convert back to GraalJS Value
        return graalContext.asValue(javaValue);
    }

    @Override
    public Value getStateKeySet() {
        if (graalContext == null) {
            return null;
        }

        Object javaValue = state.keySet();
        if (javaValue == null) {
            return null;
        }

        // Convert back to GraalJS Value
        return graalContext.asValue(javaValue);
    }

    @Override
    public Value getManagedObject(String c8ySourceId) {
        Object javaValue = inventoryEnrichmentClient.getMOFromInventoryCache(tenant, c8ySourceId, testing);
        if (javaValue == null) {
            addWarning(String.format("Device not found in inventory cache: %s", c8ySourceId));
        }
        return graalContext.asValue(javaValue);
    }

    @Override
    public Value getManagedObjectByExternalId(ExternalId externalId) {
        Object javaValue = inventoryEnrichmentClient.getMOFromInventoryCacheByExternalId(tenant, externalId,
                testing);
        if (javaValue == null) {
            addWarning(String.format("ExternalId not found in inventory cache: %s", externalId));
        }
        return graalContext.asValue(javaValue);
    }

    @Override
    public Value getManagedObjectByExternalId(Value externalIdValue) {
        if (externalIdValue == null) {
            addWarning("ExternalId parameter is null");
            return graalContext.asValue(null);
        }

        try {
            ExternalId extId;

            // Handle if a proper ExternalId Java object is passed
            if (externalIdValue.isHostObject() && externalIdValue.asHostObject() instanceof ExternalId) {
                extId = externalIdValue.asHostObject();
            }
            // Handle JavaScript object with externalId and type properties
            else if (externalIdValue.hasMembers()) {
                String externalId = externalIdValue.getMember("externalId").asString();
                String type = externalIdValue.getMember("type").asString();
                extId = new ExternalId(externalId, type);
            } else {
                addWarning("Invalid externalId parameter format");
                return graalContext.asValue(null);
            }

            Object javaValue = inventoryEnrichmentClient.getMOFromInventoryCacheByExternalId(tenant, extId, testing);
            if (javaValue == null) {
                addWarning(String.format("ExternalId not found in inventory cache: %s", extId));
            }
            return graalContext.asValue(javaValue);

        } catch (Exception e) {
            addWarning("Failed to process externalId: " + e.getMessage());
            log.error("{} - Error processing externalId", tenant, e);
            return graalContext.asValue(null);
        }
    }

    private void addWarning(String warning) {
        List<String> warnings = (List<String>) state.get(DataPrepContext.WARNINGS);
        if (warnings == null) {
            warnings = new ArrayList<String>();
        }
        warnings.add(warning);
        state.put(DataPrepContext.WARNINGS, warnings);
    }

    @Override
    public void addLogMessage(String message) {
        List<String> logs = (List<String>) state.get(DataPrepContext.LOGS);
        if (logs == null) {
            logs = new ArrayList<String>();
        }
        logs.add(message);
        state.put(DataPrepContext.LOGS, logs);
    }

    @Override
    public void clearState() {
        if (stateStore != null && mappingIdentifier != null) {
            stateStore.saveState(tenant, mappingIdentifier, getPersistableState());
        }
        state.clear();
    }

    /**
     * Returns a copy of the state map with ephemeral keys ({@code _WARNINGS_},
     * {@code _LOGS_}) removed. Only this subset is persisted across invocations.
     */
    private Map<String, Object> getPersistableState() {
        Map<String, Object> persistable = new HashMap<>(state);
        persistable.remove(DataPrepContext.WARNINGS);
        persistable.remove(DataPrepContext.LOGS);
        return persistable;
    }

    @Override
    public Boolean getTesting() {
        return testing;
    }

    @Override
    public String getClientId() {
        return clientId;
    }
}