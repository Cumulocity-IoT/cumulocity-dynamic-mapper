package mqtt.mapping.connector.core.registry;

import lombok.extern.slf4j.Slf4j;
import mqtt.mapping.connector.core.client.IConnectorClient;
import org.springframework.stereotype.Service;

import java.util.HashMap;

@Service
@Slf4j
public class ConnectorRegistry {

    //Structure: Tenant, <Connector Ident, ConnectorInstance>
    protected HashMap<String, HashMap<String, IConnectorClient>> connectorTenantMap = new HashMap<>();
    public void registerClient(String tenantId, IConnectorClient client) throws ConnectorRegistryException {
        if(tenantId == null)
            throw new ConnectorRegistryException("TenantId is missing!");
        if(client.getConntectorIdent() == null)
            throw new ConnectorRegistryException("Connector ident is missing!");
        if (connectorTenantMap.get(tenantId) == null) {
            HashMap<String, IConnectorClient> connectorMap = new HashMap<>();
            connectorMap.put(client.getConntectorIdent(), client);
            connectorTenantMap.put(tenantId, connectorMap);
        } else {
            HashMap<String, IConnectorClient> connectorMap = connectorTenantMap.get(tenantId);
            if(connectorMap.get(client.getConntectorIdent()) == null) {
                log.info("Adding new client for tenant {} with id {}...", tenantId, client.getConntectorIdent());
                connectorMap.put(client.getConntectorIdent(), client);
                connectorTenantMap.put(tenantId, connectorMap);
            } else {
                log.info("Client {} is already registered for tenant {}!", client.getConntectorIdent(), tenantId);
            }
        }

    }

    public HashMap<String, IConnectorClient> getClientsForTenant(String tenantId) throws ConnectorRegistryException {
        if(tenantId == null)
            throw new ConnectorRegistryException("TenantId is missing!");
        if(connectorTenantMap.get(tenantId) != null) {
            return connectorTenantMap.get(tenantId);
        } else {
            log.info("No Client is registered for tenant {}", tenantId);
            return null;
        }
    }

    public IConnectorClient getClientForTenant(String tenantId, String ident) throws ConnectorRegistryException {
        if(tenantId == null)
            throw new ConnectorRegistryException("TenantId is missing!");
        if(ident == null)
            throw new ConnectorRegistryException("Connector ident is missing!");
        if(connectorTenantMap.get(tenantId) != null) {
            HashMap<String,IConnectorClient> connectorMap = connectorTenantMap.get(tenantId);
            if(connectorMap.get(ident) != null)
                return connectorMap.get(ident);
            else {
                log.info("No Client is registered for tenant {} and connector ident {}", tenantId, ident);
                return null;
            }
        } else {
            log.info("No Client is registered for tenant {}", tenantId);
            return null;
        }
    }

    public void unregisterAllClientsForTenant(String tenantId) throws ConnectorRegistryException {
        if(tenantId == null)
            throw new ConnectorRegistryException("TenantId is missing!");
        if(connectorTenantMap.get(tenantId) != null) {
            HashMap<String, IConnectorClient> connectorMap = connectorTenantMap.get(tenantId);
            for (IConnectorClient client : connectorMap.values()) {
                this.unregisterClient(tenantId, client.getConntectorIdent());
            }
        }
    }

    public void unregisterClient(String tenantId, String ident) throws ConnectorRegistryException {
        if(tenantId == null)
            throw new ConnectorRegistryException("TenantId is missing!");
        if(ident == null)
            throw new ConnectorRegistryException("Connector ident is missing!");

        if(connectorTenantMap.get(tenantId) != null) {
            HashMap<String, IConnectorClient> connectorMap = connectorTenantMap.get(tenantId);
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
