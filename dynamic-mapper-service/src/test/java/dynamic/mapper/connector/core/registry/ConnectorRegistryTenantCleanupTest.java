package dynamic.mapper.connector.core.registry;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import dynamic.mapper.connector.core.client.AConnectorClient;

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
        registry.initializeResources(TENANT);
        registry.getConnectorStatusMap(TENANT).put("a", mock(dynamic.mapper.model.status.ConnectorStatusEvent.class));

        registry.removeResources(TENANT);
        registry.getConnectorStatusMap(TENANT).put("a", mock(dynamic.mapper.model.status.ConnectorStatusEvent.class));

        org.junit.jupiter.api.Assertions.assertTrue(registry.getConnectorStatusMap(TENANT).isEmpty());
    }

    @Test
    void resubscribeRestoresTheStatusMap() {
        ConnectorRegistry registry = new ConnectorRegistry();
        registry.removeResources(TENANT);

        registry.initializeResources(TENANT);
        registry.getConnectorStatusMap(TENANT).put("a", mock(dynamic.mapper.model.status.ConnectorStatusEvent.class));

        org.junit.jupiter.api.Assertions.assertEquals(1, registry.getConnectorStatusMap(TENANT).size());
    }
}
