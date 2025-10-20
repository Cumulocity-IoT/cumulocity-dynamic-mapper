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

import java.util.HashMap;
import java.util.Map;

import org.apache.camel.Exchange;
import org.springframework.web.bind.annotation.RequestMethod;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.model.API;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.CommonProcessor;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.model.C8YMessage;
import dynamic.mapper.processor.model.DynamicMapperRequest;
import dynamic.mapper.processor.model.ProcessingContext;
import jakarta.validation.constraints.NotNull;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class BaseProcessor extends CommonProcessor {

    protected static final String EXTERNAL_ID_TOKEN = "_externalId_";

    public abstract void process(Exchange exchange) throws Exception;

    @SuppressWarnings("unchecked")
    ProcessingContext<Object> getProcessingContextAsObject(Exchange exchange) {
        return exchange.getIn().getHeader("processingContext", ProcessingContext.class);
    }

    protected Object extractContent(ProcessingContext<Object> context, Mapping mapping, Object payloadJsonNode,
            String payloadAsString, @NotNull String ps) {
        Object extractedSourceContent = null;
        try {
            // var expr = jsonata(mapping.transformGenericPath2C8YPath(ps));
            var expr = jsonata(ps);
            extractedSourceContent = expr.evaluate(payloadJsonNode);
        } catch (Exception e) {
            log.error("{} - EvaluateRuntimeException for: {}, {}: ", context.getTenant(),
                    ps,
                    payloadAsString, e);
        }
        return extractedSourceContent;
    }

    protected ProcessingContext<Object> createProcessingContextAsObject(String tenant, Mapping mapping,
            C8YMessage message, ServiceConfiguration serviceConfiguration, Boolean testing) {
        return ProcessingContext.<Object>builder()
                .payload(message.getParsedPayload())
                .topic(mapping.getPublishTopic())
                .rawPayload(message.getPayload())
                .mappingType(mapping.getMappingType())
                .mapping(mapping)
                .sendPayload(message.isSendPayload())
                .testing(testing)
                .tenant(tenant)
                .supportsMessageContext(
                        mapping.getSupportsMessageContext())
                .qos(mapping.getQos())
                .serviceConfiguration(serviceConfiguration)
                .api(message.getApi()).build();
    }

    /**
     * Sets a value hierarchically in a map using dot notation
     * E.g., "source.id" will create nested maps: {"source": {"id": value}}
     */
    protected void setHierarchicalValue(Map<String, Object> map, String path, Object value) {
        String[] keys = path.split("\\.");
        Map<String, Object> current = map;

        // Navigate/create the hierarchy up to the last key
        for (int i = 0; i < keys.length - 1; i++) {
            String key = keys[i];
            if (!current.containsKey(key) || !(current.get(key) instanceof Map)) {
                current.put(key, new HashMap<String, Object>());
            }
            current = (Map<String, Object>) current.get(key);
        }

        // Set the value at the final key
        current.put(keys[keys.length - 1], value);
    }

    /**
     * Creates a DynamicMapperRequest based on the reference implementation from
     * BaseProcessorOutbound
     * This follows the same pattern as substituteInTargetAndSend method
     */
    protected DynamicMapperRequest createAndAddDynamicMapperRequest(ProcessingContext<?> context, String payloadJson,
            String sourceId, String action, Mapping mapping) throws ProcessingException {
        try {
            // Determine the request method based on action (from substituteInTargetAndSend)
            RequestMethod method = "update".equals(action) ? RequestMethod.PUT : RequestMethod.POST; // Default from //
                                                                                                     // reference

            API api = context.getApi() != null ? context.getApi() : mapping.getTargetAPI();

            // Use -1 as predecessor for flow-generated requests (no predecessor in flow
            // context)
            int predecessor = context.getCurrentRequest() != null
                    ? context.getCurrentRequest().getPredecessor()
                    : -1;

            // Create the request using the same pattern as BaseProcessorOutbound
            DynamicMapperRequest request = DynamicMapperRequest.builder()
                    .predecessor(predecessor)
                    .method(method)
                    .sourceId(sourceId) // Device/source identifier
                    .externalIdType(mapping.getExternalIdType()) // External ID type from mapping
                    .externalId(context.getExternalId())
                    .api(api)
                    .request(payloadJson) // JSON payload
                    .build();

            context.addRequest(request);
            return request;

        } catch (Exception e) {
            throw new ProcessingException("Failed to create DynamicMapperRequest: " + e.getMessage(), e);
        }
    }

}