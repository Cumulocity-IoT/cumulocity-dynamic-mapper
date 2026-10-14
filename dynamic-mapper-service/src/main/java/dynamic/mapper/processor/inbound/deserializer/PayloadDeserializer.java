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

    /**
     * Same as {@link #deserializePayload(Mapping, ConnectorMessage)}, but tells the deserializer
     * whether this is a mapping test (dry-run). Deserializers that read tenant state (inventory,
     * identity) must use the mock services when {@code testing} is {@code true}; all others can
     * ignore it, which is what this default does.
     */
    default T deserializePayload(Mapping mapping, ConnectorMessage message, boolean testing) throws IOException {
        return deserializePayload(mapping, message);
    }
}
