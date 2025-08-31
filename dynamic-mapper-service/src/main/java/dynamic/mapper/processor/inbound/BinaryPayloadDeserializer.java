package dynamic.mapper.processor.inbound;

import java.io.IOException;

import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.model.Mapping;

public class BinaryPayloadDeserializer implements PayloadDeserializer<byte[]> {
    
    @Override
    public byte[] deserializePayload(Mapping mapping, ConnectorMessage message) throws IOException {
        if (message.getPayload() == null) {
            throw new IOException("Binary payload is null");
        }
        
        // For binary data, we might want to do some validation or processing
        // based on the mapping configuration
        
        // For now, just return the payload as-is
        return message.getPayload();
    }
}
