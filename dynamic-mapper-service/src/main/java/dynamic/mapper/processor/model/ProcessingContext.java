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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.model.API;
import dynamic.mapper.model.BinaryInfo;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.Qos;
import dynamic.mapper.processor.ProcessingException;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import com.cumulocity.sdk.client.ProcessingMode;
import com.fasterxml.jackson.annotation.JsonIgnore;

@Getter
@Setter
@Builder
@Slf4j
/*
 * The class <code>ProcessingContext</code> collects all relevant information:
 * <code>mapping</code>, <code>topic</code>, <code>payload</code>,
 * <code>requests</code>, <code>error</code>, <code>processingType</code>,
 * <code>cardinality</code>, <code>needsRepair</code>
 * when a <code>mapping</code> is applied to an inbound <code>payload</code>
 */
public class ProcessingContext<O> implements AutoCloseable {

    public static final String CREATE_NON_EXISTING_DEVICE = "createNonExistingDevice";
    public static final String SOURCE_ID = "source.id";
    public static final String DEVICE_NAME = "deviceName";
    public static final String DEVICE_TYPE = "deviceType";
    public static final String DEVICE_FRAGMENTS = "deviceFragments";
    public static final String DEVICE_GROUPS = "deviceGroups";
    public static final String EVENT_WITH_ATTACHMENT = "eventWithAttachment";
    public static final String PROCESSING_MODE = "processingMode";
    public static final String ATTACHMENT_DATA = "attachmentData";
    public static final String ATTACHMENT_TYPE = "attachmentType";
    public static final String ATTACHMENT_NAME = "attachmentName";
    public static final String RETAIN = "retain";
    public static final String DEBUG = "debug";
    public static final String GENERIC_DEVICE_IDENTIFIER = "genericDeviceIdentifier";

    private Mapping mapping;

    private String topic;

    private String clientId;

    private String connectorIdentifier;

    private API api;

    private Qos qos;

    private String resolvedPublishTopic;

    /**
     * contains the deserialized payload
     */
    private O payload;

    private Object rawPayload;

    // Thread-safe: the parallel-request-processing route (direct:processRequestsInParallel)
    // runs SendInboundProcessor concurrently across virtual threads against this SAME
    // ProcessingContext, and each leg may call addError() on failure.
    @Builder.Default
    private List<DynamicMapperRequest> requests = new CopyOnWriteArrayList<DynamicMapperRequest>();

    @Builder.Default
    private List<Exception> errors = new CopyOnWriteArrayList<Exception>();

    @Builder.Default
    private List<String> warnings = new CopyOnWriteArrayList<>();

    @Builder.Default
    private List<String> logs = new CopyOnWriteArrayList<>();

    @Builder.Default
    private ProcessingType processingType = ProcessingType.UNDEFINED;

    private MappingType mappingType;

    // <pathTarget, substituteValues>
    // ConcurrentSkipListMap: thread-safe NavigableMap with weakly-consistent iterators,
    // keeping the sorted-keys property (so "_CONTEXT_DATA_.deviceName" is available when
    // creating an implicit device) while being safe under concurrent access.
    @Builder.Default
    private Map<String, List<SubstituteValue>> processingCache = new ConcurrentSkipListMap<String, List<SubstituteValue>>();

    @Builder.Default
    private boolean sendPayload = false;

    @Builder.Default
    private boolean testing = false;

    @Builder.Default
    private boolean needsRepair = false;

    @Builder.Default
    private boolean retain = false;

    private String tenant;

    private ServiceConfiguration serviceConfiguration;

    @Builder.Default
    private boolean ignoreFurtherProcessing = false;

    private String key;

    private String sourceId;

    private String externalId;

    private Engine graalEngine;

    private Context graalContext;

    /**
     * Pooled GraalVM context borrowed from {@code GraalVMContextService}'s per-mapping pool.
     * When non-null, {@link #close()} skips {@code graalContext.close()} and instead
     * lets {@link #engineReleaseAction} (which calls {@code GraalVMContextService.returnContext})
     * handle both the pool return and the engine's in-flight counter decrement.
     */
    private PooledGraalContext pooledGraalContext;

    /**
     * Callback invoked after the GraalVM {@link Context} is closed to notify
     * {@code GraalVMContextService} that this in-flight context slot has been released.
     * Set by {@code AbstractEnrichmentProcessor} so the service can close a retired
     * {@link Engine} once all its contexts have drained.
     */
    private Runnable engineReleaseAction;

