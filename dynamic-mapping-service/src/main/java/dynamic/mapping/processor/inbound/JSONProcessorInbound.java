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

package dynamic.mapping.processor.inbound;

import com.api.jsonata4java.expressions.EvaluateException;
import com.api.jsonata4java.expressions.EvaluateRuntimeException;
import com.api.jsonata4java.expressions.Expressions;
import com.api.jsonata4java.expressions.ParseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import dynamic.mapping.model.Mapping;
import dynamic.mapping.model.MappingSubstitution;
import lombok.extern.slf4j.Slf4j;
import dynamic.mapping.configuration.ServiceConfiguration;
import dynamic.mapping.connector.core.callback.ConnectorMessage;
import dynamic.mapping.core.ConfigurationRegistry;
import dynamic.mapping.model.API;
import dynamic.mapping.processor.ProcessingException;
import dynamic.mapping.processor.model.ProcessingContext;
import dynamic.mapping.processor.model.RepairStrategy;
import org.joda.time.DateTime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
public class JSONProcessorInbound extends BasePayloadProcessorInbound<JsonNode> {

	public JSONProcessorInbound(ConfigurationRegistry configurationRegistry) {
		super(configurationRegistry);
	}

	@Override
	public ProcessingContext<JsonNode> deserializePayload(ProcessingContext<JsonNode> context,
			ConnectorMessage message) throws IOException {
		JsonNode jsonNode = objectMapper.readTree(message.getPayload());
		context.setPayload(jsonNode);
		return context;
	}

	@Override
	public void extractFromSource(ProcessingContext<JsonNode> context)
			throws ProcessingException {
		String tenant = context.getTenant();
		Mapping mapping = context.getMapping();
		ServiceConfiguration serviceConfiguration = context.getServiceConfiguration();

		JsonNode payloadJsonNode = context.getPayload();
		Map<String, List<MappingSubstitution.SubstituteValue>> postProcessingCache = context.getPostProcessingCache();

		/*
		 * step 0 patch payload with dummy property _TOPIC_LEVEL_ in case the content
		 * is required in the payload for a substitution
		 */
		ArrayNode topicLevels = objectMapper.createArrayNode();
		List<String> splitTopicAsList = Mapping.splitTopicExcludingSeparatorAsList(context.getTopic());
		splitTopicAsList.forEach(s -> topicLevels.add(s));
		if (payloadJsonNode instanceof ObjectNode) {
			((ObjectNode) payloadJsonNode).set(Mapping.TOKEN_TOPIC_LEVEL, topicLevels);
			if (context.isSupportsMessageContext() && context.getKey() != null) {
				ObjectNode contextData = objectMapper.createObjectNode();
				String keyString = new String(context.getKey(), StandardCharsets.UTF_8);
				contextData.put(Mapping.CONTEXT_DATA_KEY_NAME, keyString);
				((ObjectNode) payloadJsonNode).set(Mapping.TOKEN_CONTEXT_DATA, contextData);
			}
		} else {
			log.warn("Tenant {} - Parsing this message as JSONArray, no elements from the topic level can be used!",
					tenant);
		}

		String payload = payloadJsonNode.toPrettyString();
		if (serviceConfiguration.logPayload || mapping.debug) {
			log.info("Tenant {} - Patched payload: {} {} {} {}", tenant, payload, serviceConfiguration.logPayload,
					mapping.debug, serviceConfiguration.logPayload || mapping.debug);
		}

		boolean substitutionTimeExists = false;
		for (MappingSubstitution substitution : mapping.substitutions) {
			JsonNode extractedSourceContent = null;
			/*
			 * step 1 extract content from inbound payload
			 */
			try {
				Expressions expr = Expressions.parse(substitution.pathSource);
				extractedSourceContent = expr.evaluate(payloadJsonNode);
			} catch (ParseException | IOException | EvaluateException e) {
				log.error("Tenant {} - Exception for: {}, {}: ", tenant, substitution.pathSource,
						payload, e);
			} catch (EvaluateRuntimeException e) {
				log.error("Tenant {} - EvaluateRuntimeException for: {}, {}: ", tenant, substitution.pathSource,
						payload, e);
			} catch (Exception e) {
				log.error("Tenant {} - Exception for: {}, {}: ", tenant, substitution.pathSource,
						payload, e);
			}
			/*
			 * step 2 analyse extracted content: textual, array
			 */
			List<MappingSubstitution.SubstituteValue> postProcessingCacheEntry = postProcessingCache.getOrDefault(
					substitution.pathTarget,
					new ArrayList<MappingSubstitution.SubstituteValue>());
			if (extractedSourceContent == null) {
				log.warn("Tenant {} - Substitution {} not in message payload. Check your mapping {}", tenant,
						substitution.pathSource, mapping.getMappingTopic());
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
							} else if (jn.isArray()) {
								postProcessingCacheEntry
										.add(new MappingSubstitution.SubstituteValue(jn,
												MappingSubstitution.SubstituteValue.TYPE.ARRAY,
												substitution.repairStrategy));
							} else {
								// log.warn("Tenant {} - Since result is not textual or number it is ignored:
								// {}", tenant
								// jn.asText());
								postProcessingCacheEntry
										.add(new MappingSubstitution.SubstituteValue(jn,
												MappingSubstitution.SubstituteValue.TYPE.OBJECT,
												substitution.repairStrategy));
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
					context.addCardinality(substitution.pathTarget, extractedSourceContent.size());
					postProcessingCacheEntry.add(
							new MappingSubstitution.SubstituteValue(extractedSourceContent,
									MappingSubstitution.SubstituteValue.TYPE.TEXTUAL, substitution.repairStrategy));
					postProcessingCache.put(substitution.pathTarget, postProcessingCacheEntry);
				} else if (extractedSourceContent.isNumber()) {
					context.addCardinality(substitution.pathTarget, extractedSourceContent.size());
					postProcessingCacheEntry
							.add(new MappingSubstitution.SubstituteValue(extractedSourceContent,
									MappingSubstitution.SubstituteValue.TYPE.NUMBER, substitution.repairStrategy));
					postProcessingCache.put(substitution.pathTarget, postProcessingCacheEntry);
				} else {
					if (serviceConfiguration.logSubstitution || mapping.debug) {
						log.info("Tenant {} - This substitution, involves an objects for: {}, {}", tenant,
								substitution.pathSource, extractedSourceContent.toString());
					}
					context.addCardinality(substitution.pathTarget, extractedSourceContent.size());
					postProcessingCacheEntry
							.add(new MappingSubstitution.SubstituteValue(extractedSourceContent,
									MappingSubstitution.SubstituteValue.TYPE.OBJECT, substitution.repairStrategy));
					postProcessingCache.put(substitution.pathTarget, postProcessingCacheEntry);
				}
				if (serviceConfiguration.logSubstitution || mapping.debug) {
					log.info("Tenant {} - Evaluated substitution (pathSource:substitute)/({}:{}), (pathTarget)/({})",
							tenant,
							substitution.pathSource, extractedSourceContent.toString(), substitution.pathTarget);
				}
			}

			if (substitution.pathTarget.equals(Mapping.TIME)) {
				substitutionTimeExists = true;
			}
		}

		// no substitution for the time property exists, then use the system time
		if (!substitutionTimeExists && mapping.targetAPI != API.INVENTORY && mapping.targetAPI != API.OPERATION) {
			List<MappingSubstitution.SubstituteValue> postProcessingCacheEntry = postProcessingCache.getOrDefault(
					Mapping.TIME,
					new ArrayList<MappingSubstitution.SubstituteValue>());
			postProcessingCacheEntry.add(
					new MappingSubstitution.SubstituteValue(new TextNode(new DateTime().toString()),
							MappingSubstitution.SubstituteValue.TYPE.TEXTUAL, RepairStrategy.DEFAULT));
			postProcessingCache.put(Mapping.TIME, postProcessingCacheEntry);
		}
	}

}