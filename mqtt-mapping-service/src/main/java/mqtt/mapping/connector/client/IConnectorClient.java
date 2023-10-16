package mqtt.mapping.connector.client;

import com.cumulocity.rest.representation.AbstractExtensibleRepresentation;
import mqtt.mapping.processor.model.ProcessingContext;

public interface IConnectorClient {

    String tenantId = null;

    public void setTenantId(String tenantId);

    public AbstractExtensibleRepresentation publish(ProcessingContext<?> context) throws Exception;

    public void subscribe(String topic, Integer qos) throws Exception;

    public void connect();

    public void disconnect();

    public String getConntectorId();
}