    private String sharedCode;

    private Source sharedSource;

    private String systemCode;

    private Source systemSource;

    /**
     * Reference to the ProcessingResultWrapper for this processing task.
     * Allows in-flight processors to check if processing has been cancelled.
     * Set by CamelDispatcherInbound to enable cancellation checks in JavaScript code.
     * Can be null if the wrapper is not available.
     */
    @SuppressWarnings("rawtypes")
    private ProcessingResultWrapper processingResultWrapper;

    private Source mappingSource;

    private Value sourceValue;

    @Builder.Default
    private Set<String> alarms = ConcurrentHashMap.newKeySet();

    @Builder.Default
    private ProcessingMode processingMode = ProcessingMode.PERSISTENT;

    private String deviceName;

    private String deviceType;

    private Map<String, Object> deviceFragments;

    private List<String> deviceGroups;

    private Object flowResult;

    private Object extensionResult;

    private DataPrepContext flowContext;

    private Map<String, Object> flowState;

    @Builder.Default
    private BinaryInfo binaryInfo = new BinaryInfo();

    public boolean hasError() {
        return !errors.isEmpty();
    }

    /**
     * Adds a request to the processing context.
     *
     * NOTE: This method is NOT thread-safe due to ArrayList not being thread-safe.
     * If multiple threads call this method concurrently on the same ProcessingContext,
     * race conditions may occur (lost updates, ArrayIndexOutOfBoundsException, corrupted state).
     * Consider synchronizing this method or using a thread-safe list if concurrent access is needed.
     *
     * @param c8yRequest the request to add
     * @return the index of the added request in the list
     */
    public int addRequest(DynamicMapperRequest c8yRequest) {
        requests.add(c8yRequest);
        return requests.size() - 1;
    }

    /**
     * Get the current (last) request from the requests list.
     * This method is safe to call even when requests is empty.
     * 
     * @return the last request or null if no requests exist
     */
    @JsonIgnore
    public DynamicMapperRequest getCurrentRequest() {
        if (requests == null || requests.isEmpty()) {
            return null;
        }
        return requests.get(requests.size() - 1);
    }

    /**
     * Adds an error to the processing context.
     *
     * NOTE: This method is NOT thread-safe due to ArrayList not being thread-safe.
     * If multiple threads call this method concurrently, consider synchronizing or using a thread-safe list.
     *
     * @param processingException the exception to add
     */
    public void addError(ProcessingException processingException) {
        errors.add(processingException);
    }

    /**
     * Adds a substitution to the processing cache.
     *
     * NOTE: This method is NOT thread-safe due to TreeMap not being thread-safe.
     * If multiple threads call this method concurrently, consider synchronizing or using ConcurrentHashMap.
     *
     * @param key the substitution key
     * @param value the substitution value
     * @param type the type of substitution
     * @param repairStrategy the repair strategy
     * @param expandArray whether to expand arrays
     */
    public void addSubstitution(String key, Object value, SubstituteValue.TYPE type,
            RepairStrategy repairStrategy, boolean expandArray) {
        processingCache.put(key,
                new ArrayList<>(
                        Arrays.asList(
                                new SubstituteValue(
                                        value,
                                        type,
                                        repairStrategy, expandArray))));
    }

    public List<SubstituteValue> getDeviceEntries() {
        List<String> pathsTargetForDeviceIdentifiers;
        if (mapping.getExtension() != null || MappingType.PROTOBUF_INTERNAL.equals(mapping.getMappingType())
                || mapping.isTransformationAsCode()) {
            pathsTargetForDeviceIdentifiers = new ArrayList<>(Arrays.asList(mapping.getGenericDeviceIdentifier()));
        } else {
            pathsTargetForDeviceIdentifiers = mapping.getPathTargetForDeviceIdentifiers();
        }
        String firstPathTargetForDeviceIdentifiers = pathsTargetForDeviceIdentifiers.size() > 0
                ? pathsTargetForDeviceIdentifiers.get(0)
                : null;
        List<SubstituteValue> deviceEntries = processingCache
                .get(firstPathTargetForDeviceIdentifiers);
        return deviceEntries;
    }

