package dynamic.mapper.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;

import com.cumulocity.microservice.context.credentials.MicroserviceCredentials;
import com.cumulocity.microservice.subscription.model.MicroserviceSubscriptionAddedEvent;
import com.cumulocity.microservice.subscription.model.MicroserviceSubscriptionRemovedEvent;
import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;

import dynamic.mapper.configuration.ConnectorConfigurationService;
import dynamic.mapper.configuration.ServiceConfigurationService;
import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.mapping.MappingService;
import dynamic.mapper.processor.flow.FlowStateStore;
import java.lang.reflect.Field;
import java.time.Instant;

class BootstrapServiceTenantLifecycleTest {

    private static class Recording implements TenantScoped {
        private final String name;
        private final List<String> log;
        private final boolean failOnStop;

        Recording(String name, List<String> log, boolean failOnStop) {
            this.name = name;
            this.log = log;
            this.failOnStop = failOnStop;
        }

        @Override
        public void stopTenant(String tenant) {
            log.add("stop:" + name);
            if (failOnStop) {
                throw new IllegalStateException("boom");
            }
        }

        @Override
        public void removeTenant(String tenant) {
            log.add("remove:" + name);
        }
    }

    private BootstrapService service(List<TenantScoped> beans) {
        return new BootstrapService(mock(ConnectorRegistry.class), mock(ServiceRegistry.class), null,
                mock(C8YAgent.class), mock(CacheManager.class), mock(MappingService.class),
                mock(ServiceConfigurationService.class), mock(ConnectorConfigurationService.class),
                mock(MicroserviceSubscriptionsService.class), mock(ExtensionInboundRegistry.class), null,
                mock(ExtensionManager.class), mock(FlowStateStore.class), beans, "", 10, 10, 10);
    }

    @Test
    void unsubscribeStopsAllBeansBeforeRemovingAnyAndKeepsGoingAfterAFailure() {
        List<String> calls = new ArrayList<>();
        BootstrapService service = service(List.of(
                new Recording("a", calls, true), // fails on stop: b must still run
                new Recording("b", calls, false)));
        MicroserviceSubscriptionRemovedEvent event = mock(MicroserviceSubscriptionRemovedEvent.class);
        when(event.getTenant()).thenReturn("t1");

        service.onTenantUnsubscribed(event);

        assertEquals(List.of("stop:a", "stop:b", "remove:a", "remove:b"), calls);
    }

    @Test
    void failedInitializationRemovesTheHalfBuiltTenantAndRetriesUntilUnsubscribed() {
        List<String> calls = new ArrayList<>();
        // mocks return null for the service configuration, so the initialization fails
        BootstrapService service = service(List.of(new Recording("a", calls, false)));
        MicroserviceCredentials credentials = mock(MicroserviceCredentials.class);
        when(credentials.getTenant()).thenReturn("t1");
        MicroserviceSubscriptionAddedEvent added = mock(MicroserviceSubscriptionAddedEvent.class);
        when(added.getCredentials()).thenReturn(credentials);

        service.onTenantSubscribed(added);

        assertEquals(BootstrapService.TenantState.RETRYING, service.getTenantState("t1"));
        assertFalse(service.isTenantReady("t1"));
        assertEquals(List.of("stop:a", "remove:a"), calls, "half-built tenant must be removed before the retry");

        MicroserviceSubscriptionRemovedEvent removed = mock(MicroserviceSubscriptionRemovedEvent.class);
        when(removed.getTenant()).thenReturn("t1");
        service.onTenantUnsubscribed(removed);

        assertNull(service.getTenantState("t1"), "unsubscribe cancels the pending retry and forgets the state");
    }

    // ---- retry behaviour: the initialization is overridden so no real platform call is needed ----

    private static class TestableService extends BootstrapService {
        final AtomicInteger attempts = new AtomicInteger();
        final int failures;

        TestableService(BootstrapService proto, int failures, List<TenantScoped> beans,
                MicroserviceSubscriptionsService subscriptions) {
            super(mock(ConnectorRegistry.class), mock(ServiceRegistry.class), null, mock(C8YAgent.class),
                    mock(CacheManager.class), mock(MappingService.class), mock(ServiceConfigurationService.class),
                    mock(ConnectorConfigurationService.class), subscriptions, mock(ExtensionInboundRegistry.class),
                    null, mock(ExtensionManager.class), mock(FlowStateStore.class), beans, "", 10, 10, 10);
            this.failures = failures;
        }

