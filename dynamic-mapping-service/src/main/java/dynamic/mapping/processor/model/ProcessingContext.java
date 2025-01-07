/*
 * Copyright (c) 2022 Software AG, Darmstadt, Germany and/or Software AG USA Inc., Reston, VA, USA,
 * and/or its subsidiaries and/or its affiliates and/or their licensors.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @authors Christof Strack, Stefan Witschel
 */

package dynamic.mapping.processor.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dynamic.mapping.configuration.ServiceConfiguration;
import dynamic.mapping.model.Mapping;
import dynamic.mapping.model.MappingSubstitution;
import dynamic.mapping.model.MappingSubstitution.SubstituteValue;
import dynamic.mapping.model.QOS;
import dynamic.mapping.processor.ProcessingException;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;


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

    private QOS qos;

    private String resolvedPublishTopic;

    /**
     * contains the deserialized payload
     */
    private O payload;

    private byte[] payloadRaw;

    @Builder.Default
    private List<C8YRequest> requests = new ArrayList<C8YRequest>();

    @Builder.Default
    private List<Exception> errors = new ArrayList<Exception>();

    @Builder.Default
    private ProcessingType processingType = ProcessingType.UNDEFINED;

    private MappingType mappingType;

    // <pathTarget, substituteValues>
    @Builder.Default
    private Map<String, List<MappingSubstitution.SubstituteValue>> processingCache = new HashMap<String, List<MappingSubstitution.SubstituteValue>>();

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

    public void addToProcessingCache(String key, Object value, MappingSubstitution.SubstituteValue.TYPE type,
            RepairStrategy repairStrategy) {
        processingCache.put(key,
                new ArrayList<>(
                        Arrays.asList(
                                new MappingSubstitution.SubstituteValue(
                                        value,
                                        type,
                                        repairStrategy))));
    }

    public List<MappingSubstitution.SubstituteValue> getDeviceEntries() {
        List<String> pathsTargetForDeviceIdentifiers;
        if (mapping.extension != null || MappingType.PROTOBUF_INTERNAL.equals(mapping.getMappingType())) {
            pathsTargetForDeviceIdentifiers = new ArrayList<>(Arrays.asList(mapping.getGenericDeviceIdentifier()));
        } else {
            pathsTargetForDeviceIdentifiers = mapping.getPathTargetForDeviceIdentifiers();
        }
        String firstPathTargetForDeviceIdentifiers = pathsTargetForDeviceIdentifiers.size() > 0
                ? pathsTargetForDeviceIdentifiers.get(0)
                : null;
        List<MappingSubstitution.SubstituteValue> deviceEntries = processingCache
                .get(firstPathTargetForDeviceIdentifiers);
        return deviceEntries;
    }

    public List<String> getPathsTargetForDeviceIdentifiers() {
        List<String> pathsTargetForDeviceIdentifiers;
        if (mapping.extension != null || MappingType.PROTOBUF_INTERNAL.equals(mapping.getMappingType())) {
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