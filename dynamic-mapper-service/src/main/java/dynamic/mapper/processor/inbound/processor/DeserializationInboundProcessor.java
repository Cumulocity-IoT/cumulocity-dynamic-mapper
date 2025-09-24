package dynamic.mapper.processor.inbound.processor;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.apache.camel.Exchange;
import org.springframework.beans.factory.annotation.Autowired;
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
import dynamic.mapper.processor.model.MappingType;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.service.MappingService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class DeserializationInboundProcessor extends BaseProcessor {

    @Autowired
    private MappingService mappingService;

    private final Map<MappingType, PayloadDeserializer<?>> deserializers = new HashMap<>();

    public DeserializationInboundProcessor() {
        // Map MappingType enum values to deserializers
        deserializers.put(MappingType.JSON, new JSONPayloadDeserializer());
        deserializers.put(MappingType.FLAT_FILE, new FlatFilePayloadDeserializer());
        deserializers.put(MappingType.HEX, new HexPayloadDeserializer());
        deserializers.put(MappingType.PROTOBUF_INTERNAL, new BytePayloadDeserializer());
        deserializers.put(MappingType.EXTENSION_SOURCE, new BytePayloadDeserializer());
        deserializers.put(MappingType.EXTENSION_SOURCE_TARGET, new BytePayloadDeserializer());
        deserializers.put(MappingType.CODE_BASED, new JSONPayloadDeserializer());

        // Add more mappings as needed based on the MappingType enum values
    }

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) throws Exception {
        Mapping mapping = exchange.getIn().getBody(Mapping.class);
        String tenant = exchange.getIn().getHeader("tenant", String.class);
        ServiceConfiguration serviceConfiguration = exchange.getIn().getHeader("serviceConfiguration",
                ServiceConfiguration.class);
        ConnectorMessage connectorMessage = exchange.getIn().getHeader("connectorMessage", ConnectorMessage.class);

        // Create a ConnectorMessage from the context for deserialization

        if (MappingType.PROTOBUF_INTERNAL.equals(mapping.mappingType)
                || MappingType.EXTENSION_SOURCE.equals(mapping.mappingType)
                || MappingType.EXTENSION_SOURCE_TARGET.equals(mapping.mappingType)) {
            ProcessingContext<byte[]> context = createProcessingContextAsByteArray(tenant, mapping, connectorMessage,
                    serviceConfiguration);

            PayloadDeserializer<byte[]> deserializer = (PayloadDeserializer<byte[]>) deserializers
                    .get(mapping.mappingType);
            if (deserializer == null) {
                handleMissingProcessor(tenant, mapping, context);
                exchange.getIn().setHeader("processingContext", context); // Set context with error
                return;
            }
            try {
                byte[] deserializedPayload = deserializer.deserializePayload(mapping, connectorMessage); // <--- line 73
                context.setPayload(deserializedPayload);
                exchange.getIn().setHeader("processingContext", context);
            } catch (IOException e) {
                handleDeserializationError(tenant, mapping, e, context);
                return;
            }
        } else {
            ProcessingContext<Object> context = createProcessingContextAsObject(tenant, mapping, connectorMessage,
                    serviceConfiguration);

            PayloadDeserializer<Object> deserializer = (PayloadDeserializer<Object>) deserializers
                    .get(mapping.mappingType);
            if (deserializer == null) {
                handleMissingProcessor(tenant, mapping, context);
                exchange.getIn().setHeader("processingContext", context); // Set context with error
                return;
            }

            try {
                Object deserializedPayload = deserializer.deserializePayload(mapping, connectorMessage);
                context.setPayload(deserializedPayload);
                exchange.getIn().setHeader("processingContext", context);
            } catch (IOException e) {
                handleDeserializationError(tenant, mapping, e, context);
                return;
            }
        }

    }

    private void handleMissingProcessor(String tenant, Mapping mapping, ProcessingContext<?> context) {
        MappingStatus mappingStatusUnspecified = mappingService
                .getMappingStatus(tenant, Mapping.UNSPECIFIED_MAPPING);
        MappingStatus mappingStatus = mappingService
                .getMappingStatus(tenant, mapping);
        String errorMessage = String.format("Tenant %s - No processor for MessageType: %s registered",
                tenant, mapping.mappingType);
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
        String errorMessage = String.format("Tenant %s - Failed to deserialize payload: %s",
                tenant, e.getMessage());
        log.warn(errorMessage);
        log.debug("{} - Deserialization error details:", tenant, e);
        context.addError(new ProcessingException(errorMessage, e));
        mappingStatus.errors++;
        mappingService.increaseAndHandleFailureCount(tenant, mapping, mappingStatus);
    }

}
