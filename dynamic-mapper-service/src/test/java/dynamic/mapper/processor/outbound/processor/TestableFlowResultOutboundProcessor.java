/*
 * Copyright (c) 2025 Cumulocity GmbH.
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.camel.Exchange;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMethod;

import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.flow.DeviceMessage;
import dynamic.mapper.processor.flow.ExternalSource;
import dynamic.mapper.processor.model.DynamicMapperRequest;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.util.ProcessingResultHelper;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * Testable version of FlowResultOutboundProcessor
 */
@Slf4j
public class TestableFlowResultOutboundProcessor extends FlowResultOutboundProcessor {
    
    @Setter
    private DeviceResolverFunction deviceResolver;
    
    @Setter
    private TopicResolverFunction topicResolver;
    
    @Setter
    private ExternalSourceConverterFunction externalSourceConverter;
    
    @Setter
    private String defaultDeviceId = "test-device-id";
    
    // Flag to use simplified processing for tests
    @Setter
    private boolean useSimplifiedProcessing = true;
    
    // Need access to objectMapper - it's injected in parent
    @Autowired
    private ObjectMapper objectMapper;
    
    @FunctionalInterface
    public interface DeviceResolverFunction {
        String resolve(ExternalSource externalSource, ProcessingContext<?> context, String tenant) throws ProcessingException;
    }
    
    @FunctionalInterface
    public interface TopicResolverFunction {
        String resolve(DeviceMessage deviceMsg, String resolvedDeviceId);
    }
    
    @FunctionalInterface
    public interface ExternalSourceConverterFunction {
        List<ExternalSource> convert(Object externalSource);
    }
    
    @Override
    public void process(Exchange exchange) throws Exception {
        log.debug("TestableFlowResultOutboundProcessor.process() called, useSimplifiedProcessing={}", 
                useSimplifiedProcessing);
        
        if (useSimplifiedProcessing) {
            // Use simplified test processing
            processSimplified(exchange);
        } else {
            // Use parent's full processing
            super.process(exchange);
        }
    }
    
    /**
     * Simplified processing for tests - creates exactly one request per DeviceMessage
     */
    private void processSimplified(Exchange exchange) throws Exception {
        ProcessingContext<?> context = exchange.getIn().getHeader("processingContext", ProcessingContext.class);
        
        if (context == null) {
            log.warn("No processing context found");
            return;
        }
        
        Object flowResult = context.getFlowResult();
        if (flowResult == null) {
            log.debug("No flow result, setting ignoreFurtherProcessing");
            context.setIgnoreFurtherProcessing(true);
            return;
        }
        
        List<DeviceMessage> messages = new ArrayList<>();
        
        if (flowResult instanceof List) {
            for (Object item : (List<?>) flowResult) {
                if (item instanceof DeviceMessage) {
                    messages.add((DeviceMessage) item);
                }
            }
        } else if (flowResult instanceof DeviceMessage) {
            messages.add((DeviceMessage) flowResult);
        }
        
        if (messages.isEmpty()) {
            log.debug("No DeviceMessages found, setting ignoreFurtherProcessing");
            context.setIgnoreFurtherProcessing(true);
            return;
        }
        
        log.info("{} - Processing {} DeviceMessages in simplified mode", 
                context.getTenant(), messages.size());
        
        // Process each DeviceMessage
        for (DeviceMessage deviceMsg : messages) {
            processDeviceMessageSimplified(deviceMsg, context);
        }
        
        log.info("{} - Created {} requests from {} DeviceMessages (simplified)", 
                context.getTenant(), context.getRequests().size(), messages.size());
    }
    
    /**
     * Simplified device message processing - creates exactly one request
     */
    private void processDeviceMessageSimplified(DeviceMessage deviceMsg, ProcessingContext<?> context) 
            throws ProcessingException {
        
        try {
            String tenant = context.getTenant();
            Mapping mapping = context.getMapping();
            
            // Get external sources
            List<ExternalSource> externalSources = ProcessingResultHelper.convertToExternalSourceList(deviceMsg.getExternalSource());
            
            if (externalSources == null || externalSources.isEmpty()) {
                log.warn("No external sources found in DeviceMessage");
                return;
            }
            
            // Use first external source ONLY
            ExternalSource primarySource = externalSources.get(0);
            
            log.debug("Processing single external source: type={}, externalId={}", 
                    primarySource.getType(), primarySource.getExternalId());
            
            // Resolve device ID
            String resolvedDeviceId = resolveDeviceIdentifier(primarySource, context, tenant);
            
            if (resolvedDeviceId == null) {
                log.warn("Could not resolve device ID for external source: {}", primarySource.getExternalId());
                return;
            }
            
            // Resolve topic
            String resolvedTopic = resolveTopicWithExternalIdToken(deviceMsg, resolvedDeviceId);
            context.setResolvedPublishTopic(resolvedTopic);
            
            // Handle transport fields (message context)
            if (mapping.getSupportsMessageContext() && deviceMsg.getTransportFields() != null) {
                String key = deviceMsg.getTransportFields().get(Mapping.CONTEXT_DATA_KEY_NAME);
                if (key != null) {
                    context.setKey(key);
                }
            }
            
            // Convert payload to JSON string
            String payloadJson = convertPayloadToJson(deviceMsg.getPayload());
            
            // Create single request
            DynamicMapperRequest request = DynamicMapperRequest.builder()
                    .method(RequestMethod.POST)
                    .api(mapping.getTargetAPI())
                    .sourceId(resolvedDeviceId)
                    .externalId(primarySource.getExternalId())
                    .externalIdType(primarySource.getType())
                    .request(payloadJson)
                    .predecessor(-1)  // Flow requests have no predecessor
                    .build();
            
            context.addRequest(request);
            
            log.debug("Created request: sourceId={}, externalId={}, api={}", 
                    resolvedDeviceId, primarySource.getExternalId(), mapping.getTargetAPI());
            
        } catch (Exception e) {
            log.error("Error processing DeviceMessage: {}", e.getMessage(), e);
            throw new ProcessingException("Failed to process DeviceMessage: " + e.getMessage(), e);
        }
    }
    
    /**
     * Convert payload to JSON string using injected ObjectMapper
     */
    private String convertPayloadToJson(Object payload) throws ProcessingException {
        try {
            if (payload == null) {
                return "{}";
            }
            
            if (payload instanceof String) {
                return (String) payload;
            }
            
            // Use the injected objectMapper (from test mocks or real instance)
            if (objectMapper != null) {
                return objectMapper.writeValueAsString(payload);
            }
            
            // Fallback
            return payload.toString();
        } catch (Exception e) {
            throw new ProcessingException("Failed to convert payload to JSON: " + e.getMessage(), e);
        }
    }

    protected String resolveDeviceIdentifier(ExternalSource externalSource, ProcessingContext<?> context, String tenant) 
            throws ProcessingException {
        log.debug("resolveDeviceIdentifier: externalId={}", 
                externalSource != null ? externalSource.getExternalId() : "null");
        
        if (deviceResolver != null) {
            String result = deviceResolver.resolve(externalSource, context, tenant);
            log.debug("Custom device resolver returned: {}", result);
            return result;
        }
        
        if (externalSource != null) {
            log.debug("Using default device ID: {}", defaultDeviceId);
            return defaultDeviceId;
        }
        
        return null;
    }
    
    protected String resolveTopicWithExternalIdToken(DeviceMessage deviceMsg, String resolvedDeviceId) {
        if (topicResolver != null) {
            return topicResolver.resolve(deviceMsg, resolvedDeviceId);
        }
        
        if (deviceMsg == null || deviceMsg.getTopic() == null) {
            return null;
        }
        
        String topic = deviceMsg.getTopic();
        
        if (topic.contains(EXTERNAL_ID_TOKEN)) {
            String resolvedTopic = topic.replace(EXTERNAL_ID_TOKEN, 
                    resolvedDeviceId != null ? resolvedDeviceId : "");
            log.debug("Resolved topic: {} -> {}", topic, resolvedTopic);
            return resolvedTopic;
        }
        
        return topic;
    }
    

    public TestableFlowResultOutboundProcessor withDefaultDeviceId(String deviceId) {
        this.defaultDeviceId = deviceId;
        return this;
    }
    
    public TestableFlowResultOutboundProcessor withDeviceResolver(DeviceResolverFunction resolver) {
        this.deviceResolver = resolver;
        return this;
    }
    
    public TestableFlowResultOutboundProcessor withTopicResolver(TopicResolverFunction resolver) {
        this.topicResolver = resolver;
        return this;
    }
    
    public TestableFlowResultOutboundProcessor withExternalSourceConverter(
            ExternalSourceConverterFunction converter) {
        this.externalSourceConverter = converter;
        return this;
    }
    
    public TestableFlowResultOutboundProcessor withSimplifiedProcessing(boolean simplified) {
        this.useSimplifiedProcessing = simplified;
        log.debug("Set useSimplifiedProcessing to: {}", simplified);
        return this;
    }
    
    public void resetCustomizations() {
        this.deviceResolver = null;
        this.topicResolver = null;
        this.externalSourceConverter = null;
        this.defaultDeviceId = "test-device-id";
        this.useSimplifiedProcessing = true;
    }
}