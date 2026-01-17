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

import static com.dashjoin.jsonata.Jsonata.jsonata;

import org.springframework.stereotype.Component;

import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.model.Substitution;
import dynamic.mapper.processor.AbstractJSONataExtractionProcessor;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.service.MappingService;
import lombok.extern.slf4j.Slf4j;

/**
 * Outbound JSONata extraction processor that extracts and processes substitutions
 * from Cumulocity payloads using JSONata expressions.
 *
 * Evaluates JSONata expressions directly against the payload object.
 */
@Slf4j
@Component
public class JSONataExtractionOutboundProcessor extends AbstractJSONataExtractionProcessor {

    public JSONataExtractionOutboundProcessor(MappingService mappingService) {
        super(mappingService);
    }

    @Override
    protected Object extractContentFromPayload(ProcessingContext<?> context,
                                              Substitution substitution,
                                              Object payloadObject,
                                              String payloadAsString) {
        Object extractedSourceContent = null;
        try {
            var expr = jsonata(substitution.getPathSource());
            extractedSourceContent = expr.evaluate(payloadObject);
        } catch (Exception e) {
            log.error("{} - EvaluateRuntimeException for: {}, {}: ", context.getTenant(),
                    substitution.getPathSource(), payloadAsString, e);
        }
        return extractedSourceContent;
    }

    @Override
    protected void handleProcessingError(Exception e, ProcessingContext<?> context, String tenant, Mapping mapping) {
        String errorMessage = String.format(
                "Tenant %s - Error in JSONataExtractionOutboundProcessor for mapping: %s,",
                tenant, mapping.getName());
        log.error(errorMessage, e);
        MappingStatus mappingStatus = mappingService.getMappingStatus(tenant, mapping);
        context.addError(new ProcessingException(errorMessage, e));
        mappingStatus.errors++;
        mappingService.increaseAndHandleFailureCount(tenant, mapping, mappingStatus);
    }

}