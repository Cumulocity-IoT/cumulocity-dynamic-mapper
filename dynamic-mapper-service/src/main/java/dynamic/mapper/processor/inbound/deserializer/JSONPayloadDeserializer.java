package dynamic.mapper.processor.inbound.deserializer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.model.Mapping;

public class JSONPayloadDeserializer implements PayloadDeserializer<JsonNode> {
    
    private final ObjectMapper objectMapper;
    
    public JSONPayloadDeserializer() {
        this.objectMapper = new ObjectMapper();
        // Configure ObjectMapper as needed
        objectMapper.configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_COMMENTS, true);
        objectMapper.configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_SINGLE_QUOTES, true);
    }
    
    @Override
    public JsonNode deserializePayload(Mapping mapping, ConnectorMessage message) throws IOException {
        if (message.getPayload() == null || message.getPayload().length == 0) {
            throw new IOException("Payload is null or empty");
        }
        
        try {
            // Convert byte array to string and then parse as JSON
            String payloadString = new String(message.getPayload(), StandardCharsets.UTF_8);
            return objectMapper.readTree(payloadString);
        } catch (Exception e) {
            throw new IOException("Failed to deserialize JSON payload: " + e.getMessage(), e);
        }
    }
}
