/*
 * Copyright (c) 2025 Cumulocity GmbH.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *  @authors Christof Strack, Stefan Witschel
 *
 */

package dynamic.mapper.connector.core.registry;

import dynamic.mapper.connector.core.ConnectorSpecification;
import lombok.extern.slf4j.Slf4j;
import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.connector.core.client.ConnectorException;
import dynamic.mapper.connector.core.client.ConnectorType;
import dynamic.mapper.connector.http.HttpClient;
import dynamic.mapper.connector.kafka.KafkaClient;
import dynamic.mapper.connector.mqtt.MQTT3Client;
import dynamic.mapper.connector.mqtt.MQTTServiceClient;
import dynamic.mapper.connector.pulsar.PulsarConnectorClient;
import dynamic.mapper.connector.webhook.WebHook;
import dynamic.mapper.core.ConnectorStatusEvent;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
public class ConnectorRegistry {

    // Structure: < Tenant, < ConnectorStatusEvent, ConnectorInstance > >
    private Map<String, Map<String, AConnectorClient>> connectorTenantMap = new ConcurrentHashMap<>();
    
    // Structure: < ConnectorType, < Property, PropertyDefinition > >
    private Map<ConnectorType, ConnectorSpecification> connectorSpecificationMap = new ConcurrentHashMap<>();

    // Structure: < Tenant, < ConnectorIdentifier, connectorStatusEvent > >
    private Map<String, Map<String, ConnectorStatusEvent>> connectorStatusMaps = new ConcurrentHashMap<>();

    public ConnectorSpecification getConnectorSpecification(ConnectorType connectorType) {
        return connectorSpecificationMap.get(connectorType);
    }

    public Map<ConnectorType, ConnectorSpecification> getConnectorSpecifications() {
        return connectorSpecificationMap;
    }

    public void registerClient(String tenant, AConnectorClient client) throws ConnectorRegistryException {
        if (tenant == null)
            throw new ConnectorRegistryException("Tenant is missing!");
        if (client.getConnectorIdentifier() == null)
            throw new ConnectorRegistryException("Connector identifier is missing!");
        if (connectorTenantMap.get(tenant) == null) {
            Map<String, AConnectorClient> connectorMap = new HashMap<>();
            connectorMap.put(client.getConnectorIdentifier(), client);
            connectorTenantMap.put(tenant, connectorMap);
        } else {
            Map<String, AConnectorClient> connectorMap = connectorTenantMap.get(tenant);
            if (connectorMap.get(client.getConnectorIdentifier()) == null) {
                log.debug("{} - Adding new client with id {}...", tenant, client.getConnectorIdentifier());
                connectorMap.put(client.getConnectorIdentifier(), client);
                connectorTenantMap.put(tenant, connectorMap);
            } else {
                log.debug("{} - Client {} is already registered!", tenant, client.getConnectorIdentifier());
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

    public AConnectorClient getClientForTenant(String tenant, String identifier) throws ConnectorRegistryException {
        if (tenant == null)
            throw new ConnectorRegistryException("Tenant is missing!");
        if (identifier == null)
            throw new ConnectorRegistryException("Connector identifier is missing!");
        if (connectorTenantMap.get(tenant) != null) {
            Map<String, AConnectorClient> connectorMap = connectorTenantMap.get(tenant);
            if (connectorMap.get(identifier) != null)
                return connectorMap.get(identifier);
            else {
                log.info("{} - No Client is registered for connector identifier {}", tenant, identifier);
                return null;
            }
        } else {
            log.info("{} - No Client is registered!", tenant);
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

    public void unregisterClient(String tenant, String identifier) throws ConnectorRegistryException {
        if (tenant == null)
            throw new ConnectorRegistryException("Tenant is missing!");
        if (identifier == null)
            throw new ConnectorRegistryException("Connector identifier is missing!");

        if (connectorTenantMap.get(tenant) != null) {
            Map<String, AConnectorClient> connectorMap = connectorTenantMap.get(tenant);
            if (connectorMap.get(identifier) != null) {
                AConnectorClient client = connectorMap.get(identifier);
                // to avoid memory leaks
                client.setDispatcher(null);
                client.submitDisconnect();
                // client.disconnect();
                client.stopHousekeepingAndClose();

                // store last connector status for monitoring
                connectorStatusMaps.get(tenant).put(identifier, client.getConnectorStatus());
                connectorMap.remove(identifier);
            } else {
                log.warn("{} - Client {} is not registered", tenant, identifier);
            }
        } else {
            log.warn("{} - Client {} is not registered", tenant, identifier);
        }
    }

    public void removeClientFromStatusMap(String tenant, String identifier) throws ConnectorRegistryException {
        if (tenant == null)
            throw new ConnectorRegistryException("Tenant is missing!");
        if (identifier == null)
            throw new ConnectorRegistryException("Connector identifier is missing!");
        if (connectorStatusMaps.get(tenant) != null) {
            connectorStatusMaps.get(tenant).remove(identifier);
        }

    }

    public HttpClient getHttpConnectorForTenant(String tenant) throws ConnectorRegistryException {
        return (HttpClient) getClientForTenant(tenant, HttpClient.HTTP_CONNECTOR_IDENTIFIER);
    }

    public Map<String, ConnectorStatusEvent> getConnectorStatusMap(String tenant) {
        return connectorStatusMaps.get(tenant);
    }

    public void initializeResources(String tenant) {
        connectorStatusMaps.put(tenant, new ConcurrentHashMap<>());
    }

    public void removeResources(String tenant) {
        connectorStatusMaps.remove(tenant);
    }

    public void registerConnectors() throws ConnectorRegistryException, ConnectorException {
        connectorSpecificationMap.put(ConnectorType.MQTT, new MQTT3Client().getConnectorSpecification());
        connectorSpecificationMap.put(ConnectorType.CUMULOCITY_MQTT_SERVICE,
                new MQTTServiceClient().getConnectorSpecification());
        connectorSpecificationMap.put(ConnectorType.KAFKA, new KafkaClient().getConnectorSpecification());
        connectorSpecificationMap.put(ConnectorType.WEB_HOOK, new WebHook().getConnectorSpecification());
        connectorSpecificationMap.put(ConnectorType.HTTP, new HttpClient().getConnectorSpecification());
        connectorSpecificationMap.put(ConnectorType.PULSAR, new PulsarConnectorClient().getConnectorSpecification());
    }

}
