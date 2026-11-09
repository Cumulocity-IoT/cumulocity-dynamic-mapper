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

package dynamic.mapper.processor.inbound.processor;

import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.CommonProcessor;
import dynamic.mapper.processor.runtime.ProcessingContext;
import dynamic.mapper.processor.model.SubstituteValue;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class BaseProcessor extends CommonProcessor {

    protected ProcessingContext<Object> createProcessingContextAsObject(String tenant, Mapping mapping,
            ConnectorMessage connectorMessage, ServiceConfiguration serviceConfiguration, Boolean testing) {
        return ProcessingContext.<Object>builder()
                .rawPayload(connectorMessage.getPayload())
                .topic(connectorMessage.getTopic())
                .clientId(connectorMessage.getClientId())
                .connectorIdentifier(connectorMessage.getConnectorIdentifier())
                .mappingType(mapping.getMappingType())
                .serviceConfiguration(serviceConfiguration)
                .mapping(mapping)
                .sendPayload(connectorMessage.getSendPayload())
                .testExternalIdPrefix(connectorMessage.getTestExternalIdPrefix())
                .testRun(Boolean.TRUE.equals(connectorMessage.getTestRun()))
                .testing(testing)
                .tenant(tenant)
                .key(connectorMessage.getKey())
                .transportMetadata(connectorMessage.getMetadata())
                .api(mapping.getTargetAPI()).build();
    }

    protected ProcessingContext<byte[]> createProcessingContextAsByteArray(String tenant, Mapping mapping,
            ConnectorMessage connectorMessage, ServiceConfiguration serviceConfiguration, Boolean testing) {
        return ProcessingContext.<byte[]>builder().rawPayload(connectorMessage.getPayload())
                .topic(connectorMessage.getTopic())
                .clientId(connectorMessage.getClientId())
                .connectorIdentifier(connectorMessage.getConnectorIdentifier())
                .mappingType(mapping.getMappingType())
                .serviceConfiguration(serviceConfiguration)
                .mapping(mapping)
                .sendPayload(connectorMessage.getSendPayload())
                .testExternalIdPrefix(connectorMessage.getTestExternalIdPrefix())
                .testRun(Boolean.TRUE.equals(connectorMessage.getTestRun()))
                .testing(testing)
                .tenant(tenant)
                .key(connectorMessage.getKey())
                .transportMetadata(connectorMessage.getMetadata())
                .api(mapping.getTargetAPI())
                .build();
    }

    protected void validateProcessingCache(ProcessingContext<?> context) {
        // if there are too few devices identified, then we replicate the first device
        Map<String, List<SubstituteValue>> processingCache = context.getProcessingCache();
        if (processingCache == null || processingCache.isEmpty()) {
            return;
        }
        String entryWithMaxSubstitutes = processingCache.entrySet()
                .stream()
                .map(entry -> new AbstractMap.SimpleEntry<String, Integer>(entry.getKey(), entry.getValue().size()))
                .max((Entry<String, Integer> e1, Entry<String, Integer> e2) -> e1.getValue()
                        .compareTo(e2.getValue()))
                .get().getKey();
        int countMaxEntries = processingCache.get(entryWithMaxSubstitutes).size();

        List<String> pathsTargetForDeviceIdentifiers = context.getPathsTargetForDeviceIdentifiers();
        if (pathsTargetForDeviceIdentifiers == null || pathsTargetForDeviceIdentifiers.isEmpty()) {
            // No identifier path configured on this mapping — nothing to replicate against.
            return;
        }
        String firstPathTargetForDeviceIdentifiers = pathsTargetForDeviceIdentifiers.get(0);

        List<SubstituteValue> deviceEntries = processingCache
                .get(firstPathTargetForDeviceIdentifiers);
        if (deviceEntries == null || deviceEntries.isEmpty()) {
            return;
        }
        SubstituteValue toDuplicate = deviceEntries.get(0);
        while (deviceEntries.size() < countMaxEntries) {
            deviceEntries.add(toDuplicate);
        }
    }


}
