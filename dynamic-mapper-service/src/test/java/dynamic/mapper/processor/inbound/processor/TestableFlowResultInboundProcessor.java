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

package dynamic.mapper.processor.inbound.processor;

import java.util.List;
import java.util.Map;

import dynamic.mapper.model.API;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.flow.CumulocityMessage;
import dynamic.mapper.processor.flow.CumulocitySource;
import dynamic.mapper.processor.flow.ExternalSource;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.util.ProcessingResultHelper;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * Testable version of FlowResultInboundProcessor that exposes and allows
 * customization of protected methods for unit testing purposes.
 * 
 * <p>This class provides default implementations of protected methods from the
 * parent class and allows tests to override behavior through custom functions.
 * 
 * <p>Example usage:
 * <pre>
 * TestableFlowResultInboundProcessor processor = new TestableFlowResultInboundProcessor();
 * 
 * // Customize device resolution behavior
 * processor.setDeviceResolver((msg, context, tenant) -> "custom-device-id");
 * 
 * // Customize API type mapping
 * processor.setApiMapper(type -> type.equals("custom") ? API.INVENTORY : null);
 * </pre>
 */
@Slf4j
public class TestableFlowResultInboundProcessor extends FlowResultInboundProcessor {
    
    /**
     * Custom function to resolve device identifiers.
     * If not set, uses default implementation.
     */
    @Setter
    private DeviceResolverFunction deviceResolver;
    
    /**
     * Custom function to map cumulocity types to APIs.
     * If not set, uses default implementation.
     */
    @Setter
    private ApiMapperFunction apiMapper;
    
    /**
     * Custom function to convert external sources to lists.
     * If not set, uses default implementation.
     */
    @Setter
    private ExternalSourceConverterFunction externalSourceConverter;
    
    /**
     * Default device ID to return when mocking device resolution.
     */
    @Setter
    private String defaultDeviceId = "test-device-id";
    
    /**
     * Functional interface for device resolution
     */
    @FunctionalInterface
    public interface DeviceResolverFunction {
        String resolve(CumulocityMessage msg, ProcessingContext<?> context, String tenant) throws ProcessingException;
    }
    
    /**
     * Functional interface for API type mapping
     */
    @FunctionalInterface
    public interface ApiMapperFunction {
        API map(String cumulocityType) throws ProcessingException;
    }
    
    /**
     * Functional interface for external source conversion
     */
    @FunctionalInterface
    public interface ExternalSourceConverterFunction {
        List<ExternalSource> convert(Object externalSource);
    }
    
    @Override
    protected String resolveDeviceIdentifier(CumulocityMessage msg, ProcessingContext<?> context, String tenant) 
            throws ProcessingException {
        log.debug("TestableFlowResultInboundProcessor.resolveDeviceIdentifier called");
        
        // Use custom resolver if provided
        if (deviceResolver != null) {
            String result = deviceResolver.resolve(msg, context, tenant);
            log.debug("Custom resolver returned: {}", result);
            return result;
        }
        
        // Default implementation
        // If internal source is set, use it
        if (msg.getInternalSource() != null) {
            Object internalSourceObj = msg.getInternalSource();
            String internalId = null;
            
            // Handle different types of internal source
            if (internalSourceObj instanceof CumulocitySource) {
                internalId = ((CumulocitySource) internalSourceObj).getInternalId();
            } else if (internalSourceObj instanceof String) {
                internalId = (String) internalSourceObj;
            } else if (internalSourceObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> sourceMap = (Map<String, Object>) internalSourceObj;
                internalId = (String) sourceMap.get("id");
            }
            
            if (internalId != null) {
                log.debug("Using internal source ID: {}", internalId);
                return internalId;
            }
        }
        
        // If external source is set, resolve it
        if (msg.getExternalSource() != null) {
            List<ExternalSource> sources = ProcessingResultHelper.convertToExternalSourceList(msg.getExternalSource());
            if (sources != null && !sources.isEmpty()) {
                log.debug("Resolved external source to device ID: {}", defaultDeviceId);
                return defaultDeviceId;
            }
        }
        
        log.debug("No device identifier resolved");
        return null;
    }
    
  
    
    /**
     * Builder-style method to set default device ID
     */
    public TestableFlowResultInboundProcessor withDefaultDeviceId(String deviceId) {
        this.defaultDeviceId = deviceId;
        return this;
    }
    
    /**
     * Builder-style method to set custom device resolver
     */
    public TestableFlowResultInboundProcessor withDeviceResolver(DeviceResolverFunction resolver) {
        this.deviceResolver = resolver;
        return this;
    }
    
    /**
     * Builder-style method to set custom API mapper
     */
    public TestableFlowResultInboundProcessor withApiMapper(ApiMapperFunction mapper) {
        this.apiMapper = mapper;
        return this;
    }
    
    /**
     * Builder-style method to set custom external source converter
     */
    public TestableFlowResultInboundProcessor withExternalSourceConverter(
            ExternalSourceConverterFunction converter) {
        this.externalSourceConverter = converter;
        return this;
    }
    
    /**
     * Reset all customizations to use default implementations
     */
    public void resetCustomizations() {
        this.deviceResolver = null;
        this.apiMapper = null;
        this.externalSourceConverter = null;
        this.defaultDeviceId = "test-device-id";
    }
}