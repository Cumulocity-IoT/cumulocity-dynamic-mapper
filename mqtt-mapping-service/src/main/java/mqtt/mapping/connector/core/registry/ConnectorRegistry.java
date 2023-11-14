package mqtt.mapping.connector.core.registry;

import lombok.extern.slf4j.Slf4j;
import mqtt.mapping.connector.core.ConnectorSpecification;
import mqtt.mapping.connector.core.client.AConnectorClient;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
public class ConnectorRegistry {

    // Structure: Tenant, <Connector Ident, ConnectorInstance>
    protected HashMap<String, HashMap<String, AConnectorClient>> connectorTenantMap = new HashMap<>();
    // Structure: ConnectorId, <Property, PropertyDefinition>
    protected Map<String, ConnectorSpecification> connectorSpecificationMap = new HashMap<>();

    public void registerConnector(String connectorId, ConnectorSpecification specification) {
        connectorSpecificationMap.put(connectorId, specification);
    }
    public ConnectorSpecification getConnectorSpecification(String connectorId) {
        return connectorSpecificationMap.get(connectorId);
    }

    public Map<String, ConnectorSpecification> getConnectorSpecifications() {
        return connectorSpecificationMap;
    }

    public void registerClient(String tenant, AConnectorClient client) throws ConnectorRegistryException {
        if(tenant == null)
            throw new ConnectorRegistryException("tenant is missing!");
        if(client.getConnectorIdent() == null)
            throw new ConnectorRegistryException("Connector ident is missing!");
        if (connectorTenantMap.get(tenant) == null) {
            HashMap<String, AConnectorClient> connectorMap = new HashMap<>();
            connectorMap.put(client.getConnectorIdent(), client);
            connectorTenantMap.put(tenant, connectorMap);
        } else {
            HashMap<String, AConnectorClient> connectorMap = connectorTenantMap.get(tenant);
            if(connectorMap.get(client.getConnectorIdent()) == null) {
                log.info("Adding new client for tenant {} with id {}...", tenant, client.getConnectorIdent());
                connectorMap.put(client.getConnectorIdent(), client);
                connectorTenantMap.put(tenant, connectorMap);
            } else {
                log.info("Client {} is already registered for tenant {}!", client.getConnectorIdent(), tenant);
            }
        }

    }

    public HashMap<String, AConnectorClient> getClientsForTenant(String tenant) throws ConnectorRegistryException {
        if(tenant == null)
            throw new ConnectorRegistryException("tenant is missing!");
        if(connectorTenantMap.get(tenant) != null) {
            return connectorTenantMap.get(tenant);
        } else {
            log.info("No Client is registered for tenant {}", tenant);
            return null;
        }
    }

    public AConnectorClient getClientForTenant(String tenant, String ident) throws ConnectorRegistryException {
        if(tenant == null)
            throw new ConnectorRegistryException("tenant is missing!");
        if(ident == null)
            throw new ConnectorRegistryException("Connector ident is missing!");
        if(connectorTenantMap.get(tenant) != null) {
            HashMap<String,AConnectorClient> connectorMap = connectorTenantMap.get(tenant);
            if(connectorMap.get(ident) != null)
                return connectorMap.get(ident);
            else {
                log.info("No Client is registered for tenant {} and connector ident {}", tenant, ident);
                return null;
            }
        } else {
            log.info("No Client is registered for tenant {}", tenant);
            return null;
        }
    }

    public void unregisterAllClientsForTenant(String tenant) throws ConnectorRegistryException {
        if(tenant == null)
            throw new ConnectorRegistryException("tenant is missing!");
        if(connectorTenantMap.get(tenant) != null) {
            HashMap<String, AConnectorClient> connectorMap = connectorTenantMap.get(tenant);
            for (AConnectorClient client : connectorMap.values()) {
                this.unregisterClient(tenant, client.getConnectorIdent());
            }
        }
    }

    public void unregisterClient(String tenant, String ident) throws ConnectorRegistryException {
        if(tenant == null)
            throw new ConnectorRegistryException("tenant is missing!");
        if(ident == null)
            throw new ConnectorRegistryException("Connector ident is missing!");

        if(connectorTenantMap.get(tenant) != null) {
            HashMap<String, AConnectorClient> connectorMap = connectorTenantMap.get(tenant);
            if(connectorMap.get(ident) != null) {
                AConnectorClient client= connectorMap.get(ident);
                client.disconnect();
                client.stopHouskeepingAndClose();
                connectorMap.remove(ident);
            } else {
                log.info("Client {} is not registered", ident);
            }
        } else {
            log.info("Client {} is not registered", ident);
        }
    }
}
