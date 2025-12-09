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

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.camel.Exchange;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;
import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.configuration.TemplateType;
import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.core.InventoryEnrichmentClient;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.flow.SimpleFlowContext;
import dynamic.mapper.processor.flow.DataPrepContext;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.TransformationType;
import dynamic.mapper.service.MappingService;
import lombok.extern.slf4j.Slf4j;

import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

@Component
@Slf4j
public class MappingContextOutboundProcessor extends BaseProcessor {

    @Autowired
    private ConfigurationRegistry configurationRegistry;

    @Autowired
    private MappingService mappingService;

    @Autowired
    private C8YAgent c8yAgent;

    private Context.Builder graalContextBuilder;

    @Override
    public void process(Exchange exchange) throws Exception {
        ProcessingContext<?> context = exchange.getIn().getHeader("processingContext",
                ProcessingContext.class);

        String tenant = context.getTenant();
        Mapping mapping = context.getMapping();

        ServiceConfiguration serviceConfiguration = context.getServiceConfiguration();
        MappingStatus mappingStatus = mappingService.getMappingStatus(tenant, mapping);

        // Extract additional info from headers if available
        String connectorIdentifier = exchange.getIn().getHeader("connectorIdentifier", String.class);

        // Prepare GraalVM context if code exists
        if (mapping.getCode() != null
                && mapping.isSubstitutionAsCode()) {
            try {
                // contextSemaphore.acquire();
                var graalEngine = configurationRegistry.getGraalEngine(tenant);
                var graalContext = createGraalContext(graalEngine);
                context.setGraalContext(graalContext);
                context.setSharedCode(serviceConfiguration.getCodeTemplates()
                        .get(TemplateType.SHARED.name()).getCode());
                context.setSystemCode(serviceConfiguration.getCodeTemplates()
                        .get(TemplateType.SYSTEM.name()).getCode());
            } catch (Exception e) {
                handleGraalVMError(tenant, mapping, e, context);
                return;
            }
        } else if (mapping.getCode() != null &&
                TransformationType.SMART_FUNCTION.equals(mapping.getTransformationType())) {
            try {
                var graalEngine = configurationRegistry.getGraalEngine(tenant);
                var graalContext = createGraalContext(graalEngine);
                // processingContext.setSystemCode(serviceConfiguration.getCodeTemplates()
                // .get(TemplateType.SMART.name()).getCode());
                context.setGraalContext(graalContext);
                context.setFlowState(new HashMap<String, Object>());
                context.setFlowContext(new SimpleFlowContext(graalContext, tenant,
                        (InventoryEnrichmentClient) configurationRegistry.getC8yAgent(),
                        context.getTesting()));

            } catch (Exception e) {
                handleGraalVMError(tenant, mapping, e, context);
                return;
            }
        }

        mappingStatus.messagesReceived++;
        logOutboundMessageReceived(tenant, mapping, connectorIdentifier, context, serviceConfiguration);

        // Now call the enrichment logic
        try {
            enrichPayload(context);
        } catch (Exception e) {
            String errorMessage = String.format("%s - Error in enrichment phase for mapping: %s", tenant,
                    mapping.getName());
            log.error(errorMessage, e);
            context.addError(new ProcessingException(errorMessage, e));
            context.setIgnoreFurtherProcessing(true);
            mappingStatus.errors++;
            mappingService.increaseAndHandleFailureCount(tenant, mapping, mappingStatus);
            return;
        }
    }

    private Context createGraalContext(Engine graalEngine)
            throws Exception {
        if (graalContextBuilder == null)
            graalContextBuilder = Context.newBuilder("js");

        Context graalContext = graalContextBuilder
                .engine(graalEngine)
                // .option("engine.WarnInterpreterOnly", "false")
                .allowHostAccess(configurationRegistry.getHostAccess())
                .allowHostClassLookup(className ->
                // Allow only the specific SubstitutionContext class
                className.equals("dynamic.mapper.processor.model.SubstitutionContext")
                        || className.equals("dynamic.mapper.processor.model.SubstitutionResult")
                        || className.equals("dynamic.mapper.processor.model.SubstituteValue")
                        || className.equals("dynamic.mapper.processor.model.SubstituteValue$TYPE")
                        || className.equals("dynamic.mapper.processor.model.RepairStrategy")
                        // Allow base collection classes needed for return values
                        || className.equals("java.util.ArrayList") ||
                        className.equals("java.util.HashMap") ||
                        className.equals("java.util.HashSet"))
                .build();
        return graalContext;
    }

    private void logOutboundMessageReceived(String tenant, Mapping mapping, String connectorIdentifier,
            ProcessingContext<?> context,
            ServiceConfiguration serviceConfiguration) {
        if (serviceConfiguration.getLogPayload() || mapping.getDebug()) {
            Object pp = context.getPayload();
            String ppLog = null;

            if (pp instanceof byte[]) {
                ppLog = new String((byte[]) pp, StandardCharsets.UTF_8);
            } else if (pp != null) {
                ppLog = pp.toString();
            }
            log.info(
                    "{} - PROCESSING message on topic: [{}], on  connector: {}, for Mapping {} with QoS: {}, wrapped message: {}",
                    tenant, context.getTopic(), connectorIdentifier, mapping.getName(),
                    mapping.getQos().ordinal(), ppLog);
        } else {
            log.info(
                    "{} - PROCESSING message on topic: [{}], on  connector: {}, for Mapping {} with QoS: {}",
                    tenant, context.getTopic(), connectorIdentifier, mapping.getName(),
                    mapping.getQos().ordinal());
        }
    }

    private void handleGraalVMError(String tenant, Mapping mapping, Exception e,
            ProcessingContext<?> context) {
        MappingStatus mappingStatus = mappingService
                .getMappingStatus(tenant, mapping);
        String errorMessage = String.format("Tenant %s - Failed to set up GraalVM context: %s",
                tenant, e.getMessage());
        log.error(errorMessage, e);
        context.addError(new ProcessingException(errorMessage, e));
        mappingStatus.errors++;
        mappingService.increaseAndHandleFailureCount(tenant, mapping, mappingStatus);
    }

    // ========== ENRICHMENT LOGIC (from EnrichmentOutboundProcessor) ==========

    public void enrichPayload(ProcessingContext<?> context) {

        /*
         * step 0 patch payload with dummy property _IDENTITY_ in case the content
         * is required in the payload for a substitution
         */
        String tenant = context.getTenant();
        Object payloadObject = context.getPayload();
        Mapping mapping = context.getMapping();

        String identifier = context.getTesting() ? "_IDENTITY_.c8ySourceId" : context.getApi().identifier;
        String payloadAsString = toPrettyJsonString(payloadObject);
        Object sourceId = extractContent(context, payloadObject, payloadAsString,
                identifier);
        context.setSourceId(sourceId.toString());

        Map<String, String> identityFragment = new HashMap<>();
        identityFragment.put("c8ySourceId", sourceId.toString());
        identityFragment.put("externalIdType", mapping.getExternalIdType());

        // Add topic levels to DataPrepContext if available
        DataPrepContext flowContext = context.getFlowContext();
        if (flowContext != null && context.getGraalContext() != null
                && TransformationType.SMART_FUNCTION.equals(context.getMapping().getTransformationType())) {
            addToFlowContext(flowContext, context, Mapping.TOKEN_IDENTITY, identityFragment);
            List<String> splitTopicExAsList = Mapping.splitTopicExcludingSeparatorAsList(context.getTopic(), false);
            addToFlowContext(flowContext, context, Mapping.TOKEN_TOPIC_LEVEL, splitTopicExAsList);
            addToFlowContext(flowContext, context, ProcessingContext.RETAIN, false);
        } else {
            if (payloadObject instanceof Map) {
                ((Map) payloadObject).put(Mapping.TOKEN_IDENTITY, identityFragment);
                ((Map) payloadObject).put(ProcessingContext.RETAIN, false);
                List<String> splitTopicExAsList = Mapping.splitTopicExcludingSeparatorAsList(context.getTopic(), false);
                ((Map) payloadObject).put(Mapping.TOKEN_TOPIC_LEVEL, splitTopicExAsList);
            } else {
                log.warn("{} - Parsing this message as JSONArray, no elements from the topic level can be used!",
                        tenant);
            }
        }

        if (mapping.getUseExternalId() && !("").equals(mapping.getExternalIdType())) {
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

    /**
     * Helper method to safely add values to DataPrepContext
     */
    private void addToFlowContext(DataPrepContext flowContext, ProcessingContext<?> context, String key,
            Object value) {
        try {
            if (context.getGraalContext() != null && value != null) {
                Value graalValue = context.getGraalContext().asValue(value);
                flowContext.setState(key, graalValue);
            }
        } catch (Exception e) {
            log.warn("{} - Failed to add '{}' to DataPrepContext: {}", context.getTenant(), key, e.getMessage());
        }
    }

}