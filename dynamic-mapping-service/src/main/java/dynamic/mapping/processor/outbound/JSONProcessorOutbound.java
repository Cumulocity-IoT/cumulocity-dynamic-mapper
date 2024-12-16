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

import static dynamic.mapping.model.Mapping.findDeviceIdentifier;

import com.api.jsonata4java.expressions.EvaluateException;
import com.api.jsonata4java.expressions.EvaluateRuntimeException;
import com.api.jsonata4java.expressions.Expressions;
import com.api.jsonata4java.expressions.ParseException;
import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import dynamic.mapping.model.Mapping;
import dynamic.mapping.model.MappingSubstitution;
import lombok.extern.slf4j.Slf4j;
import dynamic.mapping.configuration.ServiceConfiguration;
import dynamic.mapping.connector.core.client.AConnectorClient;
import dynamic.mapping.core.ConfigurationRegistry;
import dynamic.mapping.processor.C8YMessage;
import dynamic.mapping.processor.ProcessingException;
import dynamic.mapping.processor.model.ProcessingContext;
import jakarta.validation.constraints.NotNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
public class JSONProcessorOutbound extends BasePayloadProcessorOutbound<JsonNode> {

    public JSONProcessorOutbound(ConfigurationRegistry configurationRegistry, AConnectorClient connectorClient) {
        super(configurationRegistry, connectorClient);
    }

    @Override
    public ProcessingContext<JsonNode> deserializePayload(Mapping mapping,
            C8YMessage c8yMessage) throws IOException {
        JsonNode jsonNode = objectMapper.readTree(c8yMessage.getPayload());
        ProcessingContext<JsonNode> context = new ProcessingContext<JsonNode>();
        context.setPayload(jsonNode);
        return context;
    }

    @Override
    public void extractFromSource(ProcessingContext<JsonNode> context)
            throws ProcessingException {
        Mapping mapping = context.getMapping();
        String tenant = context.getTenant();
        ServiceConfiguration serviceConfiguration = context.getServiceConfiguration();

        JsonNode payloadJsonNode = context.getPayload();

        Map<String, List<MappingSubstitution.SubstituteValue>> postProcessingCache = context.getPostProcessingCache();
        String payloadAsString = payloadJsonNode.toPrettyString();
        /*
         * step 0 patch payload with dummy property _IDENTITY_ in case the content
         * is required in the payload for a substitution
         */
        ObjectNode identityFragment = objectMapper.createObjectNode();
        var sourceId = extractContent(context, mapping, payloadJsonNode, payloadAsString, mapping.targetAPI.identifier);
        identityFragment.set("c8ySourceId",
                sourceId);
        identityFragment.set("externalIdType", TextNode.valueOf(mapping.externalIdType));
        if (mapping.externalIdType != null && !("").equals(mapping.externalIdType)) {
            ExternalIDRepresentation externalId = c8yAgent.resolveGlobalId2ExternalId(context.getTenant(),
                    new GId(sourceId.textValue()), mapping.externalIdType,
                    context);
            identityFragment.set("externalId", new TextNode(externalId.getExternalId()));
        }
        if (payloadJsonNode instanceof ObjectNode) {
            ((ObjectNode) payloadJsonNode).set(Mapping.IDENTITY, identityFragment);
        } else {
            log.warn("Tenant {} - Parsing this message as JSONArray, no elements from the topic level can be used!",
                    tenant);
        }

        payloadAsString = payloadJsonNode.toPrettyString();
        if (serviceConfiguration.logPayload || mapping.debug) {
            log.info("Tenant {} - Incoming payload (patched) in extractFromSource(): {} {} {} {}", tenant, payloadAsString,
                    serviceConfiguration.logPayload, mapping.debug, serviceConfiguration.logPayload || mapping.debug);
        }

        for (MappingSubstitution substitution : mapping.substitutions) {
            JsonNode extractedSourceContent = null;

            /*
             * step 1 extract content from inbound payload
             */
            var ps = substitution.pathSource;
            extractedSourceContent = extractContent(context, mapping, payloadJsonNode, payloadAsString, ps);
            /*
             * step 2 analyse extracted content: textual, array
             */
            List<MappingSubstitution.SubstituteValue> postProcessingCacheEntry = postProcessingCache.getOrDefault(
                    substitution.pathTarget,
                    new ArrayList<MappingSubstitution.SubstituteValue>());
            if (extractedSourceContent == null) {
                log.error("Tenant {} - No substitution for: {}, {}", context.getTenant(), substitution.pathSource,
                        payloadAsString);
                postProcessingCacheEntry
                        .add(new MappingSubstitution.SubstituteValue(extractedSourceContent,
                                MappingSubstitution.SubstituteValue.TYPE.IGNORE, substitution.repairStrategy));
                postProcessingCache.put(substitution.pathTarget, postProcessingCacheEntry);
            } else {
                if (extractedSourceContent.isArray()) {
                    if (substitution.expandArray) {
                        // extracted result from sourcePayload is an array, so we potentially have to
                        // iterate over the result, e.g. creating multiple devices
                        for (JsonNode jn : extractedSourceContent) {
                            if (jn.isTextual()) {
                                postProcessingCacheEntry
                                        .add(new MappingSubstitution.SubstituteValue(jn,
                                                MappingSubstitution.SubstituteValue.TYPE.TEXTUAL,
                                                substitution.repairStrategy));
                            } else if (jn.isNumber()) {
                                postProcessingCacheEntry
                                        .add(new MappingSubstitution.SubstituteValue(jn,
                                                MappingSubstitution.SubstituteValue.TYPE.NUMBER,
                                                substitution.repairStrategy));
                            } else {
                                log.warn("Tenant {} - Since result is not textual or number it is ignored: {}",
                                        context.getTenant(),
                                        jn.asText());
                            }
                        }
                        context.addCardinality(substitution.pathTarget, extractedSourceContent.size());
                        postProcessingCache.put(substitution.pathTarget, postProcessingCacheEntry);
                    } else {
                        // treat this extracted enry as single value, no MULTI_VALUE or MULTI_DEVICE
                        // substitution
                        context.addCardinality(substitution.pathTarget, 1);
                        postProcessingCacheEntry
                                .add(new MappingSubstitution.SubstituteValue(extractedSourceContent,
                                        MappingSubstitution.SubstituteValue.TYPE.ARRAY,
                                        substitution.repairStrategy));
                        postProcessingCache.put(substitution.pathTarget, postProcessingCacheEntry);
                    }
                } else if (extractedSourceContent.isTextual()) {
                    if (ps.equals(findDeviceIdentifier(mapping).pathSource)) {
                        log.debug("Tenant {} - Finding external Id: resolveGlobalId2ExternalId: {}, {}, {}",
                                context.getTenant(), ps, extractedSourceContent.toPrettyString(),
                                extractedSourceContent.asText());
                        ExternalIDRepresentation externalId = c8yAgent.resolveGlobalId2ExternalId(context.getTenant(),
                                new GId(extractedSourceContent.asText()), mapping.externalIdType,
                                context);
                        if (externalId == null && context.isSendPayload()) {
                            throw new RuntimeException(String.format("External id %s for type %s not found!",
                                    extractedSourceContent.asText(), mapping.externalIdType));
                        } else if (externalId == null) {
                            extractedSourceContent = null;
                        } else {
                            extractedSourceContent = new TextNode(externalId.getExternalId());
                        }
                    }
                    context.addCardinality(substitution.pathTarget, extractedSourceContent.size());
                    postProcessingCacheEntry.add(
                            new MappingSubstitution.SubstituteValue(extractedSourceContent,
                                    MappingSubstitution.SubstituteValue.TYPE.TEXTUAL, substitution.repairStrategy));
                    postProcessingCache.put(substitution.pathTarget, postProcessingCacheEntry);
                    context.setSource(extractedSourceContent.asText());
                } else if (extractedSourceContent.isNumber()) {
                    context.addCardinality(substitution.pathTarget, extractedSourceContent.size());
                    postProcessingCacheEntry
                            .add(new MappingSubstitution.SubstituteValue(extractedSourceContent,
                                    MappingSubstitution.SubstituteValue.TYPE.NUMBER, substitution.repairStrategy));
                    postProcessingCache.put(substitution.pathTarget, postProcessingCacheEntry);
                } else {
                    log.debug("Tenant {} - This substitution, involves an objects for: {}, {}", context.getTenant(),
                            substitution.pathSource, extractedSourceContent.toString());
                    context.addCardinality(substitution.pathTarget, extractedSourceContent.size());
                    postProcessingCacheEntry
                            .add(new MappingSubstitution.SubstituteValue(extractedSourceContent,
                                    MappingSubstitution.SubstituteValue.TYPE.OBJECT, substitution.repairStrategy));
                    postProcessingCache.put(substitution.pathTarget, postProcessingCacheEntry);
                }
                if (context.getServiceConfiguration().logSubstitution || mapping.debug) {
                    log.debug("Tenant {} - Evaluated substitution (pathSource:substitute)/({}:{}), (pathTarget)/({})",
                            context.getTenant(),
                            substitution.pathSource, extractedSourceContent.toString(), substitution.pathTarget);
                }
            }

        }

    }

    private JsonNode extractContent(ProcessingContext<JsonNode> context, Mapping mapping, JsonNode payloadJsonNode,
            String payloadAsString, @NotNull String ps) {
        JsonNode extractedSourceContent = null;
        try {
            Expressions expr = Expressions.parse(mapping.transformGenericPath2C8YPath(ps));
            extractedSourceContent = expr.evaluate(payloadJsonNode);
        } catch (ParseException | IOException | EvaluateException e) {
            log.error("Tenant {} - Exception for: {}, {}: ", context.getTenant(), ps,
                    payloadAsString, e);
        } catch (EvaluateRuntimeException e) {
            log.error("Tenant {} - EvaluateRuntimeException for: {}, {}: ", context.getTenant(),
                    ps,
                    payloadAsString, e);
        }
        return extractedSourceContent;
    }

}