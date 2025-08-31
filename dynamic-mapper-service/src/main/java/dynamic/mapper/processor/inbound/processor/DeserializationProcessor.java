package dynamic.mapper.processor.inbound.processor;

import java.util.HashMap;
import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.springframework.stereotype.Component;

import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.inbound.deserializer.ExtensibleDeserializer;
import dynamic.mapper.processor.inbound.deserializer.FlatFilePayloadDeserializer;
import dynamic.mapper.processor.inbound.deserializer.HexPayloadDeserializer;
import dynamic.mapper.processor.inbound.deserializer.JSONPayloadDeserializer;
import dynamic.mapper.processor.inbound.deserializer.PayloadDeserializer;
import dynamic.mapper.processor.model.MappingType;
import dynamic.mapper.processor.model.ProcessingContext;

@Component
public class DeserializationProcessor implements Processor {
    
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
        ProcessingContext<Object> context = getProcessingContext(exchange);
        Mapping mapping = context.getMapping();
        
        MappingType mappingType = getMappingType(mapping);
        PayloadDeserializer<Object> deserializer = (PayloadDeserializer<Object>) deserializers.get(mappingType);
        
        if (deserializer == null) {
            throw new ProcessingException("No deserializer found for mapping type: " + mappingType);
        }
        
        // Create a ConnectorMessage from the context for deserialization
        ConnectorMessage connectorMessage = createConnectorMessage(context);
        Object deserializedPayload = deserializer.deserializePayload(mapping, connectorMessage);
        
        // Update the context with deserialized payload
        context.setPayload(deserializedPayload);
        
        exchange.getIn().setHeader("processingContext", context);
    }
    
    private MappingType getMappingType(Mapping mapping) {
        // Get mapping type from mapping - use JSON as default if null
        return mapping.getMappingType() != null ? mapping.getMappingType() : MappingType.JSON;
    }
    
    private ConnectorMessage createConnectorMessage(ProcessingContext<Object> context) {
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
    private ProcessingContext<Object> getProcessingContext(Exchange exchange) {
        return exchange.getIn().getHeader("processingContext", ProcessingContext.class);
    }
}
