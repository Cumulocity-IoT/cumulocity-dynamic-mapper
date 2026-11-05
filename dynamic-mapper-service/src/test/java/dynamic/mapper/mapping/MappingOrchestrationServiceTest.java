package dynamic.mapper.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dynamic.mapper.configuration.ConnectorConfiguration;
import dynamic.mapper.configuration.ConnectorId;
import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Mapping;

/** What the connectors are told when a mapping or the deployment map changes. */
class MappingOrchestrationServiceTest {

    private static final String TENANT = "t1";

    private final MappingService mappingService = mock(MappingService.class);
    private final ConnectorRegistry registry = mock(ConnectorRegistry.class);
    private final MappingOrchestrationService service = new MappingOrchestrationService(mappingService, registry);
    private final Map<String, AConnectorClient> clients = new LinkedHashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        when(registry.getClientsForTenant(TENANT)).thenReturn(clients);
    }

    private AConnectorClient client(String id) throws Exception {
        AConnectorClient c = mock(AConnectorClient.class);
        when(c.getConnectorIdentifier()).thenReturn(id);
        ConnectorConfiguration conf = new ConnectorConfiguration();
        conf.setIdentifier(id);
        conf.setName("name-" + id);
        when(c.getConnectorConfiguration()).thenReturn(conf);
        when(c.updateSubscriptionForInbound(any(), anyBoolean(), anyBoolean())).thenReturn(true);
        clients.put(id, c);
        when(registry.getClientForTenant(TENANT, id)).thenReturn(c);
        return c;
    }

    private Mapping mapping(Direction direction) {
        Mapping m = new Mapping();
        m.setId("m-id");
        m.setIdentifier("m1");
        m.setDirection(direction);
        return m;
    }

    @Test
    void aCreatedInboundMappingNotifiesOnlyTheConnectorsItIsDeployedTo() throws Exception {
        AConnectorClient deployed = client("c1");
        AConnectorClient other = client("c2");
        Mapping m = mapping(Direction.INBOUND);
        when(mappingService.getDeploymentMapEntry(TENANT, "m1")).thenReturn(List.of("c1"));

        service.onMappingCreated(TENANT, m);

        verify(deployed).updateSubscriptionForInbound(m, true, false);
        verify(other, never()).updateSubscriptionForInbound(any(), anyBoolean(), anyBoolean());
        verify(mappingService).addMappingInboundToCache(TENANT, "m-id", m);
    }

    @Test
    void anUpdatedInboundMappingReplacesItsCacheEntry() throws Exception {
        Mapping m = mapping(Direction.INBOUND);
        when(mappingService.getDeploymentMapEntry(TENANT, "m1")).thenReturn(List.of());

        service.onMappingUpdated(TENANT, m);

        verify(mappingService).removeFromMappingFromCaches(TENANT, m);
        verify(mappingService).addMappingInboundToCache(TENANT, "m-id", m);
    }

    @Test
    void anOutboundMappingRebuildsTheCachesAndNotifiesNoConnector() throws Exception {
        AConnectorClient c = client("c1");

        service.onMappingCreated(TENANT, mapping(Direction.OUTBOUND));
        service.onMappingUpdated(TENANT, mapping(Direction.OUTBOUND));

        verify(mappingService, org.mockito.Mockito.times(2)).rebuildMappingCaches(TENANT, ConnectorId.INTERNAL);
        verify(c, never()).updateSubscriptionForInbound(any(), anyBoolean(), anyBoolean());
    }

    @Test
    void aCacheFailureAfterCreatingAMappingIsNotThrown() throws Exception {
        doThrow(new RuntimeException("cache")).when(mappingService).rebuildMappingCaches(TENANT, ConnectorId.INTERNAL);
        service.onMappingCreated(TENANT, mapping(Direction.OUTBOUND)); // must not throw
    }

    @Test
    void aDeletedInboundMappingIsRemovedFromEveryConnectorEvenWhenOneFails() throws Exception {
        AConnectorClient failing = client("c1");
        AConnectorClient healthy = client("c2");
        Mapping m = mapping(Direction.INBOUND);
        doThrow(new RuntimeException("broker down")).when(failing).deleteActiveSubscription(m);

        service.onMappingDeleted(TENANT, m);

        verify(healthy).deleteActiveSubscription(m);
    }

    @Test
    void aDeletedOutboundMappingTouchesNoConnector() throws Exception {
        AConnectorClient c = client("c1");
        service.onMappingDeleted(TENANT, mapping(Direction.OUTBOUND));
        verify(c, never()).deleteActiveSubscription(any());
    }

    @Test
    void activationReportsTheDeployedConnectorsThatRefusedTheInboundChange() throws Exception {
        AConnectorClient refusing = client("c1");
        AConnectorClient accepting = client("c2");
        AConnectorClient notDeployed = client("c3");
        Mapping m = mapping(Direction.INBOUND);
        when(mappingService.getDeploymentMapEntry(TENANT, "m1")).thenReturn(List.of("c1", "c2"));
        when(refusing.updateSubscriptionForInbound(m, false, true)).thenReturn(false);

        Map<String, String> failed = service.applyActivation(TENANT, m);

        assertEquals(Map.of("c1", "name-c1"), failed);
        verify(accepting).updateSubscriptionForInbound(m, false, true);
        verify(notDeployed, never()).updateSubscriptionForInbound(any(), anyBoolean(), anyBoolean());
    }

    @Test
    void activationOfAnOutboundMappingUsesTheOutboundSubscription() throws Exception {
        AConnectorClient c = client("c1");
        Mapping m = mapping(Direction.OUTBOUND);
        when(mappingService.getDeploymentMapEntry(TENANT, "m1")).thenReturn(List.of("c1"));

        assertTrue(service.applyActivation(TENANT, m).isEmpty());

        verify(c).updateSubscriptionForOutbound(m, false, true);
    }

    @Test
    void reconcileTouchesOnlyTheAffectedConnectorsAndSurvivesAFailure() throws Exception {
        AConnectorClient failing = client("c1");
        AConnectorClient healthy = client("c2");
        AConnectorClient unaffected = client("c3");
        doThrow(new RuntimeException("boom")).when(failing).reconcileSubscriptions();

        service.reconcileConnectors(TENANT, Set.of("c1", "c2")::contains);

        verify(healthy).reconcileSubscriptions();
        verify(unaffected, never()).reconcileSubscriptions();
    }

    @Test
    void reloadRebuildsTheCachesBeforeReconcilingEveryConnector() throws Exception {
        AConnectorClient c = client("c1");

        service.reloadMappings(TENANT);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(mappingService, c);
        order.verify(mappingService).rebuildMappingCaches(TENANT, ConnectorId.INTERNAL);
        order.verify(c).reconcileSubscriptions();
    }
}
