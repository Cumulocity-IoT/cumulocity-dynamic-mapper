package mqtt.mapping.connector;

import com.cumulocity.rest.representation.AbstractExtensibleRepresentation;
import mqtt.mapping.processor.model.ProcessingContext;

import java.util.HashMap;
import java.util.Map;

public interface IConnectorClient {

    String tenantId = null;

    public void setTenantId(String tenantId);

    public String getTenantId();

    public AbstractExtensibleRepresentation publish(ProcessingContext<?> context) throws Exception;

    public void subscribe(String topic, Integer qos) throws Exception;

    public void connect();

    public void disconnect();

    public String getConntectorId();

    public boolean isConnected();

    public void reconnect();

    public Map<String, ConnectorProperty> getConfigProperties();
}
