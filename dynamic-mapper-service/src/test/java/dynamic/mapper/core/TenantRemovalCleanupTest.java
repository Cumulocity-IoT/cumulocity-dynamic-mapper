package dynamic.mapper.core;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import dynamic.mapper.model.device.DeviceToClientMapRepresentation;

/** What the tenant registries hold for a tenant must be gone after the tenant unsubscribes. */
class TenantRemovalCleanupTest {

    private static final String TENANT = "t1";

    @Test
    void removeDeviceToClientStateDropsRelationsPendingChangesAndMapId() {
        TenantRegistry registry = new TenantRegistry();
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
        TenantRegistry registry = new TenantRegistry();
        registry.addOrUpdateClientRelation(TENANT, "clientA", "1");

        registry.removeDeviceToClientState(TENANT);

        // clearCacheDeviceToClient would leave a pending removal that gets persisted for a gone tenant
        assertTrue(registry.getPendingClientChanges(TENANT).isEmpty());
    }

    @Test
    void removeDeviceToClientStateKeepsOtherTenants() {
        TenantRegistry registry = new TenantRegistry();
        registry.addOrUpdateClientRelation(TENANT, "clientA", "1");
        registry.addOrUpdateClientRelation("t2", "clientB", "1");

        registry.removeDeviceToClientState(TENANT);

        assertNull(registry.resolveDeviceToClient(TENANT, "1"));
        org.junit.jupiter.api.Assertions.assertEquals("clientB", registry.resolveDeviceToClient("t2", "1"));
    }

    @Test
    void deleteExtensionsRemovesTheTenantEntry() throws Exception {
        ExtensionInboundRegistry registry = new ExtensionInboundRegistry();
        registry.initializeExtensions(TENANT);

        registry.deleteExtensions(TENANT);

        java.lang.reflect.Field f = ExtensionInboundRegistry.class.getDeclaredField("tenantExtensionProcessors");
        f.setAccessible(true);
        assertTrue(((Map<?, ?>) f.get(registry)).isEmpty(), "entry must be removed, not replaced by an empty map");
    }
}
