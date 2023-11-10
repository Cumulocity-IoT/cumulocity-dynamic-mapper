package mqtt.mapping.connector.core.client;

import mqtt.mapping.connector.core.ConnectorPropertyDefinition;
import lombok.AllArgsConstructor;
import lombok.Data;
import mqtt.mapping.configuration.ConnectorConfiguration;
import mqtt.mapping.core.ServiceStatus;
import mqtt.mapping.model.Mapping;
import mqtt.mapping.processor.model.ProcessingContext;

import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.mutable.MutableInt;

public interface IConnectorClient {

    /***
     * A connector instance should be correlated to exactly 1 tenant. Set the header on initialization.
     * This is needed to correlate messages received via connector to the correct tenant.
     ***/
    public void setTenantId(String tenantId);

    /***
     * Retrieving the tenant from the connector
     ***/
    public String getTenantId();

    /***
     * This method should publish Cumulocity received Messages to the Connector using the provided ProcessContext
     * Relevant for Outbound Communication
     ***/
    public void publishMEAO(ProcessingContext<?> context);

    /***
     * Subscribe to a topic on the Broker
     ***/
    public void subscribe(String topic, Integer qos) throws Exception;

    /***
     * Unsubscribe a topic on the Broker
     ***/
    public void unsubscribe(String topic) throws Exception;

    /***
     * Managing all subscriptions on the broker based on the provided list of Mappings.
     * This method should subscribe on new mappings and unsubscribe on removed mappings.
     * Reset will reset potential Maps holding the subscriptions.
     ***/
    public void updateActiveSubscriptions(List<Mapping> updatedMappings, boolean reset);

    /***
     * Returning all active Subscriptions in a Map of topics and an Integer indicating how many mappers are using it.
     ***/
    public Map<String, MutableInt>  getActiveSubscriptions();

    /***
     * Adding/Updating a new subscription on the broker based on a provided mapping
     ***/
    public void upsertActiveSubscription(Mapping mapping);

    /***
     * Removing a subscription based on a provided mapping
     ***/
    public void deleteActiveSubscription(Mapping mapping);

    /***
     * Connect to the broker
     ***/
    public void connect();

    /***
     * Disconnect from the broker
     ***/
    public void disconnect();

    /***
     * Testing a single transformed message on the broker
     ***/
    public List<ProcessingContext<?>> test(String topic, boolean send, Map<String, Object> payload) throws Exception;

    /***
     * Returning the unique ID identifying the connector type
     ***/
    public String getConntectorId();


    /***
     * Returning the unique ID identifying the connector instance
     ***/
    public String getConntectorIdent();

    /***
     * Returning the connection state to the broker
     ***/
    public boolean isConnected();

    /***
     * Reconnect to the broker
     ***/
    public void reconnect();

    /***
     * Returning all needed configuration properties to configure the connection to the broker
     ***/
    public Map<String, ConnectorPropertyDefinition> getConfigProperties();

    /***
     * Returning the status of the service based on the predefined ServiceStatus class
     ***/
    public ServiceStatus getServiceStatus();

    /***
     * Checks if the provided configuration is valid
     ***/
    public boolean isConfigValid(ConnectorConfiguration configuration);

    @Data
    @AllArgsConstructor
    public static class Certificate {
        private String fingerprint;
        private String certInPemFormat;
    }

}
