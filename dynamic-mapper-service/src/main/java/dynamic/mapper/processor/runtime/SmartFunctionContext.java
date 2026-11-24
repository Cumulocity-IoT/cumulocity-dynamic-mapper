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
package dynamic.mapper.processor.runtime;

import dynamic.mapper.processor.model.CollectOptions;
import dynamic.mapper.processor.model.ExternalId;

import dynamic.mapper.processor.model.DataPrepContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

import dynamic.mapper.core.InventoryEnrichmentClient;
import dynamic.mapper.processor.util.JavaScriptInteropHelper;
import dynamic.mapper.processor.flow.FlowStateStore;
import dynamic.mapper.processor.inbound.MessageDeduplicationService;
import lombok.extern.slf4j.Slf4j;

/**
 * Simple implementation of DataPrepContext for JavaScript execution.
 *
 * <p>When constructed with a {@link FlowStateStore} and a mapping identifier,
 * state written via {@code setState} is automatically persisted across message
 * invocations. At the end of each message {@code ProcessingContext.close()} calls
 * {@link #persistState()}, which writes back only the keys this invocation changed.</p>
 */
@Slf4j
public class SmartFunctionContext implements DataPrepContext {

    private static final int MAX_EXPECTED_COUNT = CollectOptions.MAX_EXPECTED_COUNT;
    private static final int MAX_AGE_SECONDS = CollectOptions.MAX_AGE_SECONDS;
    // Warnings and logs one message may collect; a loop must not be able to fill the heap with them.
    private static final int MAX_MESSAGES = 500;

