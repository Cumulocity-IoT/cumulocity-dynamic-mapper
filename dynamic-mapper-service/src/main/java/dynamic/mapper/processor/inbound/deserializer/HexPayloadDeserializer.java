package dynamic.mapper.processor.inbound.deserializer;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.codec.binary.Hex;

import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.model.Mapping;


public class HexPayloadDeserializer implements PayloadDeserializer<Object> {
    
    @Override
    public Object deserializePayload(Mapping mapping, ConnectorMessage message) throws IOException {
        if (message.getPayload() == null) {
            throw new IOException("Hex payload is null");
        }
        
        try {

            Object payloadObjectNode = new HashMap<>(
                Map.of("payload", "0x" + Hex.encodeHexString(message.getPayload()))
            );
            
            return payloadObjectNode;
            
        } catch (Exception e) {
            throw new IOException("Failed to deserialize hex payload: " + e.getMessage(), e);
        }
    }
}
