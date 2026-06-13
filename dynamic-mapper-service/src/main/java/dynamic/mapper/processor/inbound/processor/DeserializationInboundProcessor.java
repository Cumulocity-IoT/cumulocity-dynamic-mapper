package dynamic.mapper.processor.inbound.processor;

import dynamic.mapper.processor.util.CamelHeaders;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.apache.camel.Exchange;
import org.springframework.stereotype.Component;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.inbound.deserializer.BytePayloadDeserializer;
import dynamic.mapper.processor.inbound.deserializer.FlatFilePayloadDeserializer;
import dynamic.mapper.processor.inbound.deserializer.HexPayloadDeserializer;
import dynamic.mapper.processor.inbound.deserializer.JSONPayloadDeserializer;
import dynamic.mapper.processor.inbound.deserializer.PayloadDeserializer;
import dynamic.mapper.processor.inbound.deserializer.SparkPlugBDeserializer;
import dynamic.mapper.processor.model.MappingType;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.service.MappingService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class DeserializationInboundProcessor extends BaseProcessor {

    private final MappingService mappingService;

    private final Map<MappingType, PayloadDeserializer<?>> deserializers = new HashMap<>();

    public DeserializationInboundProcessor(MappingService mappingService,
            SparkPlugBDeserializer sparkPlugBDeserializer) {
        this.mappingService = mappingService;
        // Map MappingType enum values to deserializers
        deserializers.put(MappingType.JSON, new JSONPayloadDeserializer());
        deserializers.put(MappingType.FLAT_FILE, new FlatFilePayloadDeserializer());
        deserializers.put(MappingType.HEX, new HexPayloadDeserializer());
        deserializers.put(MappingType.PROTOBUF_INTERNAL, new BytePayloadDeserializer());
        deserializers.put(MappingType.ANY_PAYLOAD, new BytePayloadDeserializer());
        // SparkPlugBDeserializer is a Spring bean (needs C8YAgent); registered here now that
        // it is provided via constructor injection.
        deserializers.put(MappingType.SPARKPLUGB, sparkPlugBDeserializer);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) throws Exception {
        String tenant = exchange.getIn().getHeader(CamelHeaders.TENANT, String.class);
        Mapping mapping = exchange.getIn().getBody(Mapping.class);
        Boolean testing = exchange.getIn().getHeader(CamelHeaders.TESTING, Boolean.class);
        
        ServiceConfiguration serviceConfiguration = exchange.getIn().getHeader(CamelHeaders.SERVICE_CONFIGURATION,
                ServiceConfiguration.class);
        ConnectorMessage connectorMessage = exchange.getIn().getHeader(CamelHeaders.CONNECTOR_MESSAGE, ConnectorMessage.class);

        // Create a ConnectorMessage from the context for deserialization

        if (MappingType.PROTOBUF_INTERNAL.equals(mapping.getMappingType())
                || MappingType.ANY_PAYLOAD.equals(mapping.getMappingType())) {
            ProcessingContext<byte[]> context = createProcessingContextAsByteArray(tenant, mapping, connectorMessage,
                    serviceConfiguration, testing);

            PayloadDeserializer<byte[]> deserializer = (PayloadDeserializer<byte[]>) deserializers
                    .get(mapping.getMappingType());
            if (deserializer == null) {
                handleMissingProcessor(tenant, mapping, context);
                exchange.getIn().setHeader(CamelHeaders.PROCESSING_CONTEXT, context); // Set context with error
                return;
            }
            try {
                byte[] deserializedPayload = deserializer.deserializePayload(mapping, connectorMessage); // <--- line 73
                context.setPayload(deserializedPayload);
                exchange.getIn().setHeader(CamelHeaders.PROCESSING_CONTEXT, context);
            } catch (IOException e) {
                handleDeserializationError(tenant, mapping, e, context);
                exchange.getIn().setHeader(CamelHeaders.PROCESSING_CONTEXT, context);
                return;
            }
        } else {
            ProcessingContext<Object> context = createProcessingContextAsObject(tenant, mapping, connectorMessage,
                    serviceConfiguration, testing);

            PayloadDeserializer<Object> deserializer = (PayloadDeserializer<Object>) deserializers
                    .get(mapping.getMappingType());
            if (deserializer == null) {
                handleMissingProcessor(tenant, mapping, context);
                exchange.getIn().setHeader(CamelHeaders.PROCESSING_CONTEXT, context); // Set context with error
                return;
            }

            try {
                Object deserializedPayload = deserializer.deserializePayload(mapping, connectorMessage);
                context.setPayload(deserializedPayload);
                exchange.getIn().setHeader(CamelHeaders.PROCESSING_CONTEXT, context);
            } catch (IOException e) {
                handleDeserializationError(tenant, mapping, e, context);
                exchange.getIn().setHeader(CamelHeaders.PROCESSING_CONTEXT, context);
                return;
            }
        }

    }

    private void handleMissingProcessor(String tenant, Mapping mapping, ProcessingContext<?> context) {
        MappingStatus mappingStatusUnspecified = mappingService
                .getMappingStatus(tenant, Mapping.UNSPECIFIED_MAPPING);
        MappingStatus mappingStatus = mappingService
                .getMappingStatus(tenant, mapping);
        String errorMessage = String.format("%s - No processor for MessageType: %s registered",
                tenant, mapping.getMappingType());
        log.error(errorMessage);
        context.addError(new ProcessingException(errorMessage));
        mappingStatus.errors++;
        mappingStatusUnspecified.errors++;
        mappingService.increaseAndHandleFailureCount(tenant, mapping, mappingStatus);
    }

    private void handleDeserializationError(String tenant, Mapping mapping, Exception e,
            ProcessingContext<?> context) {
        MappingStatus mappingStatus = mappingService
                .getMappingStatus(tenant, mapping);
        String errorMessage = String.format("%s - Failed to deserialize payload: %s",
                tenant, e.getMessage());
        log.warn(errorMessage);
        log.debug("{} - Deserialization error details:", tenant, e);
        context.addError(new ProcessingException(errorMessage, e));
        mappingStatus.errors++;
        mappingService.increaseAndHandleFailureCount(tenant, mapping, mappingStatus);
    }

}
