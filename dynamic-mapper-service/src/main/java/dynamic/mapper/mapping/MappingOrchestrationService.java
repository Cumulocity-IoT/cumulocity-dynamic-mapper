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

package dynamic.mapper.mapping;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import dynamic.mapper.configuration.ConnectorConfiguration;
import dynamic.mapper.configuration.ConnectorId;
import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.connector.core.registry.ConnectorRegistryException;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Mapping;
import lombok.extern.slf4j.Slf4j;

/**
 * Keeps the connectors in step with changes to mappings and to the deployment map.
 * <p>
 * After a mapping is created, updated, deleted, or (de)activated, or after the deployment map changes,
 * the affected connectors must update their subscriptions. {@link MappingService} stores and caches
 * mappings; this service tells the connectors. It sits between the REST controllers and both, so that
 * the controllers share one implementation instead of each repeating the connector loops.
 */
@Slf4j
@Service
public class MappingOrchestrationService {

    private final MappingService mappingService;
    private final ConnectorRegistry connectorRegistry;

    public MappingOrchestrationService(MappingService mappingService, ConnectorRegistry connectorRegistry) {
        this.mappingService = mappingService;
        this.connectorRegistry = connectorRegistry;
    }

    // === Mapping created, updated, deleted ===

    /**
     * Updates caches and connector subscriptions after a mapping was created. A failure to update a
     * cache is logged and not thrown: the mapping is stored, and the cache can be rebuilt.
     */
    public void onMappingCreated(String tenant, Mapping mapping) throws ConnectorRegistryException {
        if (Direction.OUTBOUND.equals(mapping.getDirection())) {
            rebuildOutboundCache(tenant, mapping, "creating");
            return;
        }
        // A brand-new mapping has no deployment yet, so notifying all connectors is wasteful.
        // Notify only connectors it is already deployed to (covers import/restore scenarios).
        notifyDeployedConnectorsInbound(tenant, mapping, true);

        try {
            mappingService.addMappingInboundToCache(tenant, mapping.getId(), mapping);
            log.debug("{} - Added inbound mapping to cache: {}", tenant, mapping.getId());
        } catch (Exception e) {
            log.error("{} - Failed to add mapping to cache: {}", tenant, mapping.getId(), e);
        }
    }

    /** Updates caches and connector subscriptions after a mapping was changed. */
    public void onMappingUpdated(String tenant, Mapping mapping) throws ConnectorRegistryException {
        if (Direction.OUTBOUND.equals(mapping.getDirection())) {
            rebuildOutboundCache(tenant, mapping, "updating");
            return;
        }
        // Only notify connectors where the mapping is deployed; others are unaffected.
        notifyDeployedConnectorsInbound(tenant, mapping, false);

        // Remove old entry then add updated one so stale topics are cleaned from the resolver tree
        try {
            mappingService.removeFromMappingFromCaches(tenant, mapping);
            mappingService.addMappingInboundToCache(tenant, mapping.getId(), mapping);
            log.debug("{} - Updated inbound mapping in cache: {}", tenant, mapping.getId());
        } catch (Exception e) {
            log.error("{} - Failed to update mapping in cache: {}", tenant, mapping.getId(), e);
        }
    }

    /** Removes the subscriptions of a deleted inbound mapping from all connectors. Outbound mappings have none. */
    public void onMappingDeleted(String tenant, Mapping mapping) throws ConnectorRegistryException {
        if (Direction.OUTBOUND.equals(mapping.getDirection())) {
            return;
        }
        connectorRegistry.getClientsForTenant(tenant).values().forEach(client -> {
            try {
                client.deleteActiveSubscription(mapping);
                log.debug("{} - Deleted subscription for connector after deleting mapping: {}",
                        tenant, mapping.getId());
            } catch (Exception e) {
                // Continue with the other connectors
                log.error("{} - Failed to delete subscription for connector for mapping: {}",
                        tenant, mapping.getId(), e);
            }
        });
    }

    private void rebuildOutboundCache(String tenant, Mapping mapping, String action) {
        try {
            mappingService.rebuildMappingCaches(tenant, ConnectorId.INTERNAL);
            log.debug("{} - Rebuilt outbound cache after {} mapping: {}", tenant, action, mapping.getId());
        } catch (Exception e) {
            // The mapping is stored; the cache rebuild can be retried.
            log.error("{} - Failed to rebuild outbound cache for mapping: {}", tenant, mapping.getId(), e);
        }
    }

    /**
     * Notifies only the connectors where this mapping is deployed about a subscription change, instead
     * of iterating all connectors for an operation that affects a subset.
     */
    private void notifyDeployedConnectorsInbound(String tenant, Mapping mapping, boolean create)
            throws ConnectorRegistryException {
        List<String> deployedConnectorIds = mappingService.getDeploymentMapEntry(tenant, mapping.getIdentifier());
        if (deployedConnectorIds == null || deployedConnectorIds.isEmpty()) {
            log.debug("{} - Mapping {} has no deployed connectors, skipping subscription update",
                    tenant, mapping.getId());
            return;
        }
        for (String connectorId : deployedConnectorIds) {
            try {
                AConnectorClient client = connectorRegistry.getClientForTenant(tenant, connectorId);
                client.updateSubscriptionForInbound(mapping, create, false);
                log.debug("{} - Updated subscription on connector {} after {} mapping: {}",
                        tenant, connectorId, create ? "creating" : "updating", mapping.getId());
            } catch (ConnectorRegistryException e) {
                log.warn("{} - Connector {} not found while updating mapping {}: {}",
                        tenant, connectorId, mapping.getId(), e.getMessage());
            } catch (Exception e) {
                log.error("{} - Failed to update subscription on connector {} for mapping {}: {}",
                        tenant, connectorId, mapping.getId(), e);
            }
        }
    }

    // === Mapping (de)activated ===

    /**
     * Applies the activation state of a mapping to the connectors it is deployed to. Other connectors
     * never subscribed to it and reconcile on their own when the deployment map changes.
     *
     * @return the connectors whose inbound subscription could not be updated (identifier to name);
     *         empty if every connector accepted the change
     */
    public Map<String, String> applyActivation(String tenant, Mapping mapping) throws ConnectorRegistryException {
        List<String> deployedConnectorIds = mappingService.getDeploymentMapEntry(tenant, mapping.getIdentifier());
        Map<String, String> failed = new HashMap<>();
        for (AConnectorClient client : connectorRegistry.getClientsForTenant(tenant).values()) {
            if (!deployedConnectorIds.contains(client.getConnectorIdentifier())) {
                continue;
            }
            if (!client.applyMappingSubscription(mapping, false, true)) {
                ConnectorConfiguration conf = client.getConnectorConfiguration();
                failed.put(conf.getIdentifier(), conf.getName());
            }
        }
        return failed;
    }

    /**
     * Makes the connectors this mapping is deployed to drop it after the service deactivated it:
     * inbound connectors release their topic subscription (reference-counted, so a topic shared with
     * another active mapping stays subscribed), outbound connectors remove it from their applied set.
     * Without this the mapping would be flagged inactive while its topic stayed subscribed, and every
     * further message would keep failing.
     * <p>
     * Best-effort per connector: one unreachable connector must not stop the others.
     */
    @EventListener
    public void onMappingDeactivated(MappingDeactivatedEvent event) {
        String tenant = event.tenant();
        Mapping mapping = event.mapping();
        List<String> deployedConnectorIds = mappingService.getDeploymentMapEntry(tenant, mapping.getIdentifier());
        if (deployedConnectorIds == null || deployedConnectorIds.isEmpty()) {
            return;
        }
        for (String connectorId : deployedConnectorIds) {
            try {
                connectorRegistry.getClientForTenant(tenant, connectorId)
                        .applyMappingSubscription(mapping, false, true);
            } catch (Exception e) {
                log.warn("{} - Could not update subscription on connector {} while deactivating mapping {}: {}",
                        tenant, connectorId, mapping.displayLabel(), e.getMessage());
            }
        }
    }

    // === Deployment map or mappings reloaded ===

    /**
     * Rebuilds all mapping caches, then reconciles every connector against them and the deployment map.
     * This (un)subscribes inbound mappings and rebuilds the effective outbound set, so mappings that were
     * removed or un-deployed since the last reload are dropped as well as added.
     */
    public void reloadMappings(String tenant) throws ConnectorRegistryException {
        mappingService.rebuildMappingCaches(tenant, ConnectorId.INTERNAL);
        reconcileConnectors(tenant, id -> true);
    }

    /**
     * Reconciles the subscriptions of the connectors accepted by {@code affected} against the current
     * deployment map. Each connector is reconciled on its own, so a failure on one does not stop the others.
     */
    public void reconcileConnectors(String tenant, Predicate<String> affected) throws ConnectorRegistryException {
        Map<String, AConnectorClient> connectorMap = connectorRegistry.getClientsForTenant(tenant);
        if (connectorMap == null) {
            return;
        }
        for (AConnectorClient client : connectorMap.values()) {
            if (!affected.test(client.getConnectorIdentifier())) {
                continue;
            }
            try {
                client.reconcileSubscriptions();
            } catch (Exception e) {
                log.error("{} - Error reconciling subscriptions for connector {}",
                        tenant, client.getConnectorIdentifier(), e);
            }
        }
    }
}