    private final Map<String, Object> state;
    // Kept apart from the state: a function sees only its own keys, and nothing here reaches the store.
    private final List<String> warnings = new ArrayList<>();
    private final List<String> logs = new ArrayList<>();
    private final Context graalContext;
    private final String tenant;
    private final InventoryEnrichmentClient inventoryEnrichmentClient;
    private Boolean testing;
    private String clientId;
    private final FlowStateStore stateStore;
    private final String mappingIdentifier;
    private Map<String, Object> config;
    // Built on first getConfig() and reused: the config is fixed for the invocation, but a Smart Function
    // that calls context.getConfig() several times (or in a loop) rebuilt the whole proxy tree each time.
    private Value configValue;
    // Keys this invocation wrote, and whether it called clearState(): only these reach the store.
    private final java.util.Set<String> changedKeys = new java.util.HashSet<>();
    private boolean wipeRequested;
    // Atomic "seen this fingerprint within the window?" check; null in test runs, where nothing may be recorded.
    private final java.util.function.BiPredicate<String, Integer> duplicateCheck;

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
        this(graalContext, tenant, inventoryEnrichmentClient, testing, stateStore, mappingIdentifier, initialState,
                null);
    }

    /**
     * Creates a context with persistent state and the de-duplication check behind {@link #isDuplicate}.
     *
     * @param duplicateCheck takes a fingerprint and a window in seconds, records the fingerprint, and returns
     *                       true if it was already recorded inside the window; null makes
     *                       {@code isDuplicate} always return false (test runs)
     */
    public SmartFunctionContext(Context graalContext, String tenant, InventoryEnrichmentClient inventoryEnrichmentClient,
            Boolean testing, FlowStateStore stateStore, String mappingIdentifier, Map<String, Object> initialState,
            java.util.function.BiPredicate<String, Integer> duplicateCheck) {
        this.duplicateCheck = duplicateCheck;
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

    /**
     * Set the read-only mapping configuration for this invocation.
     * Called by the enrichment processor before the Smart Function is invoked.
     *
     * @param config Map containing mapping metadata (mappingId, mappingName, tenant, topic, etc.)
     */
    public void setConfig(Map<String, Object> config) {
        this.config = config;
        this.configValue = null;
    }

    /**
     * Returns the mapping configuration as a GraalVM Value so JavaScript code can access it.
     * The returned object is read-only by convention; mutations are not persisted.
     */
    @Override
    public Value getConfig() {
        if (graalContext == null || config == null) {
            return null;
        }
        if (configValue == null) {
            configValue = graalContext.asValue(JavaScriptInteropHelper.toJsInteropLazy(config));
        }
        return configValue;
    }

    @Override
    public String getExternalId() {
        if (config == null) {
            return null;
        }
        Object val = config.get("externalId");
        return val != null ? val.toString() : null;
    }

    @Override
    public void setState(String key, Value value) {
        if (key == null) {
            log.warn("{} - Cannot set state with null key", tenant);
            return;
        }

        // Convert Value to Java object for safe storage
        Object javaValue = convertValueToJavaObject(value);
        if (javaValue != null && stateStore != null && mappingIdentifier != null) {
            String reason = stateStore.stateLimitReason(tenant, mappingIdentifier, key, javaValue);
            if (reason != null) {
                addWarning("setState: " + reason + ", the value of '" + key + "' was not stored");
                return;
            }
        }
        state.put(key, javaValue);
        changedKeys.add(key);

        log.debug("{} - Flow state set: {}={}", tenant, key, javaValue);
    }

    /**
     * Reports whether the same key was seen for this mapping less than {@code windowSeconds} ago, and records it
     * if not (issue #692). Called by a Smart Function as {@code context.isDuplicate(key, 60)}.
     *
     * <p>Unlike {@code getState}/{@code setState} the check is atomic: of two copies of a message running at the
     * same time, exactly one gets {@code false}. Keys expire with the window and a mapping holds a bounded
     * number of them. It shares its store with the {@code $isDuplicate} filter function.</p>
     *
     * <p>Fails open: it returns false, and adds a warning, when the key has no value or the window is not a
     * number from 1 to 3600. It always returns false in a test run, which records nothing.</p>
     */
    @Override
    public boolean isDuplicate(Value key, Value windowSeconds) {
        if (duplicateCheck == null) {
            return false;
        }
        if (key == null || key.isNull()) {
            addWarning("isDuplicate: the key has no value, the message is processed without a check");
            return false;
        }
        int window = windowSeconds != null && windowSeconds.fitsInInt()
                && MessageDeduplicationService.isValidWindow(windowSeconds.asInt())
                        ? windowSeconds.asInt()
                        : 0;
        if (window == 0) {
            addWarning("isDuplicate: the window must be a number of seconds from "
                    + MessageDeduplicationService.MIN_WINDOW_SECONDS + " to "
                    + MessageDeduplicationService.MAX_WINDOW_SECONDS + ", the message is processed without a check");
            return false;
        }
        String keyText = MessageDeduplicationService.keyText(convertValueToJavaObject(key));
        return duplicateCheck.test(MessageDeduplicationService.fingerprint(keyText), window);
    }

    /**
     * Atomic N:1 collection; see {@link FlowStateStore#collect}. Called by a Smart Function as
     * {@code context.collect(groupKey, itemKey, value, expectedCount)} or with an options object
     * {@code {count, keys, last, maxAgeSeconds}}. Fails open: it returns null, and adds a warning, when an argument
     * is missing or invalid, with the value not collected. A test run has no store and returns null, so nothing is
     * buffered.
     */
    @Override
    public Value collect(String groupKey, String itemKey, Value value, Value expectedCountOrOptions) {
        if (graalContext != null && (stateStore == null || mappingIdentifier == null)) {
            // A test run keeps no groups: without a word the function looks broken in the test UI.
            addWarning("collect: not evaluated in a test run, nothing is collected and the result is always null");
            return null;
        }
        if (stateStore == null || mappingIdentifier == null || graalContext == null) {
            return null;
        }
        if (groupKey == null || itemKey == null || value == null || value.isNull()) {
            addWarning("collect: groupKey, itemKey and value are required, the message was not collected");
            return null;
        }
        CollectOptions options = parseCollectOptions(expectedCountOrOptions);
        if (options == null) {
            return null;
        }
        Map<String, Object> group;
        try {
            group = stateStore.collect(tenant, mappingIdentifier, groupKey, itemKey,
                    convertValueToJavaObject(value), options);
        } catch (FlowStateStore.StateLimitExceededException e) {
            addWarning("collect: " + e.getMessage() + ", the message was not collected");
            return null;
        }
        return group == null ? null : graalContext.asValue(JavaScriptInteropHelper.toJsInterop(group));
    }

    private CollectOptions parseCollectOptions(Value arg) {
        String bad = "collect: the fourth argument must be a whole number from 1 to " + MAX_EXPECTED_COUNT
                + " or an object with count, keys, last or maxAgeSeconds, the message was not collected";
        if (arg == null || arg.isNull()) {
            addWarning(bad);
            return null;
        }
        if (arg.isNumber()) {
            if (!arg.fitsInInt() || arg.asInt() < 1 || arg.asInt() > MAX_EXPECTED_COUNT) {
                addWarning(bad);
                return null;
            }
            return CollectOptions.count(arg.asInt());
        }
        if (!arg.hasMembers() || arg.hasArrayElements()) {
            addWarning(bad);
            return null;
        }
        Integer count = null;
        java.util.Set<String> keys = null;
        boolean last = false;
        Integer maxAge = null;
        // Only the types are checked here; the ranges and the combination are checked by CollectOptions.problem(),
        // the same check Java extensions get.
        if (arg.hasMember("count") && !arg.getMember("count").isNull()) {
            Value c = arg.getMember("count");
            if (!c.fitsInInt()) {
                addWarning("collect: count must be a whole number from 1 to " + MAX_EXPECTED_COUNT
                        + ", the message was not collected");
                return null;
            }
            count = c.asInt();
        }
        if (arg.hasMember("keys") && !arg.getMember("keys").isNull()) {
            Value k = arg.getMember("keys");
            if (!k.hasArrayElements()) {
                addWarning("collect: keys must be a list of 1 to " + MAX_EXPECTED_COUNT
                        + " item keys, the message was not collected");
                return null;
            }
            keys = new java.util.HashSet<>();
            for (long i = 0; i < k.getArraySize(); i++) {
                Value e = k.getArrayElement(i);
                if (!e.isString()) {
                    addWarning("collect: keys must be strings, the message was not collected");
                    return null;
                }
                keys.add(e.asString());
            }
        }
        if (arg.hasMember("last") && !arg.getMember("last").isNull()) {
            Value l = arg.getMember("last");
            if (!l.isBoolean()) {
                addWarning("collect: last must be true or false, the message was not collected");
                return null;
            }
            last = l.asBoolean();
        }
        if (arg.hasMember("maxAgeSeconds") && !arg.getMember("maxAgeSeconds").isNull()) {
            Value m = arg.getMember("maxAgeSeconds");
            if (!m.fitsInInt()) {
                addWarning("collect: maxAgeSeconds must be a whole number from 1 to " + MAX_AGE_SECONDS
                        + ", the message was not collected");
                return null;
            }
            maxAge = m.asInt();
        }
        CollectOptions options = new CollectOptions(count, keys, last, maxAge);
        String problem = options.problem();
        if (problem != null) {
            addWarning("collect: " + problem + ", the message was not collected");
            return null;
        }
        return options;
    }

    /** Removes a collected group and returns it as it is; see {@link FlowStateStore#take}. */
    @Override
    public Value take(String groupKey) {
        if (stateStore == null || mappingIdentifier == null || graalContext == null || groupKey == null) {
            return null;
        }
        Map<String, Object> group = stateStore.take(tenant, mappingIdentifier, groupKey);
        return group == null ? null : graalContext.asValue(JavaScriptInteropHelper.toJsInterop(group));
    }

    /** Lists the keys of the open collected groups starting with the prefix; see {@link FlowStateStore#openGroups}. */
    @Override
    public Value openGroups(String prefix) {
        if (graalContext == null) {
            return null;
        }
        java.util.List<String> keys = stateStore == null || mappingIdentifier == null
                ? java.util.List.of()
                : stateStore.openGroups(tenant, mappingIdentifier, prefix);
        return graalContext.asValue(JavaScriptInteropHelper.toJsInterop(new java.util.ArrayList<>(keys)));
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

        // Convert back to GraalJS Value. Lazy: a stored value is replaced by setState, never changed in
        // place, so the script cannot see it change underneath the view.
        return graalContext.asValue(JavaScriptInteropHelper.toJsInteropLazy(javaValue));
    }

    /**
     * 2-arg overload called by V2 Smart Functions: {@code context.getState('key', defaultValue)}.
     * GraalVM selects this method when JavaScript passes two arguments to {@code getState}.
     * Uses {@code Object} (not {@code Value}) so GraalVM reliably matches any JS primitive.
     *
     * A key stored as null counts as absent and returns the default: the store cannot hold null, so the key
     * is removed when the message ends.
     */
    @Override
    public Value getState(String key, Object defaultValue) {
        if (key == null || graalContext == null) {
            return graalContext != null ? graalContext.asValue(defaultValue) : null;
        }
        Object javaValue = state.get(key);
        if (javaValue == null) {
            // A value stored as null is removed when the message ends, so it is "absent" from the next message
            // on; treat it the same way now, or the answer would depend on which message asks.
            return graalContext.asValue(defaultValue);
        }
        return graalContext.asValue(JavaScriptInteropHelper.toJsInteropLazy(javaValue));
    }

    @Override
    public Value getDTMAsset(String assetId) {
        if (graalContext == null) {
            return null;
        }

        // Not implemented: answer as for an asset that does not exist, as the API contract says (null).
        log.debug("{} - DTM asset lookup requested for: {}", tenant, assetId);
        addWarning("getDTMAsset: DTM asset lookup is not available, the result is always null");
        return graalContext.asValue(null);
    }

    /** The text of a string or number (an external id is often a number in the payload); null for anything else. */
    private static String scalarText(Value value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isString()) {
            return value.asString();
        }
        if (value.isNumber()) {
            return value.fitsInLong() ? String.valueOf(value.asLong()) : String.valueOf(value.asDouble());
        }
        return null;
    }

    /**
     * Convert GraalJS Value to Java object for safe storage
     */
    private Object convertValueToJavaObject(Value value) {
        return convertValueToJavaObject(value, new java.util.HashSet<>());
    }

    /**
     * @param path the arrays and objects being converted, to detect a reference to itself
     */
    private Object convertValueToJavaObject(Value value, java.util.Set<Object> path) {
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
        } else if (value.isInstant()) {
            // A JS Date: stored as its ISO-8601 text, which is what JSON.stringify makes of it. It used to
            // match hasMembers() and silently become {}.
            return value.asInstant().toString();
        } else if (value.canExecute()) {
            addWarning("A function cannot be stored in state or collected, it was replaced by null");
            return null;
        } else if (value.hasArrayElements()) {
            enter(value, path);
            try {
                java.util.List<Object> list = new java.util.ArrayList<>();
                long size = value.getArraySize();
                for (int i = 0; i < size; i++) {
                    list.add(convertValueToJavaObject(value.getArrayElement(i), path));
                }
                return list;
            } finally {
                path.remove(value);
            }
        } else if (value.hasMembers()) {
            enter(value, path);
            try {
                // Insertion order, as the script wrote it: a HashMap reordered the keys.
                Map<String, Object> map = new java.util.LinkedHashMap<>();
                for (String key : value.getMemberKeys()) {
                    map.put(key, convertValueToJavaObject(value.getMember(key), path));
                }
                return map;
            } finally {
                path.remove(value);
            }
        } else {
            // Fallback to string representation
            return value.toString();
        }
    }

    private static void enter(Value value, java.util.Set<Object> path) {
        // Value.equals compares the underlying guest object, not the Java wrapper (each read makes a new one)
        if (!path.add(value)) {
            throw new IllegalArgumentException("The value refers to itself and cannot be stored or collected");
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
        return graalContext.asValue(JavaScriptInteropHelper.toJsInterop(javaValue));
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
        return graalContext.asValue(JavaScriptInteropHelper.toJsInterop(javaValue));
    }

    @Override
    public Value getManagedObject(String c8ySourceId) {
        if (graalContext == null) {
            return null;
        }
        if (c8ySourceId == null || inventoryEnrichmentClient == null) {
            addWarning(c8ySourceId == null ? "getManagedObject: the device id has no value"
                    : "getManagedObject: inventory lookup is not available");
            return graalContext.asValue(null);
        }
        Object javaValue = inventoryEnrichmentClient.getMOFromInventoryCache(tenant, c8ySourceId, testing);
        if (javaValue == null) {
            addWarning(String.format("Device not found in inventory cache: %s", c8ySourceId));
        }
        return graalContext.asValue(JavaScriptInteropHelper.toJsInterop(javaValue));
    }

    @Override
    public Value getManagedObjectByExternalId(Value externalIdValue) {
        if (graalContext == null) {
            return null;
        }
        if (inventoryEnrichmentClient == null) {
            addWarning("getManagedObjectByExternalId: inventory lookup is not available");
            return graalContext.asValue(null);
        }
        if (externalIdValue == null || externalIdValue.isNull()) {
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
                String externalId = scalarText(externalIdValue.getMember("externalId"));
                String type = scalarText(externalIdValue.getMember("type"));
                if (externalId == null || type == null) {
                    addWarning("getManagedObjectByExternalId: both externalId and type are required, for example "
                            + "{externalId: 'abc', type: 'c8y_Serial'}");
                    return graalContext.asValue(null);
                }
                extId = new ExternalId(externalId, type);
            } else {
                addWarning("Invalid externalId parameter format");
                return graalContext.asValue(null);
            }

            Object javaValue = inventoryEnrichmentClient.getMOFromInventoryCacheByExternalId(tenant, extId, testing);
            if (javaValue == null) {
                addWarning(String.format("ExternalId not found in inventory cache: %s", extId));
            }
            return graalContext.asValue(JavaScriptInteropHelper.toJsInterop(javaValue));

        } catch (Exception e) {
            addWarning("Failed to process externalId: " + e.getMessage());
            log.error("{} - Error processing externalId", tenant, e);
            return graalContext.asValue(null);
        }
    }

    @Override
    public void addWarning(String warning) {
        append(warnings, warning, "warnings");
    }

    @Override
    public void addLogMessage(String message) {
        append(logs, message, "log messages");
    }

    private static void append(List<String> target, String message, String what) {
        if (target.size() < MAX_MESSAGES) {
            target.add(message);
        } else if (target.size() == MAX_MESSAGES) {
            target.add("Further " + what + " of this message are not recorded (limit " + MAX_MESSAGES + ")");
        }
    }

    /** The warnings this message collected; read by the processors, not reachable from a script. */
    public List<String> collectedWarnings() {
        return List.copyOf(warnings);
    }

    /** The log messages this message collected; read by the processors, not reachable from a script. */
    public List<String> collectedLogs() {
        return List.copyOf(logs);
    }

    /**
     * Adds a number to a state key in one atomic step, so concurrent messages never lose an increment (see
     * {@link FlowStateStore#increment}). Called by a Smart Function as {@code context.incrementState(key)} or
     * {@code context.incrementState(key, by)}; returns the new value, or null with a warning if {@code by} is not a
     * finite number, the key holds something else, or the mapping holds the maximum number of keys.
     *
     * <p>The new value is visible to this message at once and is not written again when the message ends. If the
     * function changed the same key with {@code setState} before, the increment is applied to that pending value
     * instead, and is then written like any {@code setState}. A test run has no store: it counts on its own
     * throw-away state.</p>
     */
    @Override
    public Value incrementState(String key, Value by) {
        if (graalContext == null) {
            return null;
        }
        if (key == null) {
            addWarning("incrementState: the key has no value");
            return null;
        }
        Number amount;
        if (by == null || by.isNull()) {
            amount = 1;
        } else if (by.isNumber() && by.fitsInInt()) {
            amount = by.asInt();
        } else if (by.isNumber() && by.fitsInLong()) {
            amount = by.asLong();
        } else if (by.isNumber() && Double.isFinite(by.asDouble())) {
            amount = by.asDouble();
        } else {
            addWarning("incrementState: the amount must be a finite number, the value of '" + key
                    + "' was not changed");
            return null;
        }
        try {
            Number result;
            if (stateStore != null && mappingIdentifier != null && !changedKeys.contains(key)) {
                if (wipeRequested) {
                    // clearState() came first, and the increment must start from nothing
                    stateStore.commitChanges(tenant, mappingIdentifier, true, Map.of(), java.util.Set.of());
                    wipeRequested = false;
                }
                result = stateStore.increment(tenant, mappingIdentifier, key, amount);
                state.put(key, result);
            } else {
                Object current = state.get(key);
                if (current != null && !(current instanceof Number)) {
                    throw new IllegalArgumentException("the state key '" + key + "' does not hold a number");
                }
                result = FlowStateStore.add(current == null ? 0 : (Number) current, amount);
                state.put(key, result);
                changedKeys.add(key);
            }
            return graalContext.asValue(result);
        } catch (IllegalArgumentException | FlowStateStore.StateLimitExceededException e) {
            addWarning("incrementState: " + e.getMessage() + ", the value of '" + key + "' was not changed");
            return null;
        }
    }

    /**
     * Called by Smart Function code: removes every state entry held for this mapping, effective when
     * the message ends. Collected logs and warnings, and the groups of {@code collect}, are kept.
     */
    @Override
    public void clearState() {
        state.clear();
        changedKeys.clear();
        wipeRequested = true;
    }

    /**
     * Called by {@code ProcessingContext.close()} at the end of the message, not by user code
     * (package-private, so it is not exposed to JavaScript). Writes back only what this invocation
     * changed, so a message that merely read state cannot revert what concurrent messages wrote.
     */
    void persistState() {
        if (stateStore != null && mappingIdentifier != null) {
            Map<String, Object> upserts = new HashMap<>();
            java.util.Set<String> removals = new java.util.HashSet<>();
            for (String key : changedKeys) {
                Object value = state.get(key);
                if (value == null) {
                    removals.add(key);
                } else {
                    upserts.put(key, value);
                }
            }
            stateStore.commitChanges(tenant, mappingIdentifier, wipeRequested, upserts, removals);
        }
        changedKeys.clear();
        wipeRequested = false;
        state.clear();
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