package mqtt.mapping.connector.core.registry;

import lombok.extern.slf4j.Slf4j;
import mqtt.mapping.connector.core.ConnectorPropertyDefinition;
import mqtt.mapping.connector.mqtt.AConnectorClient;

import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
public class ConnectorRegistry {

    //Structure: Tenant, <Connector Ident, ConnectorInstance>
    protected HashMap<String, HashMap<String, AConnectorClient>> connectorTenantMap = new HashMap<>();
    // ConnectorId, <Property, PropertyDefinition>
    protected Map<String, Map<String, ConnectorPropertyDefinition>> connectorPropertyMap = new HashMap<>();

    public void registerConnector(String connectorId, Map<String, ConnectorPropertyDefinition> properties) {
        connectorPropertyMap.put(connectorId, properties);
    }
    public Map<String, ConnectorPropertyDefinition> getConnectorPropertyDefinition(String connectorId) {
        return connectorPropertyMap.get(connectorId);
    }

    public Map<String, Map<String, ConnectorPropertyDefinition>> getAllConnectorPropertyDefinition() {
        return connectorPropertyMap;
    }

    public void registerClient(String tenant, AConnectorClient client) throws ConnectorRegistryException {
        if(tenant == null)
            throw new ConnectorRegistryException("tenant is missing!");
        if(client.getConntectorIdent() == null)
            throw new ConnectorRegistryException("Connector ident is missing!");
        if (connectorTenantMap.get(tenant) == null) {
            HashMap<String, AConnectorClient> connectorMap = new HashMap<>();
            connectorMap.put(client.getConntectorIdent(), client);
            connectorTenantMap.put(tenant, connectorMap);
        } else {
            HashMap<String, AConnectorClient> connectorMap = connectorTenantMap.get(tenant);
            if(connectorMap.get(client.getConntectorIdent()) == null) {
                log.info("Adding new client for tenant {} with id {}...", tenant, client.getConntectorIdent());
                connectorMap.put(client.getConntectorIdent(), client);
                connectorTenantMap.put(tenant, connectorMap);
            } else {
                log.info("Client {} is already registered for tenant {}!", client.getConntectorIdent(), tenant);
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
                this.unregisterClient(tenant, client.getConntectorIdent());
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
                connectorMap.get(ident).disconnect();
                connectorMap.remove(ident);
            } else {
                log.info("Client {} is not registered", ident);
            }
        } else {
            log.info("Client {} is not registered", ident);
        }
    }
}
