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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dynamic.mapper.model.API;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.flow.CumulocityMessage;
import dynamic.mapper.processor.flow.CumulocitySource;
import dynamic.mapper.processor.flow.ExternalSource;
import dynamic.mapper.processor.model.ProcessingContext;
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
            List<ExternalSource> sources = convertToExternalSourceList(msg.getExternalSource());
            if (sources != null && !sources.isEmpty()) {
                log.debug("Resolved external source to device ID: {}", defaultDeviceId);
                return defaultDeviceId;
            }
        }
        
        log.debug("No device identifier resolved");
        return null;
    }
    
    @Override
    protected API getAPIFromCumulocityType(String cumulocityType) throws ProcessingException {
        log.debug("TestableFlowResultInboundProcessor.getAPIFromCumulocityType called with: {}", cumulocityType);
        
        // Use custom mapper if provided
        if (apiMapper != null) {
            API result = apiMapper.map(cumulocityType);
            if (result != null) {
                log.debug("Custom API mapper returned: {}", result);
                return result;
            }
        }
        
        // Default implementation
        if (cumulocityType == null) {
            throw new ProcessingException("Cumulocity type cannot be null");
        }
        
        API result;
        switch (cumulocityType.toLowerCase()) {
            case "measurement":
                result = API.MEASUREMENT;
                break;
            case "event":
                result = API.EVENT;
                break;
            case "alarm":
                result = API.ALARM;
                break;
            case "inventory":
            case "managedobject":
                result = API.INVENTORY;
                break;
            case "operation":
                result = API.OPERATION;
                break;
            default:
                throw new ProcessingException("Unknown cumulocity type: " + cumulocityType);
        }
        
        log.debug("Mapped type '{}' to API: {}", cumulocityType, result);
        return result;
    }
    
    
    protected void setHierarchicalValue(Map<String, Object> payload, String path, String value) {
        log.debug("TestableFlowResultInboundProcessor.setHierarchicalValue called: {}={}", path, value);
        
        if (path == null || payload == null) {
            log.warn("Cannot set hierarchical value: path or payload is null");
            return;
        }
        
        // Handle nested paths like "source.id"
        if (path.contains(".")) {
            String[] parts = path.split("\\.");
            Map<String, Object> current = payload;
            
            // Navigate/create nested structure
            for (int i = 0; i < parts.length - 1; i++) {
                String part = parts[i];
                if (!current.containsKey(part)) {
                    current.put(part, new HashMap<String, Object>());
                }
                
                Object next = current.get(part);
                if (next instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> nextMap = (Map<String, Object>) next;
                    current = nextMap;
                } else {
                    // Can't navigate further, create new map
                    Map<String, Object> newMap = new HashMap<>();
                    current.put(part, newMap);
                    current = newMap;
                }
            }
            
            // Set the final value
            String finalKey = parts[parts.length - 1];
            current.put(finalKey, value);
            log.debug("Set hierarchical value successfully");
        } else {
            // Simple path
            payload.put(path, value);
            log.debug("Set simple value successfully");
        }
    }
    
    @Override
    protected List<ExternalSource> convertToExternalSourceList(Object externalSource) {
        log.debug("TestableFlowResultInboundProcessor.convertToExternalSourceList called with type: {}", 
                externalSource != null ? externalSource.getClass().getSimpleName() : "null");
        
        // Use custom converter if provided
        if (externalSourceConverter != null) {
            List<ExternalSource> result = externalSourceConverter.convert(externalSource);
            log.debug("Custom converter returned: {} items", result != null ? result.size() : 0);
            return result;
        }
        
        // Default implementation
        if (externalSource == null) {
            return null;
        }
        
        // Already a list
        if (externalSource instanceof List) {
            @SuppressWarnings("unchecked")
            List<ExternalSource> list = (List<ExternalSource>) externalSource;
            log.debug("External source is already a list with {} elements", list.size());
            return list;
        }
        
        // Convert from Map
        if (externalSource instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) externalSource;
            ExternalSource es = new ExternalSource();
            es.setType((String) map.get("type"));
            es.setExternalId((String) map.get("externalId"));
            
            List<ExternalSource> list = new ArrayList<>();
            list.add(es);
            log.debug("Converted Map to ExternalSource list: type={}, externalId={}", 
                    es.getType(), es.getExternalId());
            return list;
        }
        
        // Single ExternalSource object
        if (externalSource instanceof ExternalSource) {
            List<ExternalSource> list = new ArrayList<>();
            list.add((ExternalSource) externalSource);
            log.debug("Wrapped single ExternalSource in list");
            return list;
        }
        
        log.warn("Could not convert external source of type: {}", 
                externalSource.getClass().getSimpleName());
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