package dynamic.mapper.processor.inbound.deserializer;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;

import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.model.Mapping;

public class FlatFilePayloadDeserializer implements PayloadDeserializer<Object> {
    
    @Override
    public Object deserializePayload(Mapping mapping, ConnectorMessage message) throws IOException {
        try {
            // EXACT copy of FlatFileProcessorInbound.deserializePayload logic
            String payloadMessage = (message.getPayload() != null
                    ? new String(message.getPayload(), Charset.defaultCharset())
                    : "");
            
            // Object payloadObjectNode = objectMapper.valueToTree(new PayloadWrapper(payloadMessage));
            Object payloadObjectNode = new HashMap<>(Map.of("payload", payloadMessage));
            
            return payloadObjectNode;
            
        } catch (Exception e) {
            throw new IOException("Failed to deserialize flat file payload: " + e.getMessage(), e);
        }
    }
}
