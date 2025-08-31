package dynamic.mapper.processor.inbound.deserializer;

import java.io.IOException;

import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.model.Mapping;

public class ExtensibleDeserializer implements PayloadDeserializer<byte[]> {
    
    @Override
    public byte[] deserializePayload(Mapping mapping, ConnectorMessage message) throws IOException {
        // EXACT copy of ExtensibleProcessorInbound.deserializePayload logic
        return message.getPayload();
    }
}