    public List<String> getPathsTargetForDeviceIdentifiers() {
        List<String> pathsTargetForDeviceIdentifiers;
        if (mapping.getExtension() != null || MappingType.PROTOBUF_INTERNAL.equals(mapping.getMappingType())
                || mapping.isTransformationAsCode()) {
            pathsTargetForDeviceIdentifiers = new ArrayList<>(Arrays.asList(mapping.getGenericDeviceIdentifier()));
        } else {
            pathsTargetForDeviceIdentifiers = mapping.getPathTargetForDeviceIdentifiers();
        }
        return pathsTargetForDeviceIdentifiers;
    }

    public Set<String> getPathTargets() {
        return processingCache.keySet();
    }

    public List<SubstituteValue> getFromProcessingCache(String pathTarget) {
        return processingCache.get(pathTarget);
    }

    public Integer getProcessingCacheSize() {
        return processingCache.size();
    }

    // ===== ADAPTER METHODS FOR NEW FOCUSED CONTEXTS =====
    // These methods provide migration path from monolithic ProcessingContext
    // to focused, thread-safe context objects

    /**
     * Creates a RoutingContext from this ProcessingContext.
     * Extracts routing-related fields into an immutable, thread-safe context.
     *
     * @return a new RoutingContext with routing information
     */
    @JsonIgnore
    public RoutingContext getRoutingContext() {
        return RoutingContext.builder()
            .topic(this.topic)
            .clientId(this.clientId)
            .api(this.api)
            .qos(this.qos)
            .resolvedPublishTopic(this.resolvedPublishTopic)
            .tenant(this.tenant)
            .build();
    }

    /**
     * Creates a PayloadContext from this ProcessingContext.
     * Extracts payload-related fields into an immutable, thread-safe context.
     *
     * @return a new PayloadContext with payload information
     */
    @JsonIgnore
    public PayloadContext<O> getPayloadContext() {
        return PayloadContext.<O>builder()
            .deserializedPayload(this.payload)
            .rawPayload(this.rawPayload)
            .binaryInfo(this.binaryInfo)
            .build();
    }

    /**
     * Creates an OutputCollector from this ProcessingContext.
     * Migrates existing requests, errors, warnings, and logs into a thread-safe collector.
     *
     * Note: This creates a NEW collector with copies of current data.
     * Changes to the returned collector do NOT affect this ProcessingContext.
     *
     * @return a new OutputCollector populated with current output data
     */
    @JsonIgnore
    public OutputCollector getOutputCollector() {
        OutputCollector collector = new OutputCollector();

        // Copy requests
        if (this.requests != null) {
            this.requests.forEach(collector::addRequest);
        }

        // Copy errors
        if (this.errors != null) {
            this.errors.forEach(collector::addError);
        }

        // Copy warnings
        if (this.warnings != null) {
            this.warnings.forEach(collector::addWarning);
        }

        // Copy logs
        if (this.logs != null) {
            this.logs.forEach(collector::addLog);
        }

        return collector;
    }

    /**
     * Syncs modifications from an OutputCollector back to this ProcessingContext.
     * Replaces requests, errors, warnings, and logs with the collector's current contents.
     *
     * <p>Mirrors {@link #syncFromState(ProcessingState)} for the OutputCollector adapter:
     * without this, a caller that adds to {@link #getOutputCollector()} has no supported way
     * to propagate those additions back, and code reading {@code getRequests()}/{@code getErrors()}
     * on this context would silently miss them.
     *
     * @param collector the OutputCollector with accumulated output to sync back
     */
    public void syncFromOutputCollector(OutputCollector collector) {
        if (collector == null) {
            return;
        }

        this.requests = new CopyOnWriteArrayList<>(collector.getRequests());
        this.errors = new CopyOnWriteArrayList<>(collector.getErrors());
        this.warnings = new CopyOnWriteArrayList<>(collector.getWarnings());
        this.logs = new CopyOnWriteArrayList<>(collector.getLogs());
    }

    /**
     * Creates a ProcessingState from this ProcessingContext.
     * Migrates processing cache and flags into a thread-safe state manager.
     *
     * Note: This creates a NEW state object with copies of current data.
     * Changes to the returned state do NOT affect this ProcessingContext.
     *
     * @return a new ProcessingState populated with current processing state
     */
    @JsonIgnore
    public ProcessingState getProcessingState() {
        ProcessingState state = new ProcessingState(this.processingType, this.mappingType);

        // Copy processing cache
        if (this.processingCache != null) {
            this.processingCache.forEach(state::putSubstitutions);
        }

        // Copy flags
        state.setNeedsRepair(this.needsRepair);
        state.setIgnoreFurtherProcessing(this.ignoreFurtherProcessing);

        return state;
    }

    /**
     * Syncs modifications from ProcessingState back to this ProcessingContext.
     * Updates the processing cache and flags based on state modifications.
     *
     * @param state the ProcessingState with modifications to sync back
     */
    public void syncFromState(ProcessingState state) {
        if (state == null) {
            return;
        }

        // Sync processing cache
        this.processingCache.clear();
        state.getProcessingCache().forEach((key, values) -> {
            // Create mutable copy of the list since state returns immutable lists
            this.processingCache.put(key, new ArrayList<>(values));
        });

        // Sync flags
        this.needsRepair = state.needsRepair();
        this.ignoreFurtherProcessing = state.shouldIgnoreFurtherProcessing();
    }

    /**
     * Creates a DeviceContext from this ProcessingContext.
     * Extracts device-related fields into an immutable, thread-safe context.
     *
     * <p><b>Read-only snapshot, no sync-back:</b> unlike {@link #getProcessingState()} /
     * {@link #syncFromState(ProcessingState)} and {@link #getOutputCollector()} /
     * {@link #syncFromOutputCollector(OutputCollector)}, there is no
     * {@code syncFromDeviceContext(...)} method. {@link DeviceContext} is immutable
     * ({@code with*} methods return a new instance), so calling e.g.
     * {@code context.getDeviceContext().withExternalId(...)} does NOT affect this
     * ProcessingContext — the result is silently discarded unless you use it directly
     * (current callers only read from the returned DeviceContext) or assign the relevant
     * field on this ProcessingContext yourself (e.g. {@code context.setExternalId(...)}).
     *
     * @return a new DeviceContext with device information
     */
    @JsonIgnore
    public DeviceContext getDeviceContext() {
        DeviceContext.DeviceContextBuilder builder = DeviceContext.builder()
            .sourceId(this.sourceId)
            .externalId(this.externalId)
            .deviceName(this.deviceName)
            .deviceType(this.deviceType)
            .deviceFragments(this.deviceFragments)
            .deviceGroups(this.deviceGroups);

        // Add alarms if present
        if (this.alarms != null && !this.alarms.isEmpty()) {
            builder.alarms(this.alarms);
        }

        return builder.build();
    }

    // ===== END ADAPTER METHODS =====

    /**
     * Clean up GraalVM resources
     */
    @Override
    public void close() {
        try {
            // Close flow context first (if it holds GraalVM references)
            if (flowContext != null) {
                try {
                    flowContext.clearState();
                } catch (Exception e) {
                    log.warn("{} - Error clearing flow context state: {}", getTenant(), e.getMessage());
                }
                flowContext = null;
            }

            if (pooledGraalContext != null) {
                // Pooled path: do NOT close the GraalVM Context here.
                // engineReleaseAction (set by AbstractEnrichmentProcessor to call
                // GraalVMContextService.returnContext) will return the context to the
                // pool (or close it if it was killed).
                pooledGraalContext = null;
                graalContext = null;
            } else {
                // Non-pooled path: close the GraalVM Context directly
                if (graalContext != null) {
                    try {
                        graalContext.close();
                        log.debug("{} - Closed GraalVM Context in tenant {}", getTenant(), getTenant());
                    } catch (Exception e) {
                        log.warn("{} - Error closing GraalVM Context: {}", getTenant(), e.getMessage());
                    }
                    graalContext = null;
                }
            }

            // Notify GraalVMContextService that this context slot is freed so it can
            // close a retired Engine once all its in-flight contexts have drained.
            // For the pooled path this also returns the context to the pool.
            if (engineReleaseAction != null) {
                try {
                    engineReleaseAction.run();
                } catch (Exception e) {
                    log.warn("{} - Error in engineReleaseAction: {}", getTenant(), e.getMessage());
                }
                engineReleaseAction = null;
            }
        } catch (Exception e) {
            log.error("{} - Error during ProcessingContext cleanup: {}", getTenant(), e.getMessage(), e);
        }
    }

    /**
     * Clear flow context state
     */
    public void clearGraalVMReferences() {
        close();
    }
}