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

import static com.dashjoin.jsonata.Jsonata.jsonata;
import static dynamic.mapping.model.Mapping.findDeviceIdentifier;
import static dynamic.mapping.model.MappingSubstitution.isArray;
import static dynamic.mapping.model.MappingSubstitution.isNumber;
import static dynamic.mapping.model.MappingSubstitution.isTextual;
import static dynamic.mapping.model.MappingSubstitution.toPrettyJsonString;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.dashjoin.jsonata.json.Json;
import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;

import dynamic.mapping.configuration.ServiceConfiguration;
import dynamic.mapping.connector.core.client.AConnectorClient;
import dynamic.mapping.core.ConfigurationRegistry;
import dynamic.mapping.model.Mapping;
import dynamic.mapping.model.MappingSubstitution;
import dynamic.mapping.processor.C8YMessage;
import dynamic.mapping.processor.ProcessingException;
import dynamic.mapping.processor.model.ProcessingContext;
import jakarta.validation.constraints.NotNull;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class JSONProcessorOutbound extends BasePayloadProcessorOutbound<Object> {

    public JSONProcessorOutbound(ConfigurationRegistry configurationRegistry, AConnectorClient connectorClient) {
        super(configurationRegistry, connectorClient);
    }

    @Override
    public ProcessingContext<Object> deserializePayload(Mapping mapping,
            C8YMessage c8yMessage) throws IOException {
        Object jsonNode = Json.parseJson(c8yMessage.getPayload());
        ProcessingContext<Object> context = new ProcessingContext<Object>();
        context.setPayload(jsonNode);
        return context;
    }

    @Override
    public void extractFromSource(ProcessingContext<Object> context)
            throws ProcessingException {
        Mapping mapping = context.getMapping();
        String tenant = context.getTenant();
        ServiceConfiguration serviceConfiguration = context.getServiceConfiguration();

        Object payloadObjectNode = context.getPayload();

        Map<String, List<MappingSubstitution.SubstituteValue>> postProcessingCache = context.getPostProcessingCache();
        String payloadAsString = toPrettyJsonString(payloadObjectNode);
        /*
         * step 0 patch payload with dummy property _IDENTITY_ in case the content
         * is required in the payload for a substitution
         */
        var sourceId = extractContent(context, mapping, payloadObjectNode, payloadAsString, mapping.targetAPI.identifier);
        Map identityFragment = Map.of("c8ySourceId",
                sourceId.toString(), "externalIdType", mapping.externalIdType);
        if (mapping.externalIdType != null && !("").equals(mapping.externalIdType)) {
            ExternalIDRepresentation externalId = c8yAgent.resolveGlobalId2ExternalId(context.getTenant(),
                    new GId(sourceId.toString()), mapping.externalIdType,
                    context);
    
            identityFragment.put("externalId", externalId.getExternalId());
        }
        if (payloadObjectNode instanceof Map) {
            ((Map) payloadObjectNode).put(Mapping.IDENTITY, identityFragment);
        } else {
            log.warn("Tenant {} - Parsing this message as JSONArray, no elements from the topic level can be used!",
                    tenant);
        }

        payloadAsString = toPrettyJsonString(payloadObjectNode);
        if (serviceConfiguration.logPayload || mapping.debug) {
            log.info("Tenant {} - Incoming payload (patched) in extractFromSource(): {} {} {} {}", tenant, payloadAsString,
                    serviceConfiguration.logPayload, mapping.debug, serviceConfiguration.logPayload || mapping.debug);
        }

        for (MappingSubstitution substitution : mapping.substitutions) {
            Object extractedSourceContent = null;

            /*
             * step 1 extract content from inbound payload
             */
            var ps = substitution.pathSource;
            extractedSourceContent = extractContent(context, mapping, payloadObjectNode, payloadAsString, ps);
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
                if (isArray(extractedSourceContent)) {
                    if (substitution.expandArray) {
                        Collection extractedSourceContentCollection = (Collection)extractedSourceContent;
                        // extracted result from sourcePayload is an array, so we potentially have to
                        // iterate over the result, e.g. creating multiple devices
                        for (Object jn : extractedSourceContentCollection) {
                            if (isTextual(jn)) {
                                postProcessingCacheEntry
                                        .add(new MappingSubstitution.SubstituteValue(jn,
                                                MappingSubstitution.SubstituteValue.TYPE.TEXTUAL,
                                                substitution.repairStrategy));
                            } else if (isNumber(jn)) {
                                postProcessingCacheEntry
                                        .add(new MappingSubstitution.SubstituteValue(jn,
                                                MappingSubstitution.SubstituteValue.TYPE.NUMBER,
                                                substitution.repairStrategy));
                            } else {
                                log.warn("Tenant {} - Since result is not textual or number it is ignored: {}",
                                        context.getTenant(),
                                        jn);
                            }
                        }
                        context.addCardinality(substitution.pathTarget, extractedSourceContentCollection.size());
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
                } else if (isTextual(extractedSourceContent)) {
                    if (ps.equals(findDeviceIdentifier(mapping).pathSource)) {
                        log.debug("Tenant {} - Finding external Id: resolveGlobalId2ExternalId: {}, {}, {}",
                                context.getTenant(), ps, toPrettyJsonString(extractedSourceContent),
                                extractedSourceContent);
                        ExternalIDRepresentation externalId = c8yAgent.resolveGlobalId2ExternalId(context.getTenant(),
                                new GId(extractedSourceContent.toString()), mapping.externalIdType,
                                context);
                        if (externalId == null && context.isSendPayload()) {
                            throw new RuntimeException(String.format("External id %s for type %s not found!",
                                    extractedSourceContent, mapping.externalIdType));
                        } else if (externalId == null) {
                            extractedSourceContent = "_UNKNOWN_";
                        } else {
                            extractedSourceContent = externalId.getExternalId();
                        }
                    }
                    context.addCardinality(substitution.pathTarget, 1);
                    postProcessingCacheEntry.add(
                            new MappingSubstitution.SubstituteValue(extractedSourceContent,
                                    MappingSubstitution.SubstituteValue.TYPE.TEXTUAL, substitution.repairStrategy));
                    postProcessingCache.put(substitution.pathTarget, postProcessingCacheEntry);
                    context.setSource(extractedSourceContent.toString());
                } else if (isNumber(extractedSourceContent)) {
                    context.addCardinality(substitution.pathTarget, 1);
                    postProcessingCacheEntry
                            .add(new MappingSubstitution.SubstituteValue(extractedSourceContent,
                                    MappingSubstitution.SubstituteValue.TYPE.NUMBER, substitution.repairStrategy));
                    postProcessingCache.put(substitution.pathTarget, postProcessingCacheEntry);
                } else {
                    log.debug("Tenant {} - This substitution, involves an objects for: {}, {}", context.getTenant(),
                            substitution.pathSource, extractedSourceContent.toString());
                    context.addCardinality(substitution.pathTarget, 1);
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

    private Object extractContent(ProcessingContext<Object> context, Mapping mapping, Object payloadJsonNode,
            String payloadAsString, @NotNull String ps) {
        Object extractedSourceContent = null;
        try {
            var expr = jsonata(mapping.transformGenericPath2C8YPath(ps));
            extractedSourceContent = expr.evaluate(payloadJsonNode);
        }  catch (Exception e) {
            log.error("Tenant {} - EvaluateRuntimeException for: {}, {}: ", context.getTenant(),
                    ps,
                    payloadAsString, e);
        }
        return extractedSourceContent;
    }

}