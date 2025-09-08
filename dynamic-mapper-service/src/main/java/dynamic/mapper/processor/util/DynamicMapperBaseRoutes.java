package dynamic.mapper.processor.util;

import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.SnoopStatus;
import dynamic.mapper.processor.model.MappingType;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.TransformationType;

@Component
public abstract class DynamicMapperBaseRoutes extends RouteBuilder {

    @Autowired
    protected ConnectorRegistry connectorRegistry;

    public abstract void configure() throws Exception;

    /**
     * Check if this is snooping mode
     */
    protected boolean isSnooping(Exchange exchange) {
        try {
            ProcessingContext<?> context = exchange.getIn().getHeader("processingContext", ProcessingContext.class);
            if (context != null && context.getMapping() != null && context.getMapping().getSnoopStatus() != null) {
                return context.getMapping().getSnoopStatus().equals(SnoopStatus.ENABLED)
                        || context.getMapping().getSnoopStatus().equals(SnoopStatus.STARTED);
            }
            return false;
        } catch (Exception e) {
            log.warn("Error checking snooping mode: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Check if this uses SubstitutionAsCode extraction
     */
    protected boolean isSubstitutionAsCode(Exchange exchange) {
        try {
            ProcessingContext<?> context = exchange.getIn().getHeader("processingContext", ProcessingContext.class);
            if (context != null && context.getMapping() != null) {
                TransformationType transformationType = context.getMapping().getTransformationType();
                Boolean substitutionAsCode = context.getMapping().getSubstitutionsAsCode();
                // Fixed logic
                return substitutionAsCode ||
                        TransformationType.SUBSTITUTION_AS_CODE.equals(transformationType);
            }
            return false; // Changed from true to false for better default
        } catch (Exception e) {
            log.warn("Error checking SubstitutionAsCode extraction: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Check if this uses JSONata extraction
     */
    protected boolean isJSONataExtraction(Exchange exchange) {
        try {
            ProcessingContext<?> context = exchange.getIn().getHeader("processingContext", ProcessingContext.class);
            if (context != null && context.getMapping() != null) {
                // Default processing or explicitly JSONata
                TransformationType transformationType = context.getMapping().getTransformationType();
                return transformationType == null ||
                        TransformationType.DEFAULT.equals(transformationType) ||
                        TransformationType.JSONATA.equals(transformationType);
            }
            return true; // Default fallback
        } catch (Exception e) {
            log.warn("Error checking JSONata extraction: {}", e.getMessage());
            return true;
        }
    }

    /**
     * Check if this is extension processing
     */
    protected boolean isExtension(Exchange exchange) {
        try {
            ProcessingContext<?> context = exchange.getIn().getHeader("processingContext", ProcessingContext.class);
            return context != null &&
                    context.getMapping() != null &&
                    (context.getMapping().getExtension() != null);
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
            ProcessingContext<?> context = exchange.getIn().getHeader("processingContext", ProcessingContext.class);
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
            ProcessingContext<?> context = exchange.getIn().getHeader("processingContext", ProcessingContext.class);
            if (context != null && context.getMapping() != null) {
                TransformationType transformationType = context.getMapping().getTransformationType();
                boolean isFlow = TransformationType.FLOW_FUNCTION.equals(transformationType);

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
                log.debug("Mapping {} not deployed for connector {}, skipping",
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
            ProcessingContext<?> context = exchange.getIn().getHeader("processingContext", ProcessingContext.class);
            return context != null && context.isIgnoreFurtherProcessing();
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