package dynamic.mapper.processor.inbound.deserializer;

import java.io.IOException;

import com.dashjoin.jsonata.json.Json;
import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.model.Mapping;

public class JSONPayloadDeserializer implements PayloadDeserializer<Object> {

    private final ObjectMapper objectMapper;

    public JSONPayloadDeserializer() {
        this.objectMapper = new ObjectMapper();
        // Configure ObjectMapper as needed
        objectMapper.configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_COMMENTS, true);
        objectMapper.configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_SINGLE_QUOTES, true);
    }

    @Override
    public Object deserializePayload(Mapping mapping, ConnectorMessage message) throws IOException {
        if (message.getPayload() == null || message.getPayload().length == 0) {
            throw new IOException("Payload is null or empty");
        }

        try {
            // Convert byte array to string and then parse as JSON
            Object jsonObject = Json.parseJson(new String(message.getPayload(), "UTF-8"));
            return jsonObject;
        } catch (Exception e) {
            throw new IOException("Failed to deserialize JSON payload: " + e.getMessage(), e);
        }
    }
}
