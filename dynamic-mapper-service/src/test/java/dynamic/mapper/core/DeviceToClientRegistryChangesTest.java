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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import dynamic.mapper.model.device.DeviceToClientMapRepresentation;

class DeviceToClientRegistryChangesTest {

    private static final String TENANT = "t1";
    private final DeviceToClientRegistry registry = new DeviceToClientRegistry();

    @Test
    void theStartupSnapshotIsNotAPendingChange() {
        DeviceToClientMapRepresentation persisted = new DeviceToClientMapRepresentation();
        persisted.setId("mo");
        persisted.setDeviceToClientMap(Map.of("1", "clientA"));

        registry.initializeDeviceToClientMap(TENANT, persisted);

        assertEquals("clientA", registry.resolveDeviceToClient(TENANT, "1"));
        assertTrue(registry.getPendingClientChanges(TENANT).isEmpty());
    }

    @Test
    void localChangesAreTrackedAsUpsertsAndRemovals() {
        registry.addOrUpdateClientRelation(TENANT, "clientA", "1");
        registry.addOrUpdateClientRelation(TENANT, "clientB", "2");
        registry.removeClientRelation(TENANT, "2");

        Map<String, String> pending = registry.getPendingClientChanges(TENANT);

        assertEquals("clientA", pending.get("1"));
        assertTrue(pending.containsKey("2"));
        assertNull(pending.get("2"));
    }

    @Test
    void aChangeMadeAfterTheSnapshotStaysPendingWhenTheSnapshotIsAcknowledged() {
        registry.addOrUpdateClientRelation(TENANT, "clientA", "1");
        Map<String, String> snapshot = registry.getPendingClientChanges(TENANT);

        registry.removeClientRelation(TENANT, "1");
        registry.acknowledgeClientChanges(TENANT, snapshot);

        Map<String, String> pending = registry.getPendingClientChanges(TENANT);
        assertTrue(pending.containsKey("1"));
        assertNull(pending.get("1"));
    }

    @Test
    void aRemovedRelationIsNotRestoredByALateStartupLoad() {
        registry.addOrUpdateClientRelation(TENANT, "clientA", "1");
        registry.removeClientRelation(TENANT, "1");
        DeviceToClientMapRepresentation persisted = new DeviceToClientMapRepresentation();
        persisted.setId("mo");
        persisted.setDeviceToClientMap(Map.of("1", "clientA", "2", "clientB"));

        registry.initializeDeviceToClientMap(TENANT, persisted);

        assertNull(registry.resolveDeviceToClient(TENANT, "1"));
        assertEquals("clientB", registry.resolveDeviceToClient(TENANT, "2"));
    }
}
