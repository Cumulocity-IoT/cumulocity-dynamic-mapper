package dynamic.mapping.connector.core.registry;

import dynamic.mapping.connector.core.ConnectorSpecification;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import dynamic.mapping.connector.core.client.AConnectorClient;
import dynamic.mapping.connector.core.client.ConnectorType;
import dynamic.mapping.core.ConnectorStatusEvent;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

@Component
@Slf4j
public class ConnectorRegistry {

	// Structure: Tenant, <Connector Ident, ConnectorInstance>
	protected Map<String, Map<String, AConnectorClient>> connectorTenantMap = new HashMap<>();
	// Structure: ConnectorType, <Property, PropertyDefinition>
	protected Map<ConnectorType, ConnectorSpecification> connectorSpecificationMap = new HashMap<>();

	// Structure: Tenant, <Connector Ident, ConnectorInstance>
	@Getter
	private Map<String, Map<String, ConnectorStatusEvent>> connectorStatusMap = new HashMap<>();

	public void registerConnector(ConnectorType connectorType, ConnectorSpecification specification) {
		connectorSpecificationMap.put(connectorType, specification);
	}

	public ConnectorSpecification getConnectorSpecification(ConnectorType connectorType) {
		return connectorSpecificationMap.get(connectorType);
	}

	public Map<ConnectorType, ConnectorSpecification> getConnectorSpecifications() {
		return connectorSpecificationMap;
	}

	public void registerClient(String tenant, AConnectorClient client) throws ConnectorRegistryException {
		if (tenant == null)
			throw new ConnectorRegistryException("Tenant is missing!");
		if (client.getConnectorIdent() == null)
			throw new ConnectorRegistryException("Connector ident is missing!");
		if (connectorTenantMap.get(tenant) == null) {
			Map<String, AConnectorClient> connectorMap = new HashMap<>();
			connectorMap.put(client.getConnectorIdent(), client);
			connectorTenantMap.put(tenant, connectorMap);
		} else {
			Map<String, AConnectorClient> connectorMap = connectorTenantMap.get(tenant);
			if (connectorMap.get(client.getConnectorIdent()) == null) {
				log.debug("Tenant {} - Adding new client with id {}...", tenant, client.getConnectorIdent());
				connectorMap.put(client.getConnectorIdent(), client);
				connectorTenantMap.put(tenant, connectorMap);
			} else {
				log.debug("Tenant {} - Client {} is already registered!", tenant, client.getConnectorIdent());
			}
		}

	}

	public Map<String, AConnectorClient> getClientsForTenant(String tenant) throws ConnectorRegistryException {
		if (tenant == null)
			throw new ConnectorRegistryException("Tenant is missing!");
		if (connectorTenantMap.get(tenant) != null) {
			return connectorTenantMap.get(tenant);
		} else {
			Map<String, AConnectorClient> result = new HashMap<>();
			connectorTenantMap.put(tenant, result);
			return result;
		}
	}

	public AConnectorClient getClientForTenant(String tenant, String ident) throws ConnectorRegistryException {
		if (tenant == null)
			throw new ConnectorRegistryException("Tenant is missing!");
		if (ident == null)
			throw new ConnectorRegistryException("Connector ident is missing!");
		if (connectorTenantMap.get(tenant) != null) {
			Map<String, AConnectorClient> connectorMap = connectorTenantMap.get(tenant);
			if (connectorMap.get(ident) != null)
				return connectorMap.get(ident);
			else {
				log.info("Tenant {} - No Client is registered for connector ident {}", tenant, ident);
				return null;
			}
		} else {
			log.info("Tenant {} - No Client is registered!", tenant);
			return null;
		}
	}

	public void unregisterAllClientsForTenant(String tenant) throws ConnectorRegistryException {
		if (tenant == null)
			throw new ConnectorRegistryException("Tenant is missing!");
		if (connectorTenantMap.get(tenant) != null) {
			Map<String, AConnectorClient> connectorMap = connectorTenantMap.get(tenant);
			Iterator<Entry<String, AConnectorClient>> iterator = connectorMap.entrySet().iterator();
			while (iterator.hasNext()) {
				Entry<String, AConnectorClient> entryNext = iterator.next();
				entryNext.getValue().disconnect();
				entryNext.getValue().stopHousekeepingAndClose();
				iterator.remove();
			}
			// for (AConnectorClient client : connectorMap.values()) {
			// this.unregisterClient(tenant, client.getConnectorIdent());
			// }
		}
	}

	public void unregisterClient(String tenant, String ident) throws ConnectorRegistryException {
		if (tenant == null)
			throw new ConnectorRegistryException("Tenant is missing!");
		if (ident == null)
			throw new ConnectorRegistryException("Connector ident is missing!");

		if (connectorTenantMap.get(tenant) != null) {
			Map<String, AConnectorClient> connectorMap = connectorTenantMap.get(tenant);
			if (connectorMap.get(ident) != null) {
				AConnectorClient client = connectorMap.get(ident);
				// to avoid memory leaks
				client.setDispatcher(null);
				client.disconnect();
				client.stopHousekeepingAndClose();

				// store last connector status for monitoring
				connectorStatusMap.get(tenant).put(ident, client.getConnectorStatus());
				connectorMap.remove(ident);
			} else {
				log.warn("Tenant {} - Client {} is not registered", tenant, ident);
			}
		} else {
			log.warn("Tenant {} - Client {} is not registered", tenant, ident);
		}
	}

}
