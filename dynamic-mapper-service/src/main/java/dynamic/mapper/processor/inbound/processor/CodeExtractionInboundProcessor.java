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

import static dynamic.mapper.model.Substitution.toPrettyJsonString;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.camel.Exchange;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.dashjoin.jsonata.Functions;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.model.API;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.flow.JavaScriptInteropHelper;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.RepairStrategy;
import dynamic.mapper.processor.model.SubstituteValue;
import dynamic.mapper.processor.model.SubstituteValue.TYPE;
import dynamic.mapper.processor.model.SubstitutionContext;
import dynamic.mapper.processor.model.SubstitutionEvaluation;
import dynamic.mapper.processor.model.SubstitutionResult;
import dynamic.mapper.service.MappingService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class CodeExtractionInboundProcessor extends BaseProcessor {

    @Autowired
    private MappingService mappingService;

    @Override
    public void process(Exchange exchange) throws Exception {
        ProcessingContext<?> context = exchange.getIn().getHeader("processingContext", ProcessingContext.class);
        Mapping mapping = context.getMapping();
        String tenant = context.getTenant();
        Boolean testing = context.isTesting();

        try {
            extractFromSource(context);
        } catch (Exception e) {
            int lineNumber = 0;
            if (e.getStackTrace().length > 0) {
                lineNumber = e.getStackTrace()[0].getLineNumber();
            }
            String errorMessage = String.format(
                    "%s - Error in CodeExtractionInboundProcessor: %s for mapping: %s, line %s",
                    tenant, mapping.getName(), e.getMessage(), lineNumber);
            log.error(errorMessage, e);
            if (e instanceof ProcessingException)
                context.addError((ProcessingException) e);
            else
                context.addError(new ProcessingException(errorMessage, e));

            if (!testing) {
                MappingStatus mappingStatus = mappingService.getMappingStatus(tenant, mapping);
                mappingStatus.errors++;
                mappingService.increaseAndHandleFailureCount(tenant, mapping, mappingStatus);
            }
        } finally {
            // Close the Context completely
            if (context != null && context.getGraalContext() != null) {
                try {
                    Context graalContext = context.getGraalContext();
                    graalContext.close();
                    context.setGraalContext(null);
                    log.debug("{} - GraalVM Context closed successfully", tenant);
                } catch (Exception e) {
                    log.warn("{} - Error closing GraalVM context: {}", tenant, e.getMessage());
                }
            }
        }
    }

    public void extractFromSource(ProcessingContext<?> context) throws ProcessingException {
        Value result = null;
        Value sourceValue = null;
        Value bindings = null;
        
        try {
            String tenant = context.getTenant();
            Mapping mapping = context.getMapping();
            ServiceConfiguration serviceConfiguration = context.getServiceConfiguration();

            Object payloadObject = context.getPayload();
            Map<String, List<SubstituteValue>> processingCache = context.getProcessingCache();

            String payload = toPrettyJsonString(payloadObject);
            if (serviceConfiguration.isLogPayload() || mapping.getDebug()) {
                log.info("{} - Processing payload for extraction: {}", tenant, payload);
            }

            boolean substitutionTimeExists = false;

            if (mapping.getCode() != null) {
                Context graalContext = context.getGraalContext();

                String identifier = Mapping.EXTRACT_FROM_SOURCE + "_" + mapping.getIdentifier();
                bindings = graalContext.getBindings("js");

                // Load main code
                byte[] decodedBytes = Base64.getDecoder().decode(mapping.getCode());
                String decodedCode = new String(decodedBytes);
                String decodedCodeAdapted = decodedCode.replaceFirst(
                        Mapping.EXTRACT_FROM_SOURCE,
                        identifier);
                Source source = Source.newBuilder("js", decodedCodeAdapted, identifier + ".js")
                        .buildLiteral();
                graalContext.eval(source);
                sourceValue = bindings.getMember(identifier);

                // Load shared code if available
                if (context.getSharedCode() != null) {
                    byte[] decodedSharedCodeBytes = Base64.getDecoder().decode(context.getSharedCode());
                    String decodedSharedCode = new String(decodedSharedCodeBytes);
                    Source sharedSource = Source.newBuilder("js", decodedSharedCode, "sharedCode.js")
                            .buildLiteral();
                    graalContext.eval(sharedSource);
                }

                // Load system code if available
                if (context.getSystemCode() != null) {
                    byte[] decodedSystemCodeBytes = Base64.getDecoder().decode(context.getSystemCode());
                    String decodedSystemCode = new String(decodedSystemCodeBytes);
                    Source systemSource = Source.newBuilder("js", decodedSystemCode, "systemCode.js")
                            .buildLiteral();
                    graalContext.eval(systemSource);
                }

                @SuppressWarnings("unchecked")
                Map<String, Object> jsonObject = (Map<String, Object>) context.getPayload();
                String payloadAsString = Functions.string(context.getPayload(), false);

                // Add topic levels as metadata
                List<String> splitTopicAsList = Mapping.splitTopicExcludingSeparatorAsList(context.getTopic(), false);
                jsonObject.put(Mapping.TOKEN_TOPIC_LEVEL, splitTopicAsList);
                
                Map<String, String> contextData = new HashMap<String, String>() {{
                    put("api", mapping.getTargetAPI().toString());
                }};
                jsonObject.put(Mapping.TOKEN_CONTEXT_DATA, contextData);

                // Execute JavaScript function
                result = sourceValue.execute(
                    new SubstitutionContext(
                        context.getMapping().getGenericDeviceIdentifier(),
                        payloadAsString, 
                        context.getTopic()
                    )
                );

                // CRITICAL: Convert with Context still open
                SubstitutionResult typedResult = deepConvertSubstitutionResultWithContext(
                    result, 
                    graalContext,
                    tenant
                );

                // Process the converted result
                if (typedResult == null || typedResult.substitutions == null || typedResult.substitutions.isEmpty()) {
                    context.setIgnoreFurtherProcessing(true);
                    log.info("{} - Extraction returned no result, payload: {}", tenant, jsonObject);
                } else {
                    Set<String> keySet = typedResult.getSubstitutions().keySet();
                    
                    for (String key : keySet) {
                        List<SubstituteValue> processingCacheEntry = new ArrayList<>();
                        List<SubstituteValue> values = typedResult.getSubstitutions().get(key);
                        
                        if (values != null && !values.isEmpty() && values.get(0).expandArray) {
                            // Handle array expansion
                            for (SubstituteValue substitutionValue : values) {
                                SubstitutionEvaluation.processSubstitute(
                                    tenant, 
                                    processingCacheEntry,
                                    substitutionValue.value,
                                    substitutionValue, 
                                    mapping
                                );
                            }
                        } else if (values != null && !values.isEmpty()) {
                            SubstituteValue firstValue = values.get(0);
                            SubstitutionEvaluation.processSubstitute(
                                tenant, 
                                processingCacheEntry, 
                                firstValue.value,
                                firstValue, 
                                mapping
                            );
                        }
                        
                        processingCache.put(key, processingCacheEntry);

                        if (key.equals(Mapping.KEY_TIME)) {
                            substitutionTimeExists = true;
                        }
                    }
                    
                    // Handle alarms
                    if (typedResult.alarms != null && !typedResult.alarms.isEmpty()) {
                        for (String alarm : typedResult.alarms) {
                            context.getAlarms().add(alarm);
                            log.debug("{} - Alarm added: {}", tenant, alarm);
                        }
                    }
                    
                    if (mapping.getDebug() || serviceConfiguration.isLogPayload()) {
                        log.info("{} - Extraction returned {} results, payload: {}", 
                            tenant, 
                            keySet.size(), 
                            jsonObject);
                    }
                }
            }

            // Add default time if not exists
            if (!substitutionTimeExists && 
                mapping.getTargetAPI() != API.INVENTORY &&
                mapping.getTargetAPI() != API.OPERATION) {
                
                List<SubstituteValue> processingCacheEntry = processingCache.getOrDefault(
                    Mapping.KEY_TIME,
                    new ArrayList<>()
                );
                processingCacheEntry.add(
                    new SubstituteValue(
                        new DateTime().toString(),
                        TYPE.TEXTUAL, 
                        RepairStrategy.CREATE_IF_MISSING, 
                        false
                    )
                );
                processingCache.put(Mapping.KEY_TIME, processingCacheEntry);
            }
            
        } catch (Exception e) {
            throw new ProcessingException("Extraction failed: " + e.getMessage(), e);
        } finally {
            // Explicitly null out GraalVM Value references
            result = null;
            sourceValue = null;
            bindings = null;
        }
    }

    /**
     * CRITICAL: Deep convert SubstitutionResult WITH Context still open
     */
    private SubstitutionResult deepConvertSubstitutionResultWithContext(
            Value result, 
            Context graalContext,
            String tenant) {
        
        if (result == null || result.isNull()) {
            return new SubstitutionResult();
        }

        try {
            if (result.isHostObject()) {
                Object hostObj = result.asHostObject();
                
                if (hostObj instanceof SubstitutionResult) {
                    SubstitutionResult originalResult = (SubstitutionResult) hostObj;
                    
                    log.debug("{} - Converting SubstitutionResult with {} substitutions (context is open)", 
                        tenant, originalResult.substitutions.size());
                    
                    SubstitutionResult cleanResult = new SubstitutionResult();
                    
                    for (Map.Entry<String, List<SubstituteValue>> entry : originalResult.substitutions.entrySet()) {
                        String key = entry.getKey();
                        List<SubstituteValue> originalValues = entry.getValue();
                        List<SubstituteValue> cleanValues = new ArrayList<>();
                        
                        for (SubstituteValue subValue : originalValues) {
                            SubstituteValue cleanSubValue = convertSubstituteValueWithContext(
                                subValue, 
                                graalContext,
                                tenant
                            );
                            cleanValues.add(cleanSubValue);
                        }
                        
                        cleanResult.substitutions.put(key, cleanValues);
                    }
                    
                    if (originalResult.alarms != null) {
                        cleanResult.alarms.addAll(originalResult.alarms);
                    }
                    
                    log.debug("{} - Successfully converted SubstitutionResult with {} substitutions", 
                        tenant, cleanResult.substitutions.size());
                    
                    return cleanResult;
                }
            }
            
            log.warn("{} - Result is not a SubstitutionResult host object", tenant);
            return new SubstitutionResult();
            
        } catch (Exception e) {
            log.error("{} - Error converting SubstitutionResult: {}", tenant, e.getMessage(), e);
            throw new RuntimeException("Failed to convert substitution result", e);
        }
    }

    /**
     * Convert SubstituteValue WITH Context available
     */
    private SubstituteValue convertSubstituteValueWithContext(
            SubstituteValue original, 
            Context graalContext,
            String tenant) {
        
        Object cleanValue = original.value;
        
        if (cleanValue != null) {
            String className = cleanValue.getClass().getName();
            
            if (className.contains("org.graalvm.polyglot") || 
                className.contains("com.oracle.truffle.polyglot")) {
                
                log.debug("{} - Converting GraalVM/Truffle object: {}", tenant, className);
                
                try {
                    // Wrap it as a Value and convert
                    Value valueWrapper = graalContext.asValue(cleanValue);
                    cleanValue = JavaScriptInteropHelper.convertValueToJavaObject(valueWrapper);
                    
                    log.debug("{} - Converted to: {} (type: {})", 
                        tenant, 
                        cleanValue,
                        cleanValue != null ? cleanValue.getClass().getName() : "null");
                    
                } catch (Exception e) {
                    log.error("{} - Failed to convert via asValue: {}", tenant, e.getMessage());
                    
                    // Try direct cast to Value
                    try {
                        if (cleanValue instanceof Value) {
                            Value val = (Value) cleanValue;
                            cleanValue = JavaScriptInteropHelper.convertValueToJavaObject(val);
                            log.debug("{} - Converted via direct cast", tenant);
                        }
                    } catch (Exception e2) {
                        log.error("{} - All conversion attempts failed, converting to string", tenant);
                        cleanValue = cleanValue.toString();
                    }
                }
            }
        }
        
        // Final validation
        if (cleanValue != null) {
            String finalClassName = cleanValue.getClass().getName();
            if (finalClassName.contains("org.graalvm") || finalClassName.contains("com.oracle.truffle")) {
                log.error("{} - CONVERSION FAILED! Still has GraalVM object: {}", tenant, finalClassName);
                // Force to string as absolute last resort
                cleanValue = cleanValue.toString();
            }
        }
        
        return new SubstituteValue(
            cleanValue,
            original.type,
            original.repairStrategy,
            original.expandArray
        );
    }
}