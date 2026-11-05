package dynamic.mapper.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import dynamic.mapper.configuration.ConnectorConfiguration;
import dynamic.mapper.configuration.ConnectorConfigurationService;
import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.configuration.ServiceConfigurationService;
import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.connector.core.client.ConnectorType;
import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.core.ConnectorLifecycleService.ConnectorOperationException;
import dynamic.mapper.core.ConnectorLifecycleService.Reason;
import dynamic.mapper.mapping.deployment.DeploymentMapService;
import dynamic.mapper.notification.NotificationSubscriber;

/**
 * The connector flows that used to live in the controllers: what is refused, and which steps always run.
 */
class ConnectorLifecycleServiceTest {

    private static final String TENANT = "t1";
    private static final String ID = "c1";

    private final ConnectorRegistry registry = mock(ConnectorRegistry.class);
    private final ConnectorConfigurationService connectorConfigs = mock(ConnectorConfigurationService.class);
    private final ServiceConfigurationService serviceConfigs = mock(ServiceConfigurationService.class);
    private final DeploymentMapService deploymentMapService = mock(DeploymentMapService.class);
    private final ServiceRegistry serviceRegistry = mock(ServiceRegistry.class);
    private final NotificationSubscriber notifications = mock(NotificationSubscriber.class);
    private ConnectorLifecycleService service;

    @BeforeEach
    void setUp() {
        when(serviceRegistry.getNotificationSubscriber()).thenReturn(notifications);
        ServiceConfiguration config = new ServiceConfiguration();
        config.setOutboundMappingEnabled(true);
        when(serviceConfigs.getServiceConfiguration(TENANT)).thenReturn(config);
        service = new ConnectorLifecycleService(registry, mock(ConnectorClientFactory.class), serviceRegistry,
                connectorConfigs, serviceConfigs, deploymentMapService, "");
    }

    private ConnectorConfiguration config(ConnectorType type, boolean enabled) {
        ConnectorConfiguration c = new ConnectorConfiguration();
        c.setIdentifier(ID);
        c.setName("Connector 1");
        c.setConnectorType(type);
        c.setEnabled(enabled);
        return c;
    }

    @Test
    void deleteReportsAMissingConnectorAsNotFound() throws Exception {
        ConnectorOperationException e = assertThrows(ConnectorOperationException.class,
                () -> service.deleteConnector(TENANT, ID));
        assertEquals(Reason.NOT_FOUND, e.getReason());
        verify(connectorConfigs, never()).deleteConnectorConfiguration(anyString());
    }

    @Test
    void deleteRefusesTheHttpConnector() throws Exception {
        when(connectorConfigs.getConnectorConfiguration(ID, TENANT)).thenReturn(config(ConnectorType.HTTP, true));
        ConnectorOperationException e = assertThrows(ConnectorOperationException.class,
                () -> service.deleteConnector(TENANT, ID));
        assertEquals(Reason.NOT_ALLOWED, e.getReason());
        verify(connectorConfigs, never()).deleteConnectorConfiguration(anyString());
    }

    @Test
    void deleteStillRemovesTheConfigurationWhenTheResourceCleanupFails() throws Exception {
        when(connectorConfigs.getConnectorConfiguration(ID, TENANT)).thenReturn(config(ConnectorType.MQTT, true));
        when(registry.getClientForTenant(TENANT, ID)).thenThrow(new RuntimeException("registry down"));

        assertThrows(ConnectorOperationException.class, () -> service.deleteConnector(TENANT, ID));

        verify(connectorConfigs).deleteConnectorConfiguration(ID);
        verify(deploymentMapService).removeConnectorFromAllMappings(TENANT, ID);
        verify(registry).removeClientFromStatusMap(TENANT, ID);
    }

    @Test
    void deleteTearsDownInOrderAndCallsDeleteResourcesOnTheClient() throws Exception {
        when(connectorConfigs.getConnectorConfiguration(ID, TENANT)).thenReturn(config(ConnectorType.MQTT, true));
        AConnectorClient client = mock(AConnectorClient.class);
        when(registry.getClientForTenant(TENANT, ID)).thenReturn(client);

        service.deleteConnector(TENANT, ID);

        verify(client).deleteResources();
        InOrder order = inOrder(registry, notifications, connectorConfigs, deploymentMapService);
        order.verify(registry).unregisterClient(TENANT, ID);
        order.verify(notifications).unsubscribeDeviceSubscriberByConnector(TENANT, ID);
        order.verify(connectorConfigs).deleteConnectorConfiguration(ID);
        order.verify(deploymentMapService).removeConnectorFromAllMappings(TENANT, ID);
    }

    @Test
    void disconnectOfAConnectorThatIsNotRunningOnlyMarksItDisabled() throws Exception {
        ConnectorConfiguration stored = config(ConnectorType.MQTT, true);
        when(connectorConfigs.getConnectorConfiguration(ID, TENANT)).thenReturn(stored);

        service.disconnect(TENANT, ID);

        assertFalse(stored.getEnabled());
        verify(connectorConfigs).saveConnectorConfiguration(stored);
        verify(registry, never()).unregisterClient(anyString(), anyString());
        verify(notifications, never()).notificationSubscriberReconnect(anyString());
    }

    @Test
    void disconnectReportsAFailedNotificationReconnectAfterDisconnecting() throws Exception {
        ConnectorConfiguration stored = config(ConnectorType.MQTT, true);
        when(connectorConfigs.getConnectorConfiguration(ID, TENANT)).thenReturn(stored);
        AConnectorClient client = mock(AConnectorClient.class);
        when(client.getConnectorIdentifier()).thenReturn(ID);
        when(registry.getClientForTenant(TENANT, ID)).thenReturn(client);
        when(notifications.notificationSubscriberReconnect(TENANT)).thenReturn(false);

        ConnectorOperationException e = assertThrows(ConnectorOperationException.class,
                () -> service.disconnect(TENANT, ID));

        assertEquals(Reason.FAILED, e.getReason());
        assertTrue(e.getMessage().contains("was disconnected"), e.getMessage());
        verify(registry).unregisterClient(TENANT, ID);
    }

    @Test
    void disconnectReportsAMissingConnectorAsNotFound() {
        ConnectorOperationException e = assertThrows(ConnectorOperationException.class,
                () -> service.disconnect(TENANT, ID));
        assertEquals(Reason.NOT_FOUND, e.getReason());
    }

    @Test
    void connectReconnectsAnAlreadyRegisteredClientInsteadOfCreatingANewOne() throws Exception {
        ConnectorConfiguration stored = config(ConnectorType.MQTT, false);
        when(connectorConfigs.getConnectorConfiguration(ID, TENANT)).thenReturn(stored);
        AConnectorClient client = mock(AConnectorClient.class);
        when(registry.getClientForTenant(TENANT, ID)).thenReturn(client);
        when(client.supportedDirections()).thenReturn(java.util.List.of());
        doReturn(CompletableFuture.completedFuture(null)).when(client).reconnect();

        service.connect(TENANT, ID);

        assertTrue(stored.getEnabled(), "must be persisted as enabled before connecting");
        InOrder order = inOrder(connectorConfigs, client);
        order.verify(connectorConfigs).saveConnectorConfiguration(stored);
        order.verify(client).reconnect();
    }

    @Test
    void connectReportsAMissingConnectorAsNotFound() {
        ConnectorOperationException e = assertThrows(ConnectorOperationException.class,
                () -> service.connect(TENANT, ID));
        assertEquals(Reason.NOT_FOUND, e.getReason());
    }

    @Test
    void createRefusesTheHttpConnector() {
        ConnectorOperationException e = assertThrows(ConnectorOperationException.class,
                () -> service.createConnectorConfiguration(TENANT, config(ConnectorType.HTTP, true)));
        assertEquals(Reason.NOT_ALLOWED, e.getReason());
    }

    @Test
    void testConnectionRejectsAnUnknownConnectorType() {
        // the mocked registry has no specification for the type
        ConnectorOperationException e = assertThrows(ConnectorOperationException.class,
                () -> service.testConnection(TENANT, config(ConnectorType.MQTT, true)));
        assertEquals(Reason.NOT_ALLOWED, e.getReason());
    }


    // ---- service configuration update ----

    private ServiceConfiguration outbound(boolean enabled) {
        ServiceConfiguration c = new ServiceConfiguration();
        c.setOutboundMappingEnabled(enabled);
        return c;
    }

    @Test
    void switchingOutboundMappingOffDisconnectsNotificationsWhenConnected() throws Exception {
        when(serviceRegistry.getServiceConfiguration(TENANT)).thenReturn(outbound(true));
        when(serviceConfigs.applyPartialUpdate(anyString(), any())).thenReturn(outbound(false));
        when(notifications.getDeviceConnectionStatus(TENANT)).thenReturn(200);

        service.updateServiceConfiguration(TENANT, java.util.Map.of("outboundMappingEnabled", false));

        verify(notifications).disconnect(TENANT);
        verify(notifications, never()).initializeDeviceClient(anyString());
        verify(serviceRegistry).addServiceConfiguration(eq(TENANT), any());
    }

    @Test
    void switchingOutboundMappingOnInitializesConnectorsAndNotificationClients() throws Exception {
        when(serviceRegistry.getServiceConfiguration(TENANT)).thenReturn(outbound(false));
        ServiceConfiguration updated = outbound(true);
        when(serviceConfigs.applyPartialUpdate(anyString(), any())).thenReturn(updated);
        when(notifications.getDeviceConnectionStatus(TENANT)).thenReturn(null);
        when(connectorConfigs.getConnectorConfigurations(TENANT)).thenReturn(java.util.List.of());

        service.updateServiceConfiguration(TENANT, java.util.Map.of("outboundMappingEnabled", true));

        verify(notifications).initializeDeviceClient(TENANT);
        verify(notifications).initializeManagementClient(TENANT);
        verify(serviceRegistry).addServiceConfiguration(TENANT, updated);
    }

    @Test
    void anUnrelatedConfigurationChangeLeavesNotificationsAlone() throws Exception {
        when(serviceRegistry.getServiceConfiguration(TENANT)).thenReturn(outbound(true));
        ServiceConfiguration updated = outbound(true);
        when(serviceConfigs.applyPartialUpdate(anyString(), any())).thenReturn(updated);
        when(notifications.getDeviceConnectionStatus(TENANT)).thenReturn(200);

        service.updateServiceConfiguration(TENANT, java.util.Map.of("logPayload", true));

        verify(notifications, never()).disconnect(anyString());
        verify(notifications, never()).initializeDeviceClient(anyString());
        verify(serviceRegistry).addServiceConfiguration(TENANT, updated);
    }

    @Test
    void aFailedConfigurationUpdateIsReportedAndNotPublished() throws Exception {
        when(serviceRegistry.getServiceConfiguration(TENANT)).thenReturn(outbound(true));
        when(serviceConfigs.applyPartialUpdate(anyString(), any())).thenThrow(new java.io.IOException("bad field"));

        ConnectorOperationException e = assertThrows(ConnectorOperationException.class,
                () -> service.updateServiceConfiguration(TENANT, java.util.Map.of("x", 1)));

        assertEquals(Reason.FAILED, e.getReason());
        verify(serviceRegistry, never()).addServiceConfiguration(anyString(), any());
    }

    private static <T> org.mockito.stubbing.Stubber doReturn(T value) {
        return org.mockito.Mockito.doReturn(value);
    }
}
