package dynamic.mapper.processor.inbound.deserializer;

import java.io.IOException;

import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.model.Mapping;


public class BytePayloadDeserializer implements PayloadDeserializer<byte[]> {
    
    @Override
    public byte[] deserializePayload(Mapping mapping, ConnectorMessage message) throws IOException {
        if (message.getPayload() == null) {
            throw new IOException("Hex payload is null");
        }
        
        try {
            return message.getPayload();
            
        } catch (Exception e) {
            throw new IOException("Failed to deserialize hex payload: " + e.getMessage(), e);
        }
    }
}
