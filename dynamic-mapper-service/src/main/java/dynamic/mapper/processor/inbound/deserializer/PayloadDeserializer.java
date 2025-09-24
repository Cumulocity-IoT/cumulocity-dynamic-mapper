package dynamic.mapper.processor.inbound.deserializer;

import java.io.IOException;
import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.model.Mapping;

public interface PayloadDeserializer<T> {
    /**
     * Deserializes the payload from ConnectorMessage based on mapping configuration
     * 
     * @param mapping The mapping configuration containing deserialization rules
     * @param message The connector message containing the raw payload
     * @return Deserialized payload of type T
     * @throws IOException if deserialization fails
     */
    T deserializePayload(Mapping mapping, ConnectorMessage message) throws IOException;
}
