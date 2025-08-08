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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.model.API;
import dynamic.mapper.model.BinaryInfo;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.Qos;
import dynamic.mapper.processor.ProcessingException;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import com.cumulocity.sdk.client.ProcessingMode;

@Getter
@Setter
@Builder
/*
 * The class <code>ProcessingContext</code> collects all relevant information:
 * <code>mapping</code>, <code>topic</code>, <code>payload</code>,
 * <code>requests</code>, <code>error</code>, <code>processingType</code>,
 * <code>cardinality</code>, <code>needsRepair</code>
 * when a <code>mapping</code> is applied to an inbound <code>payload</code>
 */
public class ProcessingContext<O> {

    private Mapping mapping;

    private String topic;

    private API api;

    private Qos qos;

    private String resolvedPublishTopic;

    /**
     * contains the deserialized payload
     */
    private O payload;

    private Object rawPayload;

    @Builder.Default
    private List<C8YRequest> requests = new ArrayList<C8YRequest>();

    @Builder.Default
    private List<Exception> errors = new ArrayList<Exception>();

    @Builder.Default
    private ProcessingType processingType = ProcessingType.UNDEFINED;

    private MappingType mappingType;

    // <pathTarget, substituteValues>
    @Builder.Default
    private Map<String, List<SubstituteValue>> processingCache = new HashMap<String, List<SubstituteValue>>();

    @Builder.Default
    private boolean sendPayload = false;

    @Builder.Default
    private boolean needsRepair = false;

    private String tenant;

    private ServiceConfiguration serviceConfiguration;

    @Builder.Default
    private boolean supportsMessageContext = false;

    @Builder.Default
    private boolean ignoreFurtherProcessing = false;

    private byte[] key;

    private String sourceId;

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

    @Builder.Default
    private BinaryInfo binaryInfo = new BinaryInfo();

    public static final String SOURCE_ID = "source.id";

    public boolean hasError() {
        return errors != null && errors.size() > 0;
    }

    public int addRequest(C8YRequest c8yRequest) {
        requests.add(c8yRequest);
        return requests.size() - 1;
    }

    public C8YRequest getCurrentRequest() {
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
        if (mapping.extension != null || MappingType.PROTOBUF_INTERNAL.equals(mapping.getMappingType())
                || MappingType.CODE_BASED.equals(mapping.getMappingType())) {
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
        if (mapping.extension != null || MappingType.PROTOBUF_INTERNAL.equals(mapping.getMappingType())
                || MappingType.CODE_BASED.equals(mapping.getMappingType())) {
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
}