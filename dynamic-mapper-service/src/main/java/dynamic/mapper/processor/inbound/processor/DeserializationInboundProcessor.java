package dynamic.mapper.processor.inbound.processor;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.status.MappingStatus;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.inbound.deserializer.BytePayloadDeserializer;
import dynamic.mapper.processor.inbound.deserializer.FlatFilePayloadDeserializer;
import dynamic.mapper.processor.inbound.deserializer.HexPayloadDeserializer;
import dynamic.mapper.processor.inbound.deserializer.JSONPayloadDeserializer;
import dynamic.mapper.processor.inbound.deserializer.PayloadDeserializer;
import dynamic.mapper.processor.inbound.deserializer.SparkPlugBDeserializer;
import dynamic.mapper.model.MappingType;
import dynamic.mapper.processor.runtime.ProcessingContext;
import dynamic.mapper.mapping.MappingService;
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

    /**
     * Builds the {@link ProcessingContext} for a single mapping and deserializes the raw
     * connector payload into it. Returns the context (carrying an error on failure) rather
     * than writing it to a Camel exchange header.
     */
    @SuppressWarnings("unchecked")
    public ProcessingContext<?> process(String tenant, Mapping mapping, ConnectorMessage connectorMessage,
            ServiceConfiguration serviceConfiguration, Boolean testing) throws Exception {
        if (MappingType.PROTOBUF_INTERNAL.equals(mapping.getMappingType())
                || MappingType.ANY_PAYLOAD.equals(mapping.getMappingType())) {
            ProcessingContext<byte[]> context = createProcessingContextAsByteArray(tenant, mapping, connectorMessage,
                    serviceConfiguration, testing);

            PayloadDeserializer<byte[]> deserializer = (PayloadDeserializer<byte[]>) deserializers
                    .get(mapping.getMappingType());
            if (deserializer == null) {
                handleMissingProcessor(tenant, mapping, context);
                return context; // Return context with error
            }
            try {
                byte[] deserializedPayload = deserializer.deserializePayload(mapping, connectorMessage);
                context.setPayload(deserializedPayload);
                return context;
            } catch (IOException e) {
                handleDeserializationError(tenant, mapping, e, context);
                return context;
            }
        } else {
            ProcessingContext<Object> context = createProcessingContextAsObject(tenant, mapping, connectorMessage,
                    serviceConfiguration, testing);

            PayloadDeserializer<Object> deserializer = (PayloadDeserializer<Object>) deserializers
                    .get(mapping.getMappingType());
            if (deserializer == null) {
                handleMissingProcessor(tenant, mapping, context);
                return context; // Return context with error
            }

            try {
                Object deserializedPayload = deserializer.deserializePayload(mapping, connectorMessage);
                context.setPayload(deserializedPayload);
                return context;
            } catch (IOException e) {
                handleDeserializationError(tenant, mapping, e, context);
                return context;
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
        mappingStatus.incrementErrors();
        mappingStatusUnspecified.incrementErrors();
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
        mappingStatus.incrementErrors();
        mappingService.increaseAndHandleFailureCount(tenant, mapping, mappingStatus);
    }

}
