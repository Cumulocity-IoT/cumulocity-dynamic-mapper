package mqtt.mapping.connector;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;

@Service
@Slf4j
public class ConnectorRegistry {

    //Structure: Tenant, <ConnectorId, ConnectorInstance>
    protected HashMap<String, HashMap<String, IConnectorClient>> connectorTenantMap = new HashMap<>();
    public void registerClient(String tenantId, IConnectorClient client) throws ConnectorRegistryException {
        if(tenantId == null)
            throw new ConnectorRegistryException("TenantId is missing!");
        if(client.getConntectorId() == null)
            throw new ConnectorRegistryException("ConnectorId is missing!");
        if (connectorTenantMap.get(tenantId) == null) {
            HashMap<String, IConnectorClient> connectorMap = new HashMap<>();
            connectorMap.put(client.getConntectorId(), client);
            connectorTenantMap.put(tenantId, connectorMap);
        } else {
            HashMap<String, IConnectorClient> connectorMap = connectorTenantMap.get(tenantId);
            if(connectorMap.get(client.getConntectorId()) == null) {
                log.info("Adding new client for tenant {} with id {}...", tenantId, client.getConntectorId());
                connectorMap.put(client.getConntectorId(), client);
                connectorTenantMap.put(tenantId, connectorMap);
            } else {
                log.info("Client {} is already registered for tenant {}!", client.getConntectorId(), tenantId);
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

    public IConnectorClient getClientForTenant(String tenantId, String connectorId) throws ConnectorRegistryException {
        if(tenantId == null)
            throw new ConnectorRegistryException("TenantId is missing!");
        if(connectorId == null)
            throw new ConnectorRegistryException("ConnectorId is missing!");
        if(connectorTenantMap.get(tenantId) != null) {
            HashMap<String,IConnectorClient> connectorMap = connectorTenantMap.get(tenantId);
            if(connectorMap.get(connectorId) != null)
                return connectorMap.get(connectorId);
            else {
                log.info("No Client is registered for tenant {} and connectorId {}", tenantId, connectorId);
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
                this.unregisterClient(tenantId, client.getConntectorId());
            }
        }
    }

    public void unregisterClient(String tenantId, String connectorId) throws ConnectorRegistryException {
        if(tenantId == null)
            throw new ConnectorRegistryException("TenantId is missing!");
        if(connectorId == null)
            throw new ConnectorRegistryException("ConnectorId is missing!");

        if(connectorTenantMap.get(tenantId) != null) {
            HashMap<String, IConnectorClient> connectorMap = connectorTenantMap.get(tenantId);
            if(connectorMap.get(connectorId) != null) {
                connectorMap.get(connectorId).disconnect();
                connectorMap.remove(connectorId);
            } else {
                log.info("Client {} is not registered", connectorId);
            }
        } else {
            log.info("Client {} is not registered", connectorId);
        }
    }
}
