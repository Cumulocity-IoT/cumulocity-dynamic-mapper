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

package dynamic.mapper.processor.outbound.processor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.processor.AbstractCodeExtractionProcessor;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.SubstituteValue;
import dynamic.mapper.processor.model.SubstitutionEvaluation;
import dynamic.mapper.processor.model.SubstitutionResult;
import dynamic.mapper.service.MappingService;
import lombok.extern.slf4j.Slf4j;

/**
 * Outbound Code extraction processor that executes JavaScript code
 * to extract and process substitutions from Cumulocity payloads.
 */
@Slf4j
@Component
public class CodeExtractionOutboundProcessor extends AbstractCodeExtractionProcessor {

    public CodeExtractionOutboundProcessor(MappingService mappingService) {
        super(mappingService);
    }

    @Override
    protected void processSubstitutionResult(
            SubstitutionResult javaResult,
            ProcessingContext<?> context,
            Object payloadObject,
            Mapping mapping,
            String tenant) throws ProcessingException {

        @SuppressWarnings("unchecked")
        Map<?, ?> jsonObject = (Map<?, ?>) payloadObject;
        Map<String, List<SubstituteValue>> processingCache = context.getProcessingCache();

        if (javaResult == null || javaResult.substitutions == null || javaResult.substitutions.isEmpty()) {
            context.setIgnoreFurtherProcessing(true);
            log.info("{} - Extraction returned no results, payload: {}", tenant, jsonObject);
            return;
        }

        // Process the converted Java objects
        Set<String> keySet = javaResult.substitutions.keySet();

        for (String key : keySet) {
            List<SubstituteValue> processingCacheEntry = new ArrayList<>();
            List<SubstituteValue> values = javaResult.substitutions.get(key);

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
        }

        // Handle alarms
        if (javaResult.alarms != null && !javaResult.alarms.isEmpty()) {
            for (String alarm : javaResult.alarms) {
                context.getAlarms().add(alarm);
                log.debug("{} - Alarm added: {}", tenant, alarm);
            }
        }

        if (context.getMapping().getDebug() || context.getServiceConfiguration().getLogPayload()) {
            log.info("{} - Extraction returned {} results, payload: {}",
                    tenant,
                    keySet.size(),
                    jsonObject);
        }
    }

    @Override
    protected void handleProcessingError(Exception e, ProcessingContext<?> context, String tenant, Mapping mapping) {
        int lineNumber = 0;
        if (e.getStackTrace().length > 0) {
            lineNumber = e.getStackTrace()[0].getLineNumber();
        }
        String errorMessage = String.format(
                "Tenant %s - Error in CodeExtractionOutboundProcessor: %s for mapping: %s, line %s",
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
