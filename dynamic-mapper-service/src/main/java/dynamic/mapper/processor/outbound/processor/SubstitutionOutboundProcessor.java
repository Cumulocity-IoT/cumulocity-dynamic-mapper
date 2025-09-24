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

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.camel.Exchange;
import org.apache.commons.lang3.mutable.MutableInt;
import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMethod;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.model.API;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.model.DynamicMapperRequest;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.RepairStrategy;
import dynamic.mapper.processor.model.SubstituteValue;
import dynamic.mapper.processor.model.SubstituteValue.TYPE;
import dynamic.mapper.service.MappingService;
import dynamic.mapper.util.Utils;
import lombok.extern.slf4j.Slf4j;

import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;

@Slf4j
@Component
public class SubstitutionOutboundProcessor extends BaseProcessor {

    @Autowired
    private C8YAgent c8yAgent;

    @Autowired
    private MappingService mappingService;

    @Override
    public void process(Exchange exchange) throws Exception {
        ProcessingContext<Object> context = exchange.getIn().getHeader("processingContext", ProcessingContext.class);
        Mapping mapping = context.getMapping();
        String tenant = context.getTenant();

        try {
            substituteInTargetAndCreateRequests(context);
        } catch (Exception e) {
            String errorMessage = String.format("Tenant %s - Error in substitution processor for mapping: %s",
                    tenant, mapping.name);
            log.error(errorMessage, e);
            context.addError(new ProcessingException("Substitution failed", e));
            MappingStatus mappingStatus = mappingService.getMappingStatus(tenant, mapping);
            context.addError(new ProcessingException(errorMessage, e));
            mappingStatus.errors++;
            mappingService.increaseAndHandleFailureCount(tenant, mapping, mappingStatus);
        }

    }

    /**
     * Perform substitution and create C8Y requests
     */
    private void substituteInTargetAndCreateRequests(ProcessingContext<Object> context) throws Exception {

        /*
         * step 3 replace target with extract content from outbound payload
         */
        Mapping mapping = context.getMapping();
        String tenant = context.getTenant();
        ServiceConfiguration serviceConfiguration = context.getServiceConfiguration();

        Map<String, List<SubstituteValue>> processingCache = context.getProcessingCache();
        Set<String> pathTargets = processingCache.keySet();

        int predecessor = -1;
        DocumentContext payloadTarget = JsonPath.parse(mapping.targetTemplate);
        /*
         * step 0 patch payload with dummy property _TOPIC_LEVEL_ in case the content
         * is required in the payload for a substitution
         */
        List<String> splitTopicExAsList = Mapping.splitTopicExcludingSeparatorAsList(context.getTopic(), false);
        payloadTarget.put("$", Mapping.TOKEN_TOPIC_LEVEL, splitTopicExAsList);
        if (mapping.supportsMessageContext) {
            Map<String, String> cod = new HashMap<String, String>() {
                {
                    put(Mapping.CONTEXT_DATA_KEY_NAME, "dummy");
                    put(Mapping.CONTEXT_DATA_METHOD_NAME, "POST");
                    put("publishTopic", mapping.getPublishTopic());
                }
            };
            payloadTarget.put("$", Mapping.TOKEN_CONTEXT_DATA, cod);
        }
        if (serviceConfiguration.logPayload || mapping.debug) {
            String patchedPayloadTarget = payloadTarget.jsonString();
            log.info("{} - Patched payload: {}", tenant, patchedPayloadTarget);
        }

        String deviceSource = context.getSourceId();

        for (String pathTarget : pathTargets) {
            SubstituteValue substitute = new SubstituteValue(
                    "NOT_DEFINED", TYPE.TEXTUAL,
                    RepairStrategy.DEFAULT, false);
            if (processingCache.get(pathTarget).size() > 0) {
                substitute = processingCache.get(pathTarget).get(0).clone();
            }
            SubstituteValue.substituteValueInPayload(substitute, payloadTarget, pathTarget);
        }

        /*
         * step 4 prepare target payload for sending to mqttBroker
         */
        if (Arrays.stream(API.values()).anyMatch(v -> mapping.targetAPI.equals(v))) {
            // if (!mapping.targetAPI.equals(API.INVENTORY)) {
            List<String> topicLevels = payloadTarget.read(Mapping.TOKEN_TOPIC_LEVEL);
            if (topicLevels != null && topicLevels.size() > 0) {
                // now merge the replaced topic levels
                MutableInt c = new MutableInt(0);
                // MutableInt index = new MutableInt(0);
                String[] splitTopicInAsList = Mapping.splitTopicIncludingSeparatorAsArray(context.getTopic());
                String[] splitTopicInAsListOriginal = Mapping.splitTopicIncludingSeparatorAsArray(context.getTopic());
                topicLevels.forEach(tl -> {
                    while (c.intValue() < splitTopicInAsList.length
                            && ("/".equals(splitTopicInAsList[c.intValue()]) && c.intValue() > 0)) {
                        c.increment();
                    }
                    splitTopicInAsList[c.intValue()] = tl;
                    c.increment();
                });
                if (context.getMapping().getDebug() || context.getServiceConfiguration().logPayload) {
                    log.info("{} - Resolved topic from {} to {}",
                            tenant, splitTopicInAsListOriginal, splitTopicInAsList);
                }

                StringBuffer resolvedPublishTopic = new StringBuffer();
                for (int d = 0; d < splitTopicInAsList.length; d++) {
                    resolvedPublishTopic.append(splitTopicInAsList[d]);
                }
                context.setResolvedPublishTopic(resolvedPublishTopic.toString());
            } else {
                context.setResolvedPublishTopic(context.getMapping().getPublishTopic());
            }

            // remove TOPIC_LEVEL
            payloadTarget.delete("$." + Mapping.TOKEN_TOPIC_LEVEL);
            RequestMethod method = RequestMethod.POST;
            if (mapping.supportsMessageContext) {
                String key = payloadTarget
                        .read(String.format("$.%s.%s", Mapping.TOKEN_CONTEXT_DATA, Mapping.CONTEXT_DATA_KEY_NAME));
                context.setKey(key);

                // extract method
                try {
                    String methodString = payloadTarget
                            .read(String.format("$.%s.%s", Mapping.TOKEN_CONTEXT_DATA,
                                    Mapping.CONTEXT_DATA_METHOD_NAME));
                    method = RequestMethod.resolve(methodString.toUpperCase());
                } catch (Exception e) {
                    // method is not defined or unknown, so we assume "POST"
                }
                try {
                    String publishTopic = payloadTarget
                            .read(String.format("$.%s.%s", Mapping.TOKEN_CONTEXT_DATA, "publishTopic"));
                    if (publishTopic != null && !publishTopic.equals(""))
                        context.setTopic(publishTopic);
                } catch (Exception e) {
                    // publishTopic is not defined or unknown, so we continue using the value
                    // defined in the mapping
                }
                // remove TOKEN_CONTEXT_DATA
                payloadTarget.delete("$." + Mapping.TOKEN_CONTEXT_DATA);
            }
            var newPredecessor = context.addRequest(
                    DynamicMapperRequest.builder()
                            .predecessor(predecessor)
                            .method(method)
                            .api(mapping.targetAPI) // Set the api field
                            .sourceId(deviceSource)
                            .externalIdType(mapping.externalIdType)
                            .externalId(context.getExternalId())
                            .request(payloadTarget.jsonString())
                            .build());
            // try {
            //     if (connectorClient.isConnected() && context.isSendPayload()) {
            //         connectorClient.publishMEAO(context);
            //     } else {
            //         log.warn("{} - Not sending message: connected {}, sendPayload {}", tenant,
            //                 connectorClient.isConnected(), context.isSendPayload());
            //     }
            //     // var response = objectMapper.writeValueAsString(adHocRequest);
            //     // context.getCurrentRequest().setResponse(response);
            // } catch (Exception e) {
            //     context.getCurrentRequest().setError(e);
            //     log.error("{} - Error during publishing outbound message: ", tenant, e);
            // }
            predecessor = newPredecessor;
        } else {
            // FIXME Why are INVENTORY API messages ignored?! Needs to be implemented
            log.warn("{} - Ignoring payload: {}, {}, {}", tenant, payloadTarget, mapping.targetAPI,
                    processingCache.size());
        }
        if (context.getMapping().getDebug() || context.getServiceConfiguration().logPayload) {
            log.info("{} - Transformed message sent: API: {}, numberDevices: {}, message: {}", tenant,
                    mapping.targetAPI,
                    payloadTarget.jsonString(),
                    1);
        }
        // Create alarms for messages reported during processing substitutions
        ManagedObjectRepresentation sourceMor = new ManagedObjectRepresentation();
        sourceMor.setId(new GId(context.getSourceId()));
        context.getAlarms()
                .forEach(alarm -> c8yAgent.createAlarm("WARNING", alarm, Utils.MAPPER_PROCESSING_ALARM, new DateTime(),
                        sourceMor, tenant));

    }

}
