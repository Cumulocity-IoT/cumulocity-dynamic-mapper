package dynamic.mapper.processor.inbound.deserializer;

import java.io.IOException;

import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.model.Mapping;

/** Keeps the broker payload as raw bytes (ANY_PAYLOAD, PROTOBUF_INTERNAL). */
public class BytePayloadDeserializer implements PayloadDeserializer<byte[]> {

    @Override
    public byte[] deserializePayload(Mapping mapping, ConnectorMessage message) throws IOException {
        if (message.getPayload() == null) {
            throw new IOException("Payload is null");
        }
        return message.getPayload();
    }
}
