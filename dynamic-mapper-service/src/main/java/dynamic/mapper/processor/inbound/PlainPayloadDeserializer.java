package dynamic.mapper.processor.inbound;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.model.Mapping;

public class PlainPayloadDeserializer implements PayloadDeserializer<String> {
    
    @Override
    public String deserializePayload(Mapping mapping, ConnectorMessage message) throws IOException {
        if (message.getPayload() == null) {
            return null;
        }
        
        try {
            // Convert byte array to string using UTF-8 encoding
            // You could make encoding configurable based on mapping if needed
            return new String(message.getPayload(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IOException("Failed to deserialize plain text payload: " + e.getMessage(), e);
        }
    }
}