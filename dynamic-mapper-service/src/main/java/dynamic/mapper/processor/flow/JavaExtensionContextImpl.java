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

package dynamic.mapper.processor.flow;

import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.model.DataPrepContext;
import dynamic.mapper.processor.model.JavaExtensionContext;
import dynamic.mapper.processor.model.ExternalId;
import dynamic.mapper.processor.runtime.ProcessingContext;
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
    // Keys this invocation wrote, and whether it called clearState(): only these reach the store.
    private final java.util.Set<String> changedNativeKeys = new java.util.HashSet<>();
    private boolean wipeRequested;
    // Atomic "seen this fingerprint within the window?" check; null in test runs, where nothing may be recorded.
    private final java.util.function.BiPredicate<String, Integer> duplicateCheck;
    // Warnings and log messages one message may record; a loop must not fill the heap with them.
    private static final int MAX_MESSAGES = 500;

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
        this(dataPrepContext, c8yAgent, tenant, testing, mapping, processingContext, flowStateStore, initialState,
                null);
    }

    /**
     * Full constructor with the de-duplication check behind {@link #isDuplicate(String, int)}.
     *
     * @param duplicateCheck takes a fingerprint and a window in seconds, records the fingerprint, and returns true
     *                       if it was already recorded inside the window; null makes {@code isDuplicate} always
     *                       return false (test runs)
     */
    public JavaExtensionContextImpl(
            DataPrepContext dataPrepContext,
            C8YAgent c8yAgent,
            String tenant,
            Boolean testing,
            Mapping mapping,
            ProcessingContext<?> processingContext,
            FlowStateStore flowStateStore,
            Map<String, Object> initialState,
            java.util.function.BiPredicate<String, Integer> duplicateCheck) {
        this.duplicateCheck = duplicateCheck;
        this.dataPrepContext = dataPrepContext;
        this.c8yAgent = c8yAgent;
        this.tenant = tenant != null ? tenant : "unknown";
        this.testing = testing != null ? testing : false;
        this.mapping = mapping;
        this.processingContext = processingContext;
        this.flowStateStore = flowStateStore;
        this.mappingIdentifier = mapping != null ? mapping.getIdentifier() : null;
        this.nativeState = new HashMap<>();
        if (initialState != null) {
            // Nested collections are copied: the stored objects are shared by messages running in parallel.
            initialState.forEach((key, value) -> this.nativeState.put(key, deepCopy(value)));
        }
    }

    private static Object deepCopy(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new HashMap<>();
            map.forEach((k, v) -> copy.put(k, deepCopy(v)));
            return copy;
        } else if (value instanceof java.util.List<?> list) {
            java.util.List<Object> copy = new java.util.ArrayList<>(list.size());
            list.forEach(element -> copy.add(deepCopy(element)));
            return copy;
        } else if (value instanceof java.util.Set<?> set) {
            java.util.Set<Object> copy = new java.util.HashSet<>();
            set.forEach(element -> copy.add(deepCopy(element)));
            return copy;
        }
        return value;
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
            append(processingContext.getWarnings(), warning, "warnings");
        }
        // The warning reaches the user through the processing result; logging each one at WARN flooded the log
        log.debug("{} - Extension warning: {}", tenant, warning);
    }

    @Override
    public void addLog(String logMessage) {
        if (processingContext != null) {
            append(processingContext.getLogs(), logMessage, "log messages");
        }
        log.debug("{} - Extension log: {}", tenant, logMessage);
    }

    private static void append(java.util.List<String> target, String message, String what) {
        int size = target.size();
        if (size < MAX_MESSAGES) {
            target.add(message);
        } else if (size == MAX_MESSAGES) {
            target.add("Further " + what + " of this message are not recorded (limit " + MAX_MESSAGES + ")");
        }
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
        // Direct cache lookup without exposing C8YAgent to extensions. A copy: the cache hands out the map every
        // message shares, and an extension that changed it would change it for all of them.
        Object copy = deepCopy(c8yAgent.getMOFromInventoryCacheByExternalId(tenant, externalId, testing));
        return copy instanceof Map<?, ?> ? castMap(copy) : null;
    }

    @Override
    public java.util.Map<String, Object> getManagedObjectAsMap(String c8ySourceId) {
        if (c8yAgent == null) {
            log.warn("{} - getManagedObjectAsMap() called but C8YAgent not available", tenant);
            return null;
        }
        if (c8ySourceId == null) {
            return null;
        }
        Object copy = deepCopy(c8yAgent.getMOFromInventoryCache(tenant, c8ySourceId, testing));
        return copy instanceof Map<?, ?> ? castMap(copy) : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object map) {
        return (Map<String, Object>) map;
    }

    @Override
    public String getClientId() {
        // An extension has no DataPrepContext; the processing context holds the client id of the connector message
        if (processingContext != null) {
            return processingContext.getClientId();
        }
        return dataPrepContext != null ? dataPrepContext.getClientId() : null;
    }

    // ==================== DataPrepContext (script API) ====================
    //
    // A Java extension has no GraalVM context, so the Value-based methods of the script API cannot work. They
    // used to log a warning and do nothing, which hid the mistake (a context.setState call that never stored).
    // They now fail with the method to use instead. A DataPrepContext, when one is given (tests only), is used.

    private static UnsupportedOperationException notForExtensions(String method, String instead) {
        return new UnsupportedOperationException(method + " is a Smart Function method and is not available in Java "
                + "extensions; " + instead);
    }

    @Override
    public void setState(String key, Value value) {
        if (dataPrepContext == null) {
            throw notForExtensions("setState", "use setNativeState(key, value)");
        }
        dataPrepContext.setState(key, value);
    }

    @Override
    public Value getState(String key) {
        if (dataPrepContext == null) {
            throw notForExtensions("getState", "use getNativeState(key)");
        }
        return dataPrepContext.getState(key);
    }

    @Override
    public Value getState(String key, Object defaultValue) {
        if (dataPrepContext == null) {
            throw notForExtensions("getState", "use getNativeState(key)");
        }
        return dataPrepContext.getState(key, defaultValue);
    }

    @Override
    public Value getStateAll() {
        if (dataPrepContext == null) {
            throw notForExtensions("getStateAll", "use getNativeStateAll()");
        }
        return dataPrepContext.getStateAll();
    }

    @Override
    public Value getStateKeySet() {
        if (dataPrepContext == null) {
            throw notForExtensions("getStateKeySet", "use getNativeStateAll().keySet()");
        }
        return dataPrepContext.getStateKeySet();
    }

    @Override
    public Value getDTMAsset(String assetId) {
        if (dataPrepContext == null) {
            throw notForExtensions("getDTMAsset", "DTM asset lookup is not implemented");
        }
        return dataPrepContext.getDTMAsset(assetId);
    }

    @Override
    public Value getManagedObject(String c8ySourceId) {
        if (dataPrepContext == null) {
            throw notForExtensions("getManagedObject", "use getManagedObjectAsMap(String)");
        }
        return dataPrepContext.getManagedObject(c8ySourceId);
    }

    @Override
    public Value getManagedObjectByExternalId(ExternalId externalId) {
        if (dataPrepContext == null) {
            throw notForExtensions("getManagedObjectByExternalId", "use getManagedObjectAsMap(ExternalId)");
        }
        // a host ExternalId is understood by the Value overload
        return dataPrepContext.getManagedObjectByExternalId(Value.asValue(externalId));
    }

    @Override
    public Value getManagedObjectByExternalId(Value value) {
        if (dataPrepContext == null) {
            throw notForExtensions("getManagedObjectByExternalId", "use getManagedObjectAsMap(ExternalId)");
        }
        return dataPrepContext.getManagedObjectByExternalId(value);
    }

    @Override
    public boolean isDuplicate(Value key, Value windowSeconds) {
        if (dataPrepContext == null) {
            throw notForExtensions("isDuplicate(Value, Value)", "use isDuplicate(String, int)");
        }
        return dataPrepContext.isDuplicate(key, windowSeconds);
    }

    @Override
    public Value incrementState(String key, Value by) {
        if (dataPrepContext == null) {
            throw notForExtensions("incrementState", "use incrementNativeState(key, by)");
        }
        return dataPrepContext.incrementState(key, by);
    }

    @Override
    public Value collect(String groupKey, String itemKey, Value value, Value expectedCountOrOptions) {
        if (dataPrepContext == null) {
            throw notForExtensions("collect", "collecting items into groups is not available to extensions");
        }
        return dataPrepContext.collect(groupKey, itemKey, value, expectedCountOrOptions);
    }

    @Override
    public Value take(String groupKey) {
        if (dataPrepContext == null) {
            throw notForExtensions("take", "collecting items into groups is not available to extensions");
        }
        return dataPrepContext.take(groupKey);
    }

    @Override
    public Value openGroups(String prefix) {
        if (dataPrepContext == null) {
            throw notForExtensions("openGroups", "collecting items into groups is not available to extensions");
        }
        return dataPrepContext.openGroups(prefix);
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

    /**
     * Removes every state entry held for this mapping, effective when {@link #persistNativeState()}
     * runs at the end of the message.
     */
    @Override
    public void clearState() {
        if (dataPrepContext != null) {
            dataPrepContext.clearState();
        }
        nativeState.clear();
        changedNativeKeys.clear();
        wipeRequested = true;
    }

    /**
     * Writes back what this invocation changed, so a message that only reads state cannot revert what
     * concurrent messages wrote. Called by the extensible processors after {@code onMessage} returned
     * normally. It is not called when the extension throws: the message fails and is redelivered, and
     * state written by the failed attempt would make the retry look like a duplicate. A no-op for test
     * runs, which have no store.
     */
    public void persistNativeState() {
        if (flowStateStore != null && mappingIdentifier != null) {
            Map<String, Object> upserts = new HashMap<>();
            java.util.Set<String> removals = new java.util.HashSet<>();
            for (String key : changedNativeKeys) {
                Object value = nativeState.get(key);
                if (value == null) {
                    removals.add(key);
                } else {
                    upserts.put(key, value);
                }
            }
            flowStateStore.commitChanges(tenant, mappingIdentifier, wipeRequested, upserts, removals);
        }
        changedNativeKeys.clear();
        wipeRequested = false;
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
        changedNativeKeys.add(key);
        log.debug("{} - Native flow state set: {}={}", tenant, key, value);
    }

    @Override
    public long incrementNativeState(String key, long by) {
        if (key == null) {
            throw new IllegalArgumentException("incrementNativeState: the key is required");
        }
        Number result;
        if (flowStateStore != null && mappingIdentifier != null && !changedNativeKeys.contains(key)) {
            if (wipeRequested) {
                // clearState() came first, and the increment must start from nothing
                flowStateStore.commitChanges(tenant, mappingIdentifier, true, Map.of(), java.util.Set.of());
                wipeRequested = false;
            }
            result = flowStateStore.increment(tenant, mappingIdentifier, key, by);
            nativeState.put(key, result); // visible to this message, already stored: not a pending change
        } else {
            // a test run, or the function changed this key before: apply to the pending value
            Object current = nativeState.get(key);
            if (current != null && !(current instanceof Number)) {
                throw new IllegalArgumentException("the state key '" + key + "' does not hold a number");
            }
            result = FlowStateStore.add(current == null ? 0 : (Number) current, by);
            nativeState.put(key, result);
            changedNativeKeys.add(key);
        }
        return result.longValue();
    }

    @Override
    public boolean isDuplicate(String key, int windowSeconds) {
        if (duplicateCheck == null) {
            return false;
        }
        if (key == null) {
            addWarning("isDuplicate: the key has no value, the message is processed without a check");
            return false;
        }
        if (!dynamic.mapper.processor.inbound.MessageDeduplicationService.isValidWindow(windowSeconds)) {
            addWarning("isDuplicate: the window must be a number of seconds from "
                    + dynamic.mapper.processor.inbound.MessageDeduplicationService.MIN_WINDOW_SECONDS + " to "
                    + dynamic.mapper.processor.inbound.MessageDeduplicationService.MAX_WINDOW_SECONDS
                    + ", the message is processed without a check");
            return false;
        }
        String keyText = dynamic.mapper.processor.inbound.MessageDeduplicationService.keyText(key);
        return duplicateCheck.test(
                dynamic.mapper.processor.inbound.MessageDeduplicationService.fingerprint(keyText), windowSeconds);
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
