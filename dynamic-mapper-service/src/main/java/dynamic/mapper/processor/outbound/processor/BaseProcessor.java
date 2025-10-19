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

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.springframework.web.bind.annotation.RequestMethod;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.model.API;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.model.C8YMessage;
import dynamic.mapper.processor.model.DynamicMapperRequest;
import dynamic.mapper.processor.model.ProcessingContext;
import jakarta.validation.constraints.NotNull;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class BaseProcessor implements Processor {

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
     * Create C8Y request with correct structure
     */
    protected int createDynamicMapperRequest(int predecessor, String processedPayload,
            ProcessingContext<?> context,
            Mapping mapping) {
        API api = context.getApi() != null ? context.getApi() : determineDefaultAPI(mapping);

        DynamicMapperRequest request = DynamicMapperRequest.builder()
                .predecessor(predecessor)
                .api(api)
                .method(context.getMapping().getUpdateExistingDevice() ? RequestMethod.POST : RequestMethod.PATCH)
                .sourceId(context.getSourceId())
                .externalIdType(mapping.getExternalIdType())
                .externalId(context.getExternalId())
                .request(processedPayload)
                .build();

        var newPredecessor = context.addRequest(request);
        log.debug("Created C8Y request for API: {} with payload: {}", api, processedPayload);
        return newPredecessor;
    }

    /**
     * Determine default API from mapping
     */
    private API determineDefaultAPI(Mapping mapping) {
        if (mapping.getTargetAPI() != null) {
            try {
                return mapping.getTargetAPI();
            } catch (Exception e) {
                log.warn("Unknown target API: {}, defaulting to MEASUREMENT", mapping.getTargetAPI());
            }
        }

        return API.MEASUREMENT; // Default
    }
}