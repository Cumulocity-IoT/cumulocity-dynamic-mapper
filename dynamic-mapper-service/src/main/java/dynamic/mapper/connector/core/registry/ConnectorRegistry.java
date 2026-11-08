/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
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

import dynamic.mapper.core.TenantScoped;
import dynamic.mapper.connector.core.ConnectorSpecification;
import lombok.extern.slf4j.Slf4j;
import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.connector.core.client.ConnectorException;
import dynamic.mapper.connector.core.client.ConnectorType;
import dynamic.mapper.connector.amqp.AMQPClient;
import dynamic.mapper.connector.amqp.AMQP10Client;
import dynamic.mapper.connector.http.HttpClient;
import dynamic.mapper.connector.kafka.KafkaClientV2;
import dynamic.mapper.connector.mqtt.MQTT3Client;
import dynamic.mapper.connector.googlepubsub.GooglePubSubClient;
import dynamic.mapper.connector.httppolling.HttpPollingConnector;
import dynamic.mapper.connector.pulsar.MQTTServicePulsarClient;
import dynamic.mapper.connector.pulsar.PulsarConnectorClient;
import dynamic.mapper.connector.webhook.WebHook;
import dynamic.mapper.connector.webhook.WebHookInternal;
import dynamic.mapper.model.status.ConnectorStatusEvent;

import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.core.annotation.Order;

/**
 * Central, tenant-aware registry for connector state.
 * <p>
 * For each tenant, the registry holds:
 * <ul>
 * <li>the connector client instances, keyed by connector identifier
 * ({@link AConnectorClient}), and</li>
 * <li>the last known {@link ConnectorStatusEvent} per connector.</li>
 * </ul>
 * It also holds the {@link ConnectorSpecification} of every supported
 * {@link ConnectorType}. The specifications are tenant-independent and are
 * filled once by {@link #registerConnectors()}.
 * <p>
 * <b>Tenant lifecycle:</b> as a {@link TenantScoped} component, the registry
 * creates the per-tenant status map in {@link #initializeTenant(String)}, closes
 * all clients of a tenant in {@link #stopTenant(String)}, and drops all
 * per-tenant state in {@link #removeTenant(String)}. After removal, the tenant
 * is remembered so that a late status update from a stray thread does not
 * re-create its status map. Re-subscribing the tenant clears this marker.
 * <p>
 * <b>Thread safety:</b> all maps are {@link ConcurrentHashMap} instances, so
 * clients can be registered, looked up, and removed concurrently from
 * connector, notification, and tenant-lifecycle threads.
 */
@Component
@Slf4j
@Order(TenantScoped.ORDER_CONNECTORS)
public class ConnectorRegistry implements TenantScoped {

    // Structure: < Tenant, < ConnectorIdentifier, ConnectorInstance > >
    private Map<String, Map<String, AConnectorClient>> connectorTenantMap = new ConcurrentHashMap<>();
    
    // Structure: < ConnectorType, ConnectorSpecification >
    private Map<ConnectorType, ConnectorSpecification> connectorSpecificationMap = new ConcurrentHashMap<>();

    // Structure: < Tenant, < ConnectorIdentifier, ConnectorStatusEvent > >
    private Map<String, Map<String, ConnectorStatusEvent>> connectorStatusMaps = new ConcurrentHashMap<>();

    /**
     * Tenants whose resources were removed (unsubscribed). A late status update from a stray
     * thread must not re-create their status map via {@link #getConnectorStatusMap}; cleared
     * again by {@link #initializeTenant} when the tenant re-subscribes.
     */
    private final Set<String> removedTenants = ConcurrentHashMap.newKeySet();

    // === Existing Methods ===

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

        // computeIfAbsent is atomic on ConcurrentHashMap — no TOCTOU race for new tenants.
        // ConcurrentHashMap as inner map so concurrent registrations for the same tenant are safe.
        Map<String, AConnectorClient> connectorMap = connectorTenantMap
                .computeIfAbsent(tenant, k -> new ConcurrentHashMap<>());

        AConnectorClient existing = connectorMap.putIfAbsent(client.getConnectorIdentifier(), client);
        if (existing == null) {
            log.debug("{} - Adding new client with id {}...", tenant, client.getConnectorIdentifier());
        } else {
            log.debug("{} - Client {} is already registered!", tenant, client.getConnectorIdentifier());
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
                try {
                    // same as unregisterClient: release the dispatcher to avoid memory leaks
                    entryNext.getValue().setDispatcher(null);
                    // cleanup() = stop housekeeping + close + clear subscription state
                    entryNext.getValue().cleanup();
                } catch (Exception e) {
                    // one failing client must not leave the remaining ones running
                    log.warn("{} - Error cleaning up connector {}: {}", tenant, entryNext.getKey(), e.getMessage());
                }
                iterator.remove();
            }
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
                // Await async disconnect before stopping housekeeping so the second
                // disconnect() call inside close() is a no-op (idempotent via isConnected guard)
                try {
                    client.submitDisconnect().get(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    log.warn("{} - Timeout or error waiting for disconnect of {}: {}", tenant, identifier,
                            e.getMessage());
                }
                client.stopHousekeepingAndClose();

                // store last connector status for monitoring
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
        // computeIfAbsent ensures the map always exists after initializeTenant,
        // and guards against races during startup where updateStatus is called before
        // initializeTenant has run for this tenant.
        if (removedTenants.contains(tenant)) {
            // tenant unsubscribed: hand out a detached map so the update is dropped, not stored
            return new ConcurrentHashMap<>();
        }
        return connectorStatusMaps.computeIfAbsent(tenant, k -> new ConcurrentHashMap<>());
    }

    @Override
    public void initializeTenant(dynamic.mapper.core.TenantInitContext ctx) {
        initializeTenant(ctx.tenant());
    }

    public void initializeTenant(String tenant) {
        removedTenants.remove(tenant);
        connectorStatusMaps.put(tenant, new ConcurrentHashMap<>());
    }

    /**
     * Drops every per-tenant structure held here. Called from the unsubscribe cleanup, after
     * {@link #unregisterAllClientsForTenant(String)} has closed the clients — that method empties
     * the tenant's client map but leaves the (now empty) tenant entry behind, so remove it here
     * too and keep the cleanup contract uniform: no key of an unsubscribed tenant survives.
     */
    @Override
    public void stopTenant(String tenant) {
        try {
            unregisterAllClientsForTenant(tenant);
        } catch (ConnectorRegistryException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    @Override
    public void removeTenant(String tenant) {
        removedTenants.add(tenant);
        connectorStatusMaps.remove(tenant);
        connectorTenantMap.remove(tenant);
    }

    public void registerConnectors() throws ConnectorRegistryException, ConnectorException {
        connectorSpecificationMap.put(ConnectorType.MQTT, new MQTT3Client().getConnectorSpecification());
        connectorSpecificationMap.put(ConnectorType.KAFKA, new KafkaClientV2().getConnectorSpecification());
        connectorSpecificationMap.put(ConnectorType.WEB_HOOK, new WebHook().getConnectorSpecification());
        connectorSpecificationMap.put(ConnectorType.WEB_HOOK_INTERNAL, new WebHookInternal().getConnectorSpecification());
        connectorSpecificationMap.put(ConnectorType.HTTP, new HttpClient().getConnectorSpecification());
        connectorSpecificationMap.put(ConnectorType.PULSAR, new PulsarConnectorClient().getConnectorSpecification());
        connectorSpecificationMap.put(ConnectorType.CUMULOCITY_MQTT_SERVICE_PULSAR,
                new MQTTServicePulsarClient().getConnectorSpecification());
        connectorSpecificationMap.put(ConnectorType.AMQP_091, new AMQPClient().getConnectorSpecification());
        connectorSpecificationMap.put(ConnectorType.AMQP_10, new AMQP10Client().getConnectorSpecification());
        connectorSpecificationMap.put(ConnectorType.GOOGLE_PUBSUB, new GooglePubSubClient().getConnectorSpecification());
        connectorSpecificationMap.put(ConnectorType.REST_POLLING, new HttpPollingConnector().getConnectorSpecification());
    }

    @PreDestroy
    public void cleanup() {
        log.info("Cleaning up ConnectorRegistry");

        // Unregister all clients for all tenants
        for (String tenant : new HashMap<>(connectorTenantMap).keySet()) {
            try {
                unregisterAllClientsForTenant(tenant);
            } catch (ConnectorRegistryException e) {
                log.warn("Error unregistering clients for tenant {}: {}", tenant, e.getMessage());
            }
        }

        // Clear all maps
        connectorTenantMap.clear();
        connectorStatusMaps.clear();
        connectorSpecificationMap.clear();

        log.info("ConnectorRegistry cleanup completed");
    }
}