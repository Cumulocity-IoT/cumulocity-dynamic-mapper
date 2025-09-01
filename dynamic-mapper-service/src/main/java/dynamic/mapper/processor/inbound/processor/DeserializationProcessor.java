package dynamic.mapper.processor.inbound.processor;

import java.util.HashMap;
import java.util.Map;

import org.apache.camel.Exchange;
import org.springframework.stereotype.Component;

import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.inbound.deserializer.ExtensibleDeserializer;
import dynamic.mapper.processor.inbound.deserializer.FlatFilePayloadDeserializer;
import dynamic.mapper.processor.inbound.deserializer.HexPayloadDeserializer;
import dynamic.mapper.processor.inbound.deserializer.JSONPayloadDeserializer;
import dynamic.mapper.processor.inbound.deserializer.PayloadDeserializer;
import dynamic.mapper.processor.model.MappingType;
import dynamic.mapper.processor.model.ProcessingContext;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class DeserializationProcessor extends BaseProcessor {

    private final Map<MappingType, PayloadDeserializer<?>> deserializers = new HashMap<>();

    public DeserializationProcessor() {
        // Map MappingType enum values to deserializers
        deserializers.put(MappingType.JSON, new JSONPayloadDeserializer());
        deserializers.put(MappingType.FLAT_FILE, new FlatFilePayloadDeserializer());
        deserializers.put(MappingType.HEX, new HexPayloadDeserializer());
        deserializers.put(MappingType.PROTOBUF_INTERNAL, new HexPayloadDeserializer());
        deserializers.put(MappingType.EXTENSION_SOURCE, new ExtensibleDeserializer());
        deserializers.put(MappingType.EXTENSION_SOURCE_TARGET, new ExtensibleDeserializer());
        deserializers.put(MappingType.CODE_BASED, new JSONPayloadDeserializer());

        // Add more mappings as needed based on the MappingType enum values
    }

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) throws Exception {
        Mapping mapping = exchange.getIn().getBody(Mapping.class);
        // Create a ConnectorMessage from the context for deserialization

        if (MappingType.PROTOBUF_INTERNAL.equals(mapping.mappingType)
                || MappingType.EXTENSION_SOURCE.equals(mapping.mappingType)
                || MappingType.EXTENSION_SOURCE_TARGET.equals(mapping.mappingType)) {
            ProcessingContext<Object> context = getProcessingContextAsObject(exchange);
            ConnectorMessage connectorMessage = createConnectorMessage(context);

            PayloadDeserializer<Object> deserializer = (PayloadDeserializer<Object>) deserializers
                    .get(mapping.mappingType);
            Object deserializedPayload = deserializer.deserializePayload(mapping, connectorMessage);
            context.setPayload(deserializedPayload);

            exchange.getIn().setHeader("processingContext", context);
        } else {
            ProcessingContext<byte[]> context = getProcessingContextAsByteArray(exchange);
            ConnectorMessage connectorMessage = createConnectorMessage(context);

            PayloadDeserializer<byte[]> deserializer = (PayloadDeserializer<byte[]>) deserializers
                    .get(mapping.mappingType);
            byte[] deserializedPayload = deserializer.deserializePayload(mapping, connectorMessage);
            context.setPayload(deserializedPayload);
            exchange.getIn().setHeader("processingContext", context);
        }

    }

    private ConnectorMessage createConnectorMessage(ProcessingContext<?> context) {
        return ConnectorMessage.builder()
                .payload((byte[]) context.getRawPayload())
                .key(context.getKey())
                .tenant(context.getTenant())
                .topic(context.getTopic())
                .client(context.getClient())
                .sendPayload(context.isSendPayload())
                .supportsMessageContext(context.isSupportsMessageContext())
                .connectorIdentifier("camel-processor") // Could be derived from context
                .build();
    }

    @SuppressWarnings("unchecked")
    private ProcessingContext<Object> getProcessingContextAsObject(Exchange exchange) {
        return exchange.getIn().getHeader("processingContextAsObject", ProcessingContext.class);
    }

    @SuppressWarnings("unchecked")
    private ProcessingContext<byte[]> getProcessingContextAsByteArray(Exchange exchange) {
        return exchange.getIn().getHeader("processingContextAsByteArray", ProcessingContext.class);
    }

}