        @Override
        void initializeTenantState(String tenant, MicroserviceCredentials credentials) {
            if (attempts.incrementAndGet() <= failures) {
                throw new IllegalStateException("platform unavailable");
            }
        }

        @Override
        long initRetryDelaySeconds(int attempt) {
            return 0;
        }
    }

    private static MicroserviceSubscriptionAddedEvent addedEvent() {
        MicroserviceCredentials credentials = mock(MicroserviceCredentials.class);
        when(credentials.getTenant()).thenReturn("t1");
        MicroserviceSubscriptionAddedEvent added = mock(MicroserviceSubscriptionAddedEvent.class);
        when(added.getCredentials()).thenReturn(credentials);
        return added;
    }

    private static MicroserviceSubscriptionsService runningSubscriptions() {
        MicroserviceSubscriptionsService subscriptions = mock(MicroserviceSubscriptionsService.class);
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(1)).run();
            return null;
        }).when(subscriptions).runForTenant(anyString(), any(Runnable.class));
        return subscriptions;
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), "condition not reached within 5s");
    }

    @Test
    void initializationSucceedingAfterRetriesEndsReadyWithOneCleanupPerFailure() throws Exception {
        List<String> calls = new ArrayList<>();
        TestableService service = new TestableService(null, 2, List.of(new Recording("a", calls, false)),
                runningSubscriptions());

        service.onTenantSubscribed(addedEvent());
        await(() -> service.isTenantReady("t1"));

        assertEquals(3, service.attempts.get());
        assertEquals(List.of("stop:a", "remove:a", "stop:a", "remove:a"), calls,
                "each failed attempt removes the half-built tenant; the successful one does not");
        service.onTenantUnsubscribed(removedEvent());
    }

    @Test
    void initializationFailingEveryTimeEndsFailedAfterTheMaximumAttemptsAndLeavesNothingBehind() throws Exception {
        List<String> calls = new ArrayList<>();
        TestableService service = new TestableService(null, Integer.MAX_VALUE,
                List.of(new Recording("a", calls, false)), runningSubscriptions());

        service.onTenantSubscribed(addedEvent());
        await(() -> service.getTenantState("t1") == BootstrapService.TenantState.FAILED);

        assertEquals(BootstrapService.MAX_INIT_ATTEMPTS, service.attempts.get());
        assertEquals(2 * BootstrapService.MAX_INIT_ATTEMPTS, calls.size(), "stop+remove after every attempt");
        assertFalse(service.isTenantReady("t1"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void unsubscribeForgetsTheRetentionTimestampsOfThatTenantOnly() throws Exception {
        BootstrapService service = service(List.of());
        for (String field : List.of("cacheInboundExternalIdRetentionStartMap",
                "cacheOutboundExternalIdRetentionStartMap", "cacheInventoryRetentionStartMap")) {
            Field f = BootstrapService.class.getDeclaredField(field);
            f.setAccessible(true);
            Map<String, Instant> map = (Map<String, Instant>) f.get(service);
            map.put("t1", Instant.now());
            map.put("t2", Instant.now());
        }

        service.onTenantUnsubscribed(removedEvent());

        for (String field : List.of("cacheInboundExternalIdRetentionStartMap",
                "cacheOutboundExternalIdRetentionStartMap", "cacheInventoryRetentionStartMap")) {
            Field f = BootstrapService.class.getDeclaredField(field);
            f.setAccessible(true);
            Map<String, ?> map = (Map<String, ?>) f.get(service);
            assertFalse(map.containsKey("t1"), field);
            assertTrue(map.containsKey("t2"), field);
        }
    }

    private static MicroserviceSubscriptionRemovedEvent removedEvent() {
        MicroserviceSubscriptionRemovedEvent removed = mock(MicroserviceSubscriptionRemovedEvent.class);
        when(removed.getTenant()).thenReturn("t1");
        return removed;
    }
}
