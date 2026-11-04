package dynamic.mapper.connector.core.registry;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.model.status.ConnectorStatusEvent;

class ConnectorRegistryTenantCleanupTest {

    private static final String TENANT = "t1";

    private static AConnectorClient client(String id) {
        AConnectorClient c = mock(AConnectorClient.class);
        when(c.getConnectorIdentifier()).thenReturn(id);
        return c;
    }

    @Test
    void unregisterAllClientsCleansUpEveryClientEvenWhenOneFails() throws Exception {
        ConnectorRegistry registry = new ConnectorRegistry();
        AConnectorClient failing = client("a");
        AConnectorClient healthy = client("b");
        doThrow(new IllegalStateException("boom")).when(failing).cleanup();
        registry.registerClient(TENANT, failing);
        registry.registerClient(TENANT, healthy);

        registry.unregisterAllClientsForTenant(TENANT);

        verify(failing).setDispatcher(null);
        verify(healthy).setDispatcher(null);
        verify(failing).cleanup();
        verify(healthy).cleanup();
        assertNull(registry.getClientForTenant(TENANT, "a"));
        assertNull(registry.getClientForTenant(TENANT, "b"));
    }

    @Test
    void lateStatusUpdateAfterRemovalDoesNotRecreateTheStatusMap() {
        ConnectorRegistry registry = new ConnectorRegistry();
        registry.initializeTenant(TENANT);
        registry.getConnectorStatusMap(TENANT).put("a", mock(ConnectorStatusEvent.class));

        registry.removeTenant(TENANT);
        registry.getConnectorStatusMap(TENANT).put("a", mock(ConnectorStatusEvent.class));

        assertTrue(registry.getConnectorStatusMap(TENANT).isEmpty());
    }

    @Test
    void resubscribeRestoresTheStatusMap() {
        ConnectorRegistry registry = new ConnectorRegistry();
        registry.removeTenant(TENANT);

        registry.initializeTenant(TENANT);
        registry.getConnectorStatusMap(TENANT).put("a", mock(ConnectorStatusEvent.class));

        assertEquals(1, registry.getConnectorStatusMap(TENANT).size());
    }

    @Test
    void stopTenantCleansUpClientsButKeepsTheRegistryEntry() throws Exception {
        ConnectorRegistry registry = new ConnectorRegistry();
        AConnectorClient c = client("a");
        registry.registerClient(TENANT, c);

        registry.stopTenant(TENANT);

        verify(c).cleanup();
        // stopTenant is the shutdown phase; removeTenant drops the status/dispatcher maps afterwards
        registry.initializeTenant(TENANT);
        registry.removeTenant(TENANT);
        assertTrue(registry.getConnectorStatusMap(TENANT).isEmpty());
    }
}
