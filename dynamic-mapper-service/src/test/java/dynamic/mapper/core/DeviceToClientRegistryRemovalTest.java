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

package dynamic.mapper.core;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.Test;

import dynamic.mapper.model.device.DeviceToClientMapRepresentation;
import java.lang.reflect.Field;

/** What the tenant registries hold for a tenant must be gone after the tenant unsubscribes. */
class DeviceToClientRegistryRemovalTest {

    private static final String TENANT = "t1";

    @Test
    void removeDeviceToClientStateDropsRelationsPendingChangesAndMapId() {
        DeviceToClientRegistry registry = new DeviceToClientRegistry();
        DeviceToClientMapRepresentation persisted = new DeviceToClientMapRepresentation();
        persisted.setId("mo");
        persisted.setDeviceToClientMap(Map.of("1", "clientA"));
        registry.initializeDeviceToClientMap(TENANT, persisted);
        registry.addOrUpdateClientRelation(TENANT, "clientB", "2");

        registry.removeDeviceToClientState(TENANT);

        assertNull(registry.resolveDeviceToClient(TENANT, "1"));
        assertNull(registry.resolveDeviceToClient(TENANT, "2"));
        assertNull(registry.getDeviceToClientMapId(TENANT));
        assertTrue(registry.getPendingClientChanges(TENANT).isEmpty());
    }

    @Test
    void removeDeviceToClientStateRecordsNoPendingChange() {
        DeviceToClientRegistry registry = new DeviceToClientRegistry();
        registry.addOrUpdateClientRelation(TENANT, "clientA", "1");

        registry.removeDeviceToClientState(TENANT);

        // clearCacheDeviceToClient would leave a pending removal that gets persisted for a gone tenant
        assertTrue(registry.getPendingClientChanges(TENANT).isEmpty());
    }

    @Test
    void removeDeviceToClientStateKeepsOtherTenants() {
        DeviceToClientRegistry registry = new DeviceToClientRegistry();
        registry.addOrUpdateClientRelation(TENANT, "clientA", "1");
        registry.addOrUpdateClientRelation("t2", "clientB", "1");

        registry.removeDeviceToClientState(TENANT);

        assertNull(registry.resolveDeviceToClient(TENANT, "1"));
        assertEquals("clientB", registry.resolveDeviceToClient("t2", "1"));
    }

    @Test
    void deleteExtensionsRemovesTheTenantEntry() throws Exception {
        ExtensionInboundRegistry registry = new ExtensionInboundRegistry();
        registry.initializeTenant(TENANT);

        registry.removeTenant(TENANT);

        Field f = ExtensionInboundRegistry.class.getDeclaredField("tenantExtensionProcessors");
        f.setAccessible(true);
        assertTrue(((Map<?, ?>) f.get(registry)).isEmpty(), "entry must be removed, not replaced by an empty map");
    }
}
