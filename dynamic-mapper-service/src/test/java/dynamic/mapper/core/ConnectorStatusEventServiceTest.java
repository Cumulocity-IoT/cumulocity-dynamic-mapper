package dynamic.mapper.core;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.joda.time.DateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.cumulocity.microservice.context.ContextService;
import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.cumulocity.sdk.client.event.EventApi;
import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.model.status.ConnectorStatus;
import dynamic.mapper.model.status.LoggingEventType;

/** The Notification 2.0 status event: sent on a change only, and never for an unsubscribed tenant. */
class ConnectorStatusEventServiceTest {

    private static final String TENANT = "t1";

    private final C8YAgent c8yAgent = mock(C8YAgent.class);
    private final TenantRegistry tenantRegistry = mock(TenantRegistry.class);
    private final ServiceConfiguration config = new ServiceConfiguration();
    private ConnectorStatusEventService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new ConnectorStatusEventService(mock(EventApi.class), mock(MicroserviceSubscriptionsService.class),
                mock(ContextService.class), mock(IMapperConfiguration.class), new ObjectMapper(), tenantRegistry,
                c8yAgent);
        config.setSendNotificationLifecycle(true);
        when(tenantRegistry.getServiceConfiguration(TENANT)).thenReturn(config);
    }

    @AfterEach
    void clearRevoked() {
        TenantCredentials.clearAll();
    }

    @Test
    void aStatusIsReportedOnlyWhenItChanged() {
        service.sendNotificationLifecycle(TENANT, ConnectorStatus.CONNECTED, null);
        service.sendNotificationLifecycle(TENANT, ConnectorStatus.CONNECTED, null);
        service.sendNotificationLifecycle(TENANT, ConnectorStatus.DISCONNECTED, null);

        verify(c8yAgent, times(2)).createLoggingEvent(anyString(), eq(LoggingEventType.NOTIFICATION_EVENT_TYPE),
                anyString(), any(DateTime.class), eq(TENANT), any());
    }

    @Test
    void nothingIsReportedWhenTheTenantDisabledIt() {
        config.setSendNotificationLifecycle(false);

        service.sendNotificationLifecycle(TENANT, ConnectorStatus.CONNECTED, null);

        verify(c8yAgent, never()).createLoggingEvent(anyString(), any(), anyString(), any(DateTime.class), anyString(),
                any());
    }

    @Test
    void nothingIsSentForAnUnsubscribedTenant() {
        TenantCredentials.markRevoked(TENANT);

        service.sendNotificationLifecycle(TENANT, ConnectorStatus.CONNECTED, null);
        assertNull(service.createConnectorStatusEvent("m", "NORMAL", DateTime.now(), TENANT, null, null));

        verify(c8yAgent, never()).createLoggingEvent(anyString(), any(), anyString(), any(DateTime.class), anyString(),
                any());
    }

    @Test
    void theLastStatusIsRememberedPerTenant() {
        when(tenantRegistry.getServiceConfiguration("t2")).thenReturn(config);

        service.sendNotificationLifecycle(TENANT, ConnectorStatus.CONNECTED, null);
        service.sendNotificationLifecycle("t2", ConnectorStatus.CONNECTED, null); // same status, other tenant

        verify(c8yAgent).createLoggingEvent(anyString(), eq(LoggingEventType.NOTIFICATION_EVENT_TYPE), anyString(),
                any(DateTime.class), eq(TENANT), any());
        verify(c8yAgent).createLoggingEvent(anyString(), eq(LoggingEventType.NOTIFICATION_EVENT_TYPE), anyString(),
                any(DateTime.class), eq("t2"), any());
    }

    @Test
    void anUnsubscribedTenantReportsItsFirstStatusAgainAfterResubscribing() {
        service.sendNotificationLifecycle(TENANT, ConnectorStatus.CONNECTED, null);
        com.cumulocity.microservice.subscription.model.MicroserviceSubscriptionRemovedEvent event =
                mock(com.cumulocity.microservice.subscription.model.MicroserviceSubscriptionRemovedEvent.class);
        when(event.getTenant()).thenReturn(TENANT);

        service.onTenantUnsubscribed(event);
        service.sendNotificationLifecycle(TENANT, ConnectorStatus.CONNECTED, null);

        verify(c8yAgent, times(2)).createLoggingEvent(anyString(), eq(LoggingEventType.NOTIFICATION_EVENT_TYPE),
                anyString(), any(DateTime.class), eq(TENANT), any());
    }
}
