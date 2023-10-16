package mqtt.mapping.client;

import com.cumulocity.rest.representation.AbstractExtensibleRepresentation;
import mqtt.mapping.processor.model.ProcessingContext;

public interface ConnectorClient {

    public AbstractExtensibleRepresentation publish(ProcessingContext<?> context) throws Exception;

    public void subscribe(String topic, Integer qos) throws Exception;

    public void connect();

    public void disconnect();
}
