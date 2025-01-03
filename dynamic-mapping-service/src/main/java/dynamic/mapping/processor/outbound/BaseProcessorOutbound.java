/*
 * Copyright (c) 2022 Software AG, Darmstadt, Germany and/or Software AG USA Inc., Reston, VA, USA,
 * and/or its subsidiaries and/or its affiliates and/or their licensors.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @authors Christof Strack, Stefan Witschel
 */

package dynamic.mapping.processor.outbound;

import static dynamic.mapping.model.MappingSubstitution.substituteValueInPayload;
import static dynamic.mapping.model.MappingSubstitution.toPrettyJsonString;
import static com.dashjoin.jsonata.Jsonata.jsonata;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.mutable.MutableInt;
import org.springframework.web.bind.annotation.RequestMethod;

import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;

import dynamic.mapping.configuration.ServiceConfiguration;
import dynamic.mapping.connector.core.client.AConnectorClient;
import dynamic.mapping.core.C8YAgent;
import dynamic.mapping.core.ConfigurationRegistry;
import dynamic.mapping.model.API;
import dynamic.mapping.model.Mapping;
import dynamic.mapping.model.MappingSubstitution;
import dynamic.mapping.model.MappingSubstitution.SubstituteValue.TYPE;
import dynamic.mapping.processor.C8YMessage;
import dynamic.mapping.processor.ProcessingException;
import dynamic.mapping.processor.model.C8YRequest;
import dynamic.mapping.processor.model.ProcessingContext;
import dynamic.mapping.processor.model.RepairStrategy;
import jakarta.validation.constraints.NotNull;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class BaseProcessorOutbound<T> {

    public BaseProcessorOutbound(ConfigurationRegistry configurationRegistry, AConnectorClient connectorClient) {
        this.connectorClient = connectorClient;
        this.c8yAgent = configurationRegistry.getC8yAgent();
    }

    protected C8YAgent c8yAgent;

    protected AConnectorClient connectorClient;

    public abstract T deserializePayload(Mapping mapping, C8YMessage c8yMessage)
            throws IOException;

    public abstract void extractFromSource(ProcessingContext<T> context) throws ProcessingException;

    public void enrichPayload(ProcessingContext<T> context) {

        /*
         * step 0 patch payload with dummy property _IDENTITY_ in case the content
         * is required in the payload for a substitution
         */
        String tenant = context.getTenant();
        Object payloadObject = context.getPayload();
        Mapping mapping = context.getMapping();
        String payloadAsString = toPrettyJsonString(payloadObject);
        var sourceId = extractContent(context, mapping, payloadObject, payloadAsString,
                mapping.targetAPI.identifier);
        context.setSourceId(sourceId.toString());
        Map<String, String> identityFragment = new HashMap<>();
        identityFragment.put("c8ySourceId", sourceId.toString());
        identityFragment.put("externalIdType", mapping.externalIdType);
        if (mapping.useExternalId && !("").equals(mapping.externalIdType)) {
            ExternalIDRepresentation externalId = c8yAgent.resolveGlobalId2ExternalId(context.getTenant(),
                    new GId(sourceId.toString()), mapping.externalIdType,
                    context);
            if (externalId == null) {
                if (context.isSendPayload()) {
                    throw new RuntimeException(String.format("External id %s for type %s not found!",
                            sourceId.toString(), mapping.externalIdType));
                }
            } else {
                identityFragment.put("externalId", externalId.getExternalId());
            }
        }
        if (payloadObject instanceof Map) {
            ((Map) payloadObject).put(Mapping.IDENTITY, identityFragment);
        } else {
            log.warn("Tenant {} - Parsing this message as JSONArray, no elements from the topic level can be used!",
                    tenant);
        }
    }

    public ProcessingContext<T> substituteInTargetAndSend(ProcessingContext<T> context) {
        /*
         * step 3 replace target with extract content from outbound payload
         */
        Mapping mapping = context.getMapping();
        String tenant = context.getTenant();
        ServiceConfiguration serviceConfiguration = context.getServiceConfiguration();

        Map<String, List<MappingSubstitution.SubstituteValue>> processingCache = context.getProcessingCache();
        Set<String> pathTargets = processingCache.keySet();

        int predecessor = -1;
        DocumentContext payloadTarget = JsonPath.parse(mapping.targetTemplate);
        /*
         * step 0 patch payload with dummy property _TOPIC_LEVEL_ in case the content
         * is required in the payload for a substitution
         */
        List<String> splitTopicExAsList = Mapping.splitTopicExcludingSeparatorAsList(context.getTopic());
        payloadTarget.put("$", Mapping.TOKEN_TOPIC_LEVEL, splitTopicExAsList);
        if (mapping.supportsMessageContext) {
            Map<String, String> cod = new HashMap<String, String>() {
                {
                    put(Mapping.CONTEXT_DATA_KEY_NAME, "dummy");
                }
            };
            payloadTarget.put("$", Mapping.TOKEN_CONTEXT_DATA, cod);
        }
        if (serviceConfiguration.logPayload || mapping.debug) {
            String patchedPayloadTarget = payloadTarget.jsonString();
            log.info("Tenant {} - Patched payload: {} {} {} {}", tenant, patchedPayloadTarget,
                    serviceConfiguration.logPayload, mapping.debug, serviceConfiguration.logPayload || mapping.debug);
        }

        String deviceSource = context.getSourceId();

        for (String pathTarget : pathTargets) {
            MappingSubstitution.SubstituteValue substitute = new MappingSubstitution.SubstituteValue(
                    "NOT_DEFINED", TYPE.TEXTUAL,
                    RepairStrategy.DEFAULT);
            if (processingCache.get(pathTarget).size() > 0) {
                substitute = processingCache.get(pathTarget).get(0).clone();
            }
            substituteValueInPayload(substitute, payloadTarget, pathTarget);
        }
        /*
         * step 4 prepare target payload for sending to mqttBroker
         */
        if (!mapping.targetAPI.equals(API.INVENTORY)) {
            List<String> topicLevels = payloadTarget.read(Mapping.TOKEN_TOPIC_LEVEL);
            if (topicLevels != null && topicLevels.size() > 0) {
                // now merge the replaced topic levels
                MutableInt c = new MutableInt(0);
                String[] splitTopicInAsList = Mapping.splitTopicIncludingSeparatorAsArray(context.getTopic());
                topicLevels.forEach(tl -> {
                    while (c.intValue() < splitTopicInAsList.length
                            && ("/".equals(splitTopicInAsList[c.intValue()]))) {
                        c.increment();
                    }
                    splitTopicInAsList[c.intValue()] = tl;
                    c.increment();
                });

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
            if (mapping.supportsMessageContext) {
                String key = payloadTarget
                        .read(String.format("$.%s.%s", Mapping.TOKEN_CONTEXT_DATA, Mapping.CONTEXT_DATA_KEY_NAME));
                context.setKey(key.getBytes());
                // remove TOKEN_CONTEXT_DATA
                payloadTarget.delete("$." + Mapping.TOKEN_CONTEXT_DATA);
            }
            var newPredecessor = context.addRequest(
                    new C8YRequest(predecessor, RequestMethod.POST, deviceSource, mapping.externalIdType,
                            payloadTarget.jsonString(),
                            null, mapping.targetAPI, null));
            try {
                if (connectorClient.isConnected() && context.isSendPayload()) {
                    connectorClient.publishMEAO(context);
                } else {
                    log.warn("Tenant {} - Not sending message: connected {}, sendPayload {}", tenant,
                            connectorClient.isConnected(), context.isSendPayload());
                }
                // var response = objectMapper.writeValueAsString(attocRequest);
                // context.getCurrentRequest().setResponse(response);
            } catch (Exception e) {
                context.getCurrentRequest().setError(e);
                log.error("Tenant {} - Error during publishing outbound message: ", tenant, e);
            }
            predecessor = newPredecessor;
        } else {
            log.warn("Tenant {} - Ignoring payload: {}, {}, {}", tenant, payloadTarget, mapping.targetAPI,
                    processingCache.size());
        }
        log.debug("Tenant {} - Added payload for sending: {}, {}, numberDevices: {}", tenant, payloadTarget,
                mapping.targetAPI,
                1);
        return context;
    }

    protected Object extractContent(ProcessingContext<T> context, Mapping mapping, Object payloadJsonNode,
            String payloadAsString, @NotNull String ps) {
        Object extractedSourceContent = null;
        try {
            var expr = jsonata(mapping.transformGenericPath2C8YPath(ps));
            extractedSourceContent = expr.evaluate(payloadJsonNode);
        } catch (Exception e) {
            log.error("Tenant {} - EvaluateRuntimeException for: {}, {}: ", context.getTenant(),
                    ps,
                    payloadAsString, e);
        }
        return extractedSourceContent;
    }
}