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
package dynamic.mapper.processor.inbound.processor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.joda.time.DateTime;
import org.springframework.stereotype.Component;

import dynamic.mapper.model.API;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.processor.AbstractCodeExtractionProcessor;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.RepairStrategy;
import dynamic.mapper.processor.model.SubstituteValue;
import dynamic.mapper.processor.model.SubstituteValue.TYPE;
import dynamic.mapper.processor.model.SubstitutionEvaluation;
import dynamic.mapper.processor.model.SubstitutionResult;
import dynamic.mapper.service.MappingService;
import lombok.extern.slf4j.Slf4j;

/**
 * Inbound Code extraction processor that executes JavaScript code
 * to extract and process substitutions from device payloads.
 *
 * Includes special handling for:
 * - Adding topic levels as metadata
 * - Adding context data (API type)
 * - Time substitution - if no time substitution is provided and the target API requires time, system time is automatically added
 */
@Slf4j
@Component
public class CodeExtractionInboundProcessor extends AbstractCodeExtractionProcessor {

    public CodeExtractionInboundProcessor(MappingService mappingService) {
        super(mappingService);
    }

    @Override
    protected void preparePayload(ProcessingContext<?> context, Object payloadObject) {
        // Add topic levels as metadata
        @SuppressWarnings("unchecked")
        Map<String, Object> jsonObject = (Map<String, Object>) payloadObject;
        List<String> splitTopicAsList = Mapping.splitTopicExcludingSeparatorAsList(context.getTopic(), false);
        jsonObject.put(Mapping.TOKEN_TOPIC_LEVEL, splitTopicAsList);

        // Add context data with API information
        Map<String, String> contextData = new HashMap<String, String>() {
            {
                put("api", context.getMapping().getTargetAPI().toString());
            }
        };
        jsonObject.put(Mapping.TOKEN_CONTEXT_DATA, contextData);
    }

    @Override
    protected void processSubstitutionResult(
            SubstitutionResult typedResult,
            ProcessingContext<?> context,
            Object payloadObject,
            Mapping mapping,
            String tenant) throws ProcessingException {

        @SuppressWarnings("unchecked")
        Map<String, Object> jsonObject = (Map<String, Object>) payloadObject;
        Map<String, List<SubstituteValue>> processingCache = context.getProcessingCache();

        if (typedResult == null || typedResult.substitutions == null || typedResult.substitutions.isEmpty()) {
            context.setIgnoreFurtherProcessing(true);
            log.info("{} - Extraction returned no result, payload: {}", tenant, jsonObject);
            return;
        }

        Set<String> keySet = typedResult.getSubstitutions().keySet();
        boolean substitutionTimeExists = false;

        for (String key : keySet) {
            List<SubstituteValue> processingCacheEntry = new ArrayList<>();
            List<SubstituteValue> values = typedResult.getSubstitutions().get(key);

            if (values != null && !values.isEmpty() && values.get(0).expandArray) {
                // Handle array expansion
                for (SubstituteValue substitutionValue : values) {
                    SubstitutionEvaluation.processSubstitute(
                            tenant,
                            processingCacheEntry,
                            substitutionValue.value,
                            substitutionValue,
                            mapping);
                }
            } else if (values != null && !values.isEmpty()) {
                SubstituteValue firstValue = values.get(0);
                SubstitutionEvaluation.processSubstitute(
                        tenant,
                        processingCacheEntry,
                        firstValue.value,
                        firstValue,
                        mapping);
            }

            processingCache.put(key, processingCacheEntry);

            if (key.equals(Mapping.KEY_TIME)) {
                substitutionTimeExists = true;
            }
        }

        // Handle alarms
        if (typedResult.alarms != null && !typedResult.alarms.isEmpty()) {
            for (String alarm : typedResult.alarms) {
                context.getAlarms().add(alarm);
                log.debug("{} - Alarm added: {}", tenant, alarm);
            }
        }

        if (mapping.getDebug() || context.getServiceConfiguration().getLogPayload()) {
            log.info("{} - Extraction returned {} results, payload: {}",
                    tenant,
                    keySet.size(),
                    jsonObject);
        }

        // Add default time if not exists
        if (!substitutionTimeExists &&
                mapping.getTargetAPI() != API.INVENTORY &&
                mapping.getTargetAPI() != API.OPERATION) {

            List<SubstituteValue> processingCacheEntry = processingCache.getOrDefault(
                    Mapping.KEY_TIME,
                    new ArrayList<>());
            processingCacheEntry.add(
                    new SubstituteValue(
                            new DateTime().toString(),
                            TYPE.TEXTUAL,
                            RepairStrategy.CREATE_IF_MISSING,
                            false));
            processingCache.put(Mapping.KEY_TIME, processingCacheEntry);
        }
    }

    @Override
    protected void handleProcessingError(Exception e, ProcessingContext<?> context, String tenant, Mapping mapping) {
        int lineNumber = 0;
        if (e.getStackTrace().length > 0) {
            lineNumber = e.getStackTrace()[0].getLineNumber();
        }
        String errorMessage = String.format(
                "Tenant %s - Error in CodeExtractionInboundProcessor: %s for mapping: %s, line %s",
                tenant, mapping.getName(), e.getMessage(), lineNumber);
        log.error(errorMessage, e);

        if (e instanceof ProcessingException) {
            context.addError((ProcessingException) e);
        } else {
            context.addError(new ProcessingException(errorMessage, e));
        }

        if (!context.getTesting()) {
            MappingStatus mappingStatus = mappingService.getMappingStatus(tenant, mapping);
            mappingStatus.errors++;
            mappingService.increaseAndHandleFailureCount(tenant, mapping, mappingStatus);
            context.setIgnoreFurtherProcessing(true);
        }
    }
}
