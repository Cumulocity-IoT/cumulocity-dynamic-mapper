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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;
import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.processor.AbstractEnrichmentProcessor;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.model.DataPrepContext;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.TransformationType;
import dynamic.mapper.service.MappingService;
import dynamic.mapper.service.cache.FlowStateStore;
import lombok.extern.slf4j.Slf4j;

/**
 * Outbound Enrichment processor that enriches Cumulocity operation payloads
 * with identity information and metadata before transformation.
 */
@Component
@Slf4j
public class EnrichmentOutboundProcessor extends AbstractEnrichmentProcessor {

    private final C8YAgent c8yAgent;

    public EnrichmentOutboundProcessor(
            ConfigurationRegistry configurationRegistry,
            MappingService mappingService,
            C8YAgent c8yAgent,
            FlowStateStore flowStateStore) {
        super(configurationRegistry, mappingService, flowStateStore);
        this.c8yAgent = c8yAgent;
    }

    @Override
    protected void enrichPayload(ProcessingContext<?> context) throws ProcessingException {
        /*
         * Enrich payload with _IDENTITY_ property containing source device information
         */
        String tenant = context.getTenant();
        Object payloadObject = context.getPayload();
        Mapping mapping = context.getMapping();
        boolean isSmartFunction = TransformationType.SMART_FUNCTION.equals(mapping.getTransformationType())
                || TransformationType.EXTENSION_JAVA.equals(mapping.getTransformationType());

        String identifier = context.getApi().identifier;
        String payloadAsString = toPrettyJsonString(payloadObject);
        Object sourceId = extractContent(context, payloadObject, payloadAsString, identifier);
        if (sourceId == null) {
            throw new ProcessingException(
                    String.format("Could not extract source ID from payload using path '%s'", identifier));
        }
        context.setSourceId(sourceId.toString());

        Map<String, String> identityFragment = new HashMap<>();
        identityFragment.put("c8ySourceId", sourceId.toString());
        identityFragment.put("externalIdType", mapping.getExternalIdType());

        // For SMART_FUNCTION/EXTENSION_JAVA: add to DataPrepContext only — never expand the payload Map
        // _IDENTITY_ and _TOPIC_LEVEL_ are template-substitution tokens not relevant here;
        // the function/extension reads device identity directly from the C8Y payload.
        DataPrepContext flowContext = context.getFlowContext();
        if (isSmartFunction) {
            if (flowContext != null && context.getGraalContext() != null) {
                addToFlowContext(flowContext, context, ProcessingContext.RETAIN, false);
            }
        } else {
            if (payloadObject instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> payloadMap = (Map<String, Object>) payloadObject;
                payloadMap.put(Mapping.TOKEN_IDENTITY, identityFragment);
                payloadMap.put(ProcessingContext.RETAIN, false);
                List<String> splitTopicExAsList = Mapping.splitTopicExcludingSeparatorAsList(context.getTopic(), false);
                payloadMap.put(Mapping.TOKEN_TOPIC_LEVEL, splitTopicExAsList);
            } else {
                log.warn("{} - Parsing this message as JSONArray, no elements from the topic level can be used!",
                        tenant);
            }
        }

        if (mapping.getUseExternalId() && !mapping.getExternalIdType().isEmpty()) {
            ExternalIDRepresentation externalId = c8yAgent.resolveGlobalId2ExternalId(context.getTenant(),
                    new GId(sourceId.toString()), mapping.getExternalIdType(),
                    context.getTesting());
            if (externalId == null) {
                if (context.getSendPayload()) {
                    throw new RuntimeException(String.format("External id %s for type %s not found!",
                            sourceId.toString(), mapping.getExternalIdType()));
                }
            } else {
                identityFragment.put("externalId", externalId.getExternalId());
            }
        }
    }

    @Override
    protected void handleEnrichmentError(String tenant, Mapping mapping, Exception e,
            ProcessingContext<?> context, MappingStatus mappingStatus) {
        String errorMessage = String.format("%s - Error in enrichment phase for mapping: %s", tenant,
                mapping.getName());
        log.error(errorMessage, e);
        context.addError(new ProcessingException(errorMessage, e));
        context.setIgnoreFurtherProcessing(true);
        mappingStatus.errors++;
        mappingService.increaseAndHandleFailureCount(tenant, mapping, mappingStatus);
    }

    /**
     * Convert payload object to pretty JSON string for logging
     */
    private String toPrettyJsonString(Object payloadObject) {
        ObjectMapper objectMapper = configurationRegistry.getObjectMapper();
        try {
            if (payloadObject == null) {
                return "null";
            }

            if (payloadObject instanceof String) {
                return (String) payloadObject;
            }

            // Use ObjectMapper to convert to pretty JSON
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payloadObject);

        } catch (Exception e) {
            log.warn("Failed to convert payload to pretty JSON string: {}", e.getMessage());
            return payloadObject != null ? payloadObject.toString() : "null";
        }
    }
}
