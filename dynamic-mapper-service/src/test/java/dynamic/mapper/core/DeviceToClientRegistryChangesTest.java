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
