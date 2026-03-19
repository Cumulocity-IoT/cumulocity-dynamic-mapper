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

import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.model.DataPrepContext;
import dynamic.mapper.processor.model.JavaExtensionContext;
import dynamic.mapper.processor.model.ExternalId;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.service.cache.FlowStateStore;
import lombok.extern.slf4j.Slf4j;
import org.graalvm.polyglot.Value;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Simple implementation of JavaExtensionContext for Java extensions.
 *
 * <p>This class wraps a ProcessingContext and optionally a DataPrepContext
 * to provide a clean API for extension developers following the SMART function pattern.</p>
 *
 * <p>It delegates data prep operations to the underlying DataPrepContext
 * and provides direct access to C8YAgent, mapping, and other necessary components.</p>
 */
@Slf4j
public class JavaExtensionContextImpl implements JavaExtensionContext {

    private final DataPrepContext dataPrepContext;
    private final C8YAgent c8yAgent;
    private final String tenant;
    private final Boolean testing;
    private final Mapping mapping;
    private final ProcessingContext<?> processingContext;

    // Native (non-GraalVM) state for Java Extensions
    private final Map<String, Object> nativeState;
    private final FlowStateStore flowStateStore;
    private final String mappingIdentifier;

    // Resolved external ID for outbound mappings (set by EnrichmentOutboundProcessor)
    private String externalId;

    /**
     * Full constructor with data prep support and persistent native state.
     *
     * @param dataPrepContext   The underlying data preparation context (may be null for EXTENSION_JAVA)
     * @param c8yAgent          The Cumulocity agent
     * @param tenant            The tenant identifier
     * @param testing           Whether this is a test execution
     * @param mapping           The mapping configuration
     * @param processingContext The processing context for warnings/logs
     * @param flowStateStore    Store for persisting native state across invocations (may be null)
     * @param initialState      Pre-loaded native state from the store (may be null)
     */
    public JavaExtensionContextImpl(
            DataPrepContext dataPrepContext,
            C8YAgent c8yAgent,
            String tenant,
            Boolean testing,
            Mapping mapping,
            ProcessingContext<?> processingContext,
            FlowStateStore flowStateStore,
            Map<String, Object> initialState) {
        this.dataPrepContext = dataPrepContext;
        this.c8yAgent = c8yAgent;
        this.tenant = tenant != null ? tenant : "unknown";
        this.testing = testing != null ? testing : false;
        this.mapping = mapping;
        this.processingContext = processingContext;
        this.flowStateStore = flowStateStore;
        this.mappingIdentifier = mapping != null ? mapping.getIdentifier() : null;
        this.nativeState = (initialState != null && !initialState.isEmpty())
                ? new HashMap<>(initialState) : new HashMap<>();
    }

    /**
     * Backward-compatible constructor without native state persistence.
     *
     * @param dataPrepContext   The underlying data preparation context
     * @param c8yAgent          The Cumulocity agent
     * @param tenant            The tenant identifier
     * @param testing           Whether this is a test execution
     * @param mapping           The mapping configuration
     * @param processingContext The processing context for warnings/logs
     */
    public JavaExtensionContextImpl(
            DataPrepContext dataPrepContext,
            C8YAgent c8yAgent,
            String tenant,
            Boolean testing,
            Mapping mapping,
            ProcessingContext<?> processingContext) {
        this(dataPrepContext, c8yAgent, tenant, testing, mapping, processingContext, null, null);
    }

    /**
     * Simplified constructor without data prep context.
     * State management and inventory lookups will not be available.
     *
     * @param c8yAgent          The Cumulocity agent
     * @param tenant            The tenant identifier
     * @param testing           Whether this is a test execution
     * @param mapping           The mapping configuration
     * @param processingContext The processing context for warnings/logs
     */
    public JavaExtensionContextImpl(
            C8YAgent c8yAgent,
            String tenant,
            Boolean testing,
            Mapping mapping,
            ProcessingContext<?> processingContext) {
        this(null, c8yAgent, tenant, testing, mapping, processingContext, null, null);
    }

    // ==================== JavaExtensionContext Methods ====================

    @Override
    public String getTenant() {
        return tenant;
    }

    @Override
    public Mapping getMapping() {
        return mapping;
    }

    @Override
    public void addWarning(String warning) {
        if (processingContext != null) {
            processingContext.getWarnings().add(warning);
        }
        log.warn("{} - Extension warning: {}", tenant, warning);
    }

    @Override
    public void addLog(String logMessage) {
        if (processingContext != null) {
            processingContext.getLogs().add(logMessage);
        }
        log.debug("{} - Extension log: {}", tenant, logMessage);
    }

    @Override
    public java.util.Map<String, Object> getConfigAsMap() {
        java.util.Map<String, Object> config = new java.util.HashMap<>();
        config.put("tenant", tenant);
        config.put("clientId", getClientId());
        if (externalId != null) {
            config.put("externalId", externalId);
        }
        if (mapping != null) {
            config.put("mappingId", mapping.getId());
            config.put("mappingName", mapping.getName());
            config.put("targetAPI", mapping.getTargetAPI() != null ? mapping.getTargetAPI().toString() : null);
            config.put("debug", mapping.getDebug());
            // Add extension parameter provided by the user in the UI under the "parameter" key
            if (mapping.getExtension() != null && mapping.getExtension().getParameter() != null) {
                config.put("parameter", mapping.getExtension().getParameter());
            }
        }
        if (processingContext != null) {
            config.put("topic", processingContext.getTopic());
        }
        return config;
    }

    /**
     * Set the resolved external ID for this outbound invocation.
     * Called by EnrichmentOutboundProcessor after resolving the C8Y global ID.
     *
     * @param externalId The resolved external identifier of the source device
     */
    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }

    /**
     * Get the resolved external ID for this outbound invocation.
     *
     * @return The resolved external identifier, or null if not available
     */
    @Override
    public String getExternalId() {
        return externalId;
    }

    @Override
    public java.util.Map<String, Object> getManagedObjectAsMap(ExternalId externalId) {
        if (c8yAgent == null) {
            log.warn("{} - getManagedObjectAsMap() called but C8YAgent not available", tenant);
            return null;
        }
        // Direct cache lookup without exposing C8YAgent to extensions
        return c8yAgent.getMOFromInventoryCacheByExternalId(tenant, externalId, testing);
    }

    @Override
    public String getClientId() {
        if (dataPrepContext != null) {
            return dataPrepContext.getClientId();
        }
        log.warn("{} - getClientId() called but DataPrepContext not available", tenant);
        return null;
    }

    // ==================== DataPrepContext Delegation ====================

    @Override
    public void setState(String key, Value value) {
        if (dataPrepContext != null) {
            dataPrepContext.setState(key, value);
        } else {
            log.warn("{} - setState() called but DataPrepContext not available", tenant);
        }
    }

    @Override
    public Value getState(String key) {
        if (dataPrepContext != null) {
            return dataPrepContext.getState(key);
        }
        log.warn("{} - getState() called but DataPrepContext not available", tenant);
        return null;
    }

    @Override
    public Value getStateAll() {
        if (dataPrepContext != null) {
            return dataPrepContext.getStateAll();
        }
        log.warn("{} - getStateAll() called but DataPrepContext not available", tenant);
        return null;
    }

    @Override
    public Value getStateKeySet() {
        if (dataPrepContext != null) {
            return dataPrepContext.getStateKeySet();
        }
        log.warn("{} - getStateKeySet() called but DataPrepContext not available", tenant);
        return null;
    }

    @Override
    public Value getDTMAsset(String assetId) {
        if (dataPrepContext != null) {
            return dataPrepContext.getDTMAsset(assetId);
        }
        log.warn("{} - getDTMAsset() called but DataPrepContext not available", tenant);
        return null;
    }

    @Override
    public Value getManagedObject(String c8ySourceId) {
        if (dataPrepContext != null) {
            return dataPrepContext.getManagedObject(c8ySourceId);
        }
        log.warn("{} - getManagedObject() called but DataPrepContext not available", tenant);
        return null;
    }

    @Override
    public Value getManagedObjectByExternalId(ExternalId externalId) {
        if (dataPrepContext != null) {
            return dataPrepContext.getManagedObjectByExternalId(externalId);
        }
        log.warn("{} - getManagedObjectByExternalId() called but DataPrepContext not available", tenant);
        return null;
    }

    @Override
    public Value getManagedObjectByExternalId(Value value) {
        if (dataPrepContext != null) {
            return dataPrepContext.getManagedObjectByExternalId(value);
        }
        log.warn("{} - getManagedObjectByExternalId(Value) called but DataPrepContext not available", tenant);
        return null;
    }

    @Override
    public void addLogMessage(String message) {
        if (dataPrepContext != null) {
            dataPrepContext.addLogMessage(message);
        } else {
            addLog(message);
        }
    }

    @Override
    public Boolean getTesting() {
        return testing;
    }

    @Override
    public void clearState() {
        if (dataPrepContext != null) {
            dataPrepContext.clearState();
        }
        // Persist native state to FlowStateStore
        if (flowStateStore != null && mappingIdentifier != null) {
            flowStateStore.saveState(tenant, mappingIdentifier, new HashMap<>(nativeState));
        }
        nativeState.clear();
    }

    // ==================== Native (Java) State Methods ====================

    @Override
    public void setNativeState(String key, Object value) {
        if (key == null) {
            log.warn("{} - Cannot set native state with null key", tenant);
            return;
        }
        if (value == null) {
            nativeState.remove(key);
        } else {
            nativeState.put(key, value);
        }
        log.debug("{} - Native flow state set: {}={}", tenant, key, value);
    }

    @Override
    public Object getNativeState(String key) {
        return nativeState.get(key);
    }

    @Override
    public Map<String, Object> getNativeStateAll() {
        return Collections.unmodifiableMap(nativeState);
    }
}
