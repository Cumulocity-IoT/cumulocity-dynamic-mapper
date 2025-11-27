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
package dynamic.mapper.processor.flow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

import dynamic.mapper.core.InventoryEnrichmentClient;
import lombok.extern.slf4j.Slf4j;

/**
 * Simple implementation of FlowContext for JavaScript execution
 */
@Slf4j
public class SimpleFlowContext implements DataPrepContext {

    private final Map<String, Object> state;
    private final Context graalContext;
    private final String tenant;
    private final InventoryEnrichmentClient inventoryEnrichmentClient;
    private Boolean testing;

    public SimpleFlowContext(Context graalContext, String tenant, InventoryEnrichmentClient inventoryEnrichmentClient,
            Boolean testing) {
        this.state = new HashMap<>();
        this.graalContext = graalContext;
        this.tenant = tenant != null ? tenant : "unknown";
        this.inventoryEnrichmentClient = inventoryEnrichmentClient;
        this.testing = testing;
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
    public Value getConfig() {
        if (graalContext == null) {
            return null;
        }

        // Create a basic configuration
        Map<String, Object> config = new HashMap<>();
        config.put("tenant", tenant);
        config.put("timestamp", System.currentTimeMillis());

        return graalContext.asValue(config);
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
    public Value getManagedObjectByDeviceId(String deviceId) {
        Object javaValue = inventoryEnrichmentClient.getMOFromInventoryCache(tenant, deviceId, testing);
        if (javaValue == null) {
            addWarning(String.format("Device not found in inventory cache: %s", deviceId));
        }
        return graalContext.asValue(javaValue);
    }

    @Override
    public Value getManagedObject(ExternalId externalId) {
        Object javaValue = inventoryEnrichmentClient.getMOFromInventoryCacheByExternalId(tenant, externalId,
                testing);
        if (javaValue == null) {
            addWarning(String.format("ExternalId not found in inventory cache: %s", externalId));
        }
        return graalContext.asValue(javaValue);
    }

    @Override
    public Value getManagedObject(Value externalIdValue) {
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
                extId = new ExternalId(externalId, type); // Adjust based on your ExternalId constructor
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
        state.clear();
    }

    @Override
    public Boolean getTesting() {
        return testing;
    }
}