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
package dynamic.mapper.processor.util;

import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.connector.test.TestClient;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.model.MappingType;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.TransformationType;

@Component
public abstract class DynamicMapperBaseRoutes extends RouteBuilder {

    @Autowired
    protected ConnectorRegistry connectorRegistry;

    public abstract void configure() throws Exception;

    /**
     * Check if this uses JSONata extraction.
     * Returns false on exception so the caller's otherwise() branch handles it
     * explicitly rather than silently routing to JSONata and masking the real bug.
     */
    protected boolean isJSONataExtraction(Exchange exchange) {
        try {
            ProcessingContext<?> context = exchange.getIn().getHeader(CamelHeaders.PROCESSING_CONTEXT, ProcessingContext.class);
            if (context != null && context.getMapping() != null) {
                // Default processing or explicitly JSONata
                TransformationType transformationType = context.getMapping().getTransformationType();
                return transformationType == null ||
                        TransformationType.DEFAULT.equals(transformationType) ||
                        TransformationType.JSONATA.equals(transformationType);
            }
            return false;
        } catch (Exception e) {
            log.warn("Error checking JSONata extraction: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Check if this is extension processing.
     * Requires BOTH a non-null extension object AND TransformationType.EXTENSION_JAVA so that
     * mappings which carry a leftover extension field from a previous type are not accidentally
     * routed to the extension path.
     */
    protected boolean isExtension(Exchange exchange) {
        try {
            ProcessingContext<?> context = exchange.getIn().getHeader(CamelHeaders.PROCESSING_CONTEXT, ProcessingContext.class);
            return context != null &&
                    context.getMapping() != null &&
                    context.getMapping().getExtension() != null &&
                    TransformationType.EXTENSION_JAVA.equals(context.getMapping().getTransformationType());
        } catch (Exception e) {
            log.warn("Error checking extension: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Check if this is extension processing
     */
    protected boolean isInternalProtobuf(Exchange exchange) {
        try {
            ProcessingContext<?> context = exchange.getIn().getHeader(CamelHeaders.PROCESSING_CONTEXT, ProcessingContext.class);
            return context != null &&
                    context.getMapping() != null &&
                    (MappingType.PROTOBUF_INTERNAL.equals(context.getMapping().getMappingType()));
        } catch (Exception e) {
            log.warn("Error checking extension: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Check if the mapping uses flow function transformation
     */
    protected boolean isFlowFunction(Exchange exchange) {
        try {
            ProcessingContext<?> context = exchange.getIn().getHeader(CamelHeaders.PROCESSING_CONTEXT, ProcessingContext.class);
            if (context != null && context.getMapping() != null) {
                TransformationType transformationType = context.getMapping().getTransformationType();
                boolean isFlow = TransformationType.SMART_FUNCTION.equals(transformationType);

                log.debug("Checking transformation type for mapping {}: {} (isFlow: {})",
                        context.getMapping().getName(),
                        transformationType != null ? transformationType.toString() : "null",
                        isFlow);

                return isFlow;
            }
            return false;
        } catch (Exception e) {
            log.warn("Error checking transformation type: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Custom predicate to validate if mapping should be processed
     */
    protected boolean isValidMapping(String tenant, Mapping mapping, String connectorIdentifier) {
        try {

            if (TestClient.TEST_CONNECTOR_IDENTIFIER.equals(connectorIdentifier)) {
                // Test connector processes all mappings
                return true;
            }

            if (mapping == null) {
                log.debug("Mapping is null, skipping");
                return false;
            }

            // Check if mapping is active
            if (!mapping.getActive()) {
                log.debug("Mapping {} is inactive, skipping", mapping.getName());
                return false;
            }

            // Check if mapping is deployed (you'll need to get connector info)
            if (connectorIdentifier != null && !isMappingDeployed(tenant, mapping, connectorIdentifier)) {
                log.info("Mapping {} not deployed for connector {}, skipping",
                        mapping.getName(), connectorIdentifier);
                return false;
            }

            return true;

        } catch (Exception e) {
            log.error("Error validating mapping: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Check if further processing should be ignored
     */
    protected boolean shouldIgnoreFurtherProcessing(Exchange exchange) {
        try {
            ProcessingContext<?> context = exchange.getIn().getHeader(CamelHeaders.PROCESSING_CONTEXT, ProcessingContext.class);
            return context != null && context.getIgnoreFurtherProcessing();
        } catch (Exception e) {
            log.warn("Error checking ignore further processing: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Check if mapping is deployed for the connector
     */
    protected abstract boolean isMappingDeployed(String tenant, Mapping mapping, String connectorIdentifier);

}