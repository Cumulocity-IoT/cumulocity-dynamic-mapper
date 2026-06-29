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
 */

package dynamic.mapper.service.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;

import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.core.facade.InventoryFacade;

/**
 * Unit tests for {@link DeploymentMapService}, focused on the in-memory deployment map
 * behaviour. Persistence is exercised indirectly: {@code persistDeploymentMap} runs inside
 * {@code subscriptionsService.runForTenant(...)}, which the mock leaves as a no-op, so the
 * tests assert purely on the in-memory state without touching the inventory.
 */
@ExtendWith(MockitoExtension.class)
class DeploymentMapServiceTest {

    private static final String TENANT = "t1";

    @Mock
    private InventoryFacade inventoryApi;

    @Mock
    private ConfigurationRegistry configurationRegistry;

    @Mock
    private MicroserviceSubscriptionsService subscriptionsService;

    @InjectMocks
    private DeploymentMapService service;

    @Test
    void getDeployedConnectors_isReadOnly_doesNotCreateEntry() {
        // Querying an unknown mapping must not insert an (empty) entry into the map.
        List<String> connectors = service.getDeployedConnectors(TENANT, "unknown");

        assertTrue(connectors.isEmpty());
        assertFalse(service.getDeploymentMap(TENANT).containsKey("unknown"),
                "read-only lookup must not pollute the deployment map");
        assertTrue(service.getDeploymentMap(TENANT).isEmpty());
    }

    @Test
    void updateDeployment_deduplicatesConnectors() {
        service.updateDeployment(TENANT, "m1", Arrays.asList("c1", "c1", "c2"));

        assertEquals(List.of("c1", "c2"), service.getDeployedConnectors(TENANT, "m1"));
    }

    @Test
    void getDeployedConnectors_returnsDefensiveCopy() {
        service.updateDeployment(TENANT, "m1", List.of("c1"));

        List<String> connectors = service.getDeployedConnectors(TENANT, "m1");
        connectors.add("hacked");

        assertEquals(List.of("c1"), service.getDeployedConnectors(TENANT, "m1"),
                "mutating the returned list must not affect internal state");
    }

    @Test
    void getDeploymentMap_returnsDeepCopy() {
        service.updateDeployment(TENANT, "m1", List.of("c1"));

        Map<String, List<String>> snapshot = service.getDeploymentMap(TENANT);
        snapshot.get("m1").add("hacked");

        assertEquals(List.of("c1"), service.getDeployedConnectors(TENANT, "m1"),
                "mutating the snapshot's inner list must not affect internal state");
    }

    @Test
    void removeMappingDeployment_removesEntryEvenWhenConnectorListEmpty() {
        // A mapping explicitly deployed to zero connectors still has an entry that must be removed.
        service.updateDeployment(TENANT, "m1", List.of());
        assertTrue(service.getDeploymentMap(TENANT).containsKey("m1"));

        boolean removed = service.removeMappingDeployment(TENANT, "m1");

        assertTrue(removed, "removing an existing (empty) entry must report success");
        assertFalse(service.getDeploymentMap(TENANT).containsKey("m1"));
    }

    @Test
    void removeConnectorFromAllMappings_removesEverywhere() {
        service.updateDeployment(TENANT, "m1", List.of("c1", "c2"));
        service.updateDeployment(TENANT, "m2", List.of("c2", "c3"));

        boolean modified = service.removeConnectorFromAllMappings(TENANT, "c2");

        assertTrue(modified);
        assertEquals(List.of("c1"), service.getDeployedConnectors(TENANT, "m1"));
        assertEquals(List.of("c3"), service.getDeployedConnectors(TENANT, "m2"));
    }

    @Test
    void cleanupStaleConnectors_dropsUnknownConnectorIdentifiers() {
        service.updateDeployment(TENANT, "m1", Arrays.asList("c1", "stale"));

        boolean modified = service.cleanupStaleConnectors(TENANT, Set.of("c1"));

        assertTrue(modified);
        assertEquals(List.of("c1"), service.getDeployedConnectors(TENANT, "m1"));
    }

    @Test
    void isConnectorDeployed_reflectsConfiguration() {
        service.updateDeployment(TENANT, "m1", List.of("c1"));

        assertTrue(service.isConnectorDeployed(TENANT, "m1", "c1"));
        assertFalse(service.isConnectorDeployed(TENANT, "m1", "c2"));
        assertFalse(service.isConnectorDeployed(TENANT, "unknown", "c1"));
    }
}
