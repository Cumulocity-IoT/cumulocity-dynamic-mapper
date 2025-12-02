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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.model.API;
import dynamic.mapper.model.BinaryInfo;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.Qos;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.flow.DataPrepContext;
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

    private API api;

    private Qos qos;

    private String resolvedPublishTopic;

    /**
     * contains the deserialized payload
     */
    private O payload;

    private Object rawPayload;

    @Builder.Default
    private List<DynamicMapperRequest> requests = new ArrayList<DynamicMapperRequest>();

    @Builder.Default
    private List<Exception> errors = new ArrayList<Exception>();

    @Builder.Default
    private List<String> warnings = new ArrayList<>();

    @Builder.Default
    private List<String> logs = new ArrayList<>();

    @Builder.Default
    private ProcessingType processingType = ProcessingType.UNDEFINED;

    private MappingType mappingType;

    // <pathTarget, substituteValues>
    @Builder.Default
    // private Map<String, List<SubstituteValue>> processingCache = new
    // HashMap<String, List<SubstituteValue>>();
    // sort processingCache, so that the "_CONTEXT_DATA_.deviceName" is available
    // when creating an implicit device
    private Map<String, List<SubstituteValue>> processingCache = new TreeMap<String, List<SubstituteValue>>();

    @Builder.Default
    private Boolean sendPayload = false;

    @Builder.Default
    private Boolean testing = false;

    @Builder.Default
    private Boolean needsRepair = false;

    @Builder.Default
    private Boolean retain = false;

    private String tenant;

    private ServiceConfiguration serviceConfiguration;

    @Builder.Default
    private Boolean ignoreFurtherProcessing = false;

    private String key;

    private String sourceId;

    private String externalId;

    private Engine graalEngine;

    private Context graalContext;

    private String sharedCode;

    private Source sharedSource;

    private String systemCode;

    private Source systemSource;

    private Source mappingSource;

    private Value sourceValue;

    @Builder.Default
    private Set<String> alarms = new HashSet<>();

    @Builder.Default
    private ProcessingMode processingMode = ProcessingMode.PERSISTENT;

    private String deviceName;

    private String deviceType;

    private Object flowResult;

    private DataPrepContext flowContext;

    private Map<String, Object> flowState;

    @Builder.Default
    private BinaryInfo binaryInfo = new BinaryInfo();

    public boolean hasError() {
        return errors != null && errors.size() > 0;
    }

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

    public void addError(ProcessingException processingException) {
        errors.add(processingException);
    }

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
        pathsTargetForDeviceIdentifiers = new ArrayList<>(Arrays.asList(mapping.getGenericDeviceIdentifier()));
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

            // Close GraalVM Context
            if (graalContext != null) {
                try {
                    graalContext.close();
                    log.debug("{} - Closed GraalVM Context in tenant {}", getTenant(), getTenant());
                } catch (Exception e) {
                    log.warn("{} - Error closing GraalVM Context: {}", getTenant(), e.getMessage());
                }
                graalContext = null;
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