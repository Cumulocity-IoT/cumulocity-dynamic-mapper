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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.cumulocity.sdk.client.ProcessingMode;

import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.model.Qos;
import dynamic.mapper.processor.AbstractEnrichmentProcessor;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.model.DataPrepContext;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.TransformationType;
import dynamic.mapper.service.MappingService;
import dynamic.mapper.service.cache.FlowStateStore;
import lombok.extern.slf4j.Slf4j;

/**
 * Inbound Enrichment processor that enriches device payloads
 * with metadata and context information before transformation.
 */
@Component
@Slf4j
public class EnrichmentInboundProcessor extends AbstractEnrichmentProcessor {

    public EnrichmentInboundProcessor(
            ConfigurationRegistry configurationRegistry,
            MappingService mappingService,
            FlowStateStore flowStateStore) {
        super(configurationRegistry, mappingService, flowStateStore);
    }

    @Override
    protected void performPreEnrichmentSetup(ProcessingContext<?> context, String connectorIdentifier) {
        context.setQos(determineQos(connectorIdentifier));
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void enrichPayload(ProcessingContext<?> context) {
        /*
         * Enrich payload with _TOPIC_LEVEL_ (non-SMART_FUNCTION only) and add metadata to DataPrepContext
         */
        String tenant = context.getTenant();
        Object payloadObject = context.getPayload();
        Mapping mapping = context.getMapping();
        boolean isSmartFunction = TransformationType.SMART_FUNCTION.equals(mapping.getTransformationType())
                || TransformationType.EXTENSION_JAVA.equals(mapping.getTransformationType());

        // For SMART_FUNCTION: populate read-only config — never expand the payload Map
        DataPrepContext flowContext = context.getFlowContext();
        if (isSmartFunction) {
            if (flowContext instanceof dynamic.mapper.processor.model.SmartFunctionContext) {
                dynamic.mapper.processor.model.SmartFunctionContext sfContext =
                        (dynamic.mapper.processor.model.SmartFunctionContext) flowContext;
                sfContext.setClientId(context.getClientId());

                Map<String, Object> config = new HashMap<>();
                config.put("tenant", tenant);
                config.put("topic", context.getTopic());
                config.put("clientId", context.getClientId());
                config.put("mappingName", mapping.getName());
                config.put("mappingId", mapping.getId());
                config.put("targetAPI", mapping.getTargetAPI().toString());
                config.put(ProcessingContext.DEBUG, mapping.getDebug());

                if (context.getMapping().getEventWithAttachment()) {
                    config.put(ProcessingContext.ATTACHMENT_TYPE, "");
                    config.put(ProcessingContext.ATTACHMENT_NAME, "");
                    config.put(ProcessingContext.ATTACHMENT_DATA, "");
                    config.put(ProcessingContext.EVENT_WITH_ATTACHMENT, true);
                }
                if (context.getMapping().getCreateNonExistingDevice()) {
                    config.put(ProcessingContext.DEVICE_NAME, context.getDeviceName());
                    config.put(ProcessingContext.DEVICE_TYPE, context.getDeviceType());
                    config.put(ProcessingContext.CREATE_NON_EXISTING_DEVICE, true);
                }
                sfContext.setConfig(config);
            }
        } else if (payloadObject instanceof Map) {
            Map<String, Object> payloadMap = (Map<String, Object>) payloadObject;
            List<String> splitTopicAsList = Mapping.splitTopicExcludingSeparatorAsList(context.getTopic(), false);

            payloadMap.put(Mapping.TOKEN_TOPIC_LEVEL, splitTopicAsList);

            // Process message context
            if (context.getKey() != null) {
                String keyString = context.getKey();
                Map<String, String> contextData = new HashMap<>();
                contextData.put(Mapping.CONTEXT_DATA_KEY_NAME, keyString);
                contextData.put("api", context.getMapping().getTargetAPI().toString());
                contextData.put("processingMode", ProcessingMode.PERSISTENT.toString());
                if (context.getMapping().getCreateNonExistingDevice()) {
                    contextData.put(ProcessingContext.DEVICE_NAME, context.getDeviceName());
                    contextData.put(ProcessingContext.DEVICE_TYPE, context.getDeviceType());
                }

                payloadMap.put(Mapping.TOKEN_CONTEXT_DATA, contextData);
            }

            // Handle attachment properties independently
            if (context.getMapping().getEventWithAttachment()) {
                Map<String, String> contextData;
                if (payloadMap.containsKey(Mapping.TOKEN_CONTEXT_DATA)) {
                    contextData = (Map<String, String>) payloadMap.get(Mapping.TOKEN_CONTEXT_DATA);
                } else {
                    contextData = new HashMap<>();
                    payloadMap.put(Mapping.TOKEN_CONTEXT_DATA, contextData);
                }

                contextData.put(ProcessingContext.ATTACHMENT_TYPE, "");
                contextData.put(ProcessingContext.ATTACHMENT_NAME, "");
                contextData.put(ProcessingContext.ATTACHMENT_DATA, "");
            }

        } else {
            log.info(
                    "{} - This message is not parsed by Base Inbound Processor, will be potentially parsed by extension due to custom format.",
                    tenant);
        }

        if (flowContext != null) {
            log.debug("{} - Enriched DataPrepContext with payload enrichment data", tenant);
        }
    }

    @Override
    protected void handleEnrichmentError(String tenant, Mapping mapping, Exception e,
            ProcessingContext<?> context, MappingStatus mappingStatus) {
        String errorMessage = String.format("%s - Error in enrichment phase for mapping: %s", tenant,
                mapping.getName());
        log.error(errorMessage, e);
        if (e instanceof ProcessingException) {
            context.addError((ProcessingException) e);
        } else {
            context.addError(new ProcessingException(errorMessage, e));
        }
        mappingStatus.errors++;
        mappingService.increaseAndHandleFailureCount(tenant, mapping, mappingStatus);
    }

    private Qos determineQos(String connectorIdentifier) {
        // Determine QoS based on connector type
        if ("mqtt".equalsIgnoreCase(connectorIdentifier)) {
            return Qos.AT_LEAST_ONCE;
        } else if ("kafka".equalsIgnoreCase(connectorIdentifier)) {
            return Qos.EXACTLY_ONCE;
        } else {
            return Qos.AT_MOST_ONCE; // Default for HTTP, etc.
        }
    }
}
