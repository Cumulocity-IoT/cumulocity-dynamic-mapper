package mqtt.mapping.connector;

import lombok.AllArgsConstructor;
import lombok.Data;
import mqtt.mapping.core.ServiceStatus;
import mqtt.mapping.model.Mapping;
import mqtt.mapping.processor.model.ProcessingContext;

import java.util.List;
import java.util.Map;

public interface IConnectorClient {

    public void setTenantId(String tenantId);

    public String getTenantId();

    public void publishMEAO(ProcessingContext<?> context);

    public void subscribe(String topic, Integer qos) throws Exception;

    public void unsubscribe(String topic) throws Exception;

    public List<Mapping> updateActiveSubscriptions(List<Mapping> updatedMappings, boolean reset);

    public Map<String, Integer>  getActiveSubscriptions(String tenant);

    public void upsertActiveSubscription(Mapping mapping);

    public void deleteActiveSubscription(Mapping mapping);

    public void connect();

    public void disconnect();

    public List<ProcessingContext<?>>  test(String topic, boolean send, Map<String, Object> payload) throws Exception;

    public String getConntectorId();

    public boolean isConnected();

    public void reconnect();

    public Map<String, ConnectorProperty> getConfigProperties();

    public ServiceStatus getServiceStatus();

    public boolean isConfigValid(ConnectorConfiguration configuration);

    @Data
    @AllArgsConstructor
    public static class Certificate {
        private String fingerprint;
        private String certInPemFormat;
    }


}
