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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;

import com.cumulocity.microservice.context.credentials.MicroserviceCredentials;
import com.cumulocity.microservice.subscription.model.MicroserviceSubscriptionAddedEvent;
import com.cumulocity.microservice.subscription.model.MicroserviceSubscriptionRemovedEvent;
import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;

import dynamic.mapper.configuration.ServiceConfigurationService;
import dynamic.mapper.mapping.MappingService;

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
        public void initializeTenant(TenantInitContext ctx) {
            log.add("init:" + name);
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
        return new BootstrapService(mock(ServiceRegistry.class), mock(C8YAgent.class),
                mock(MappingService.class), mock(ServiceConfigurationService.class),
                mock(ConnectorLifecycleService.class), mock(MicroserviceSubscriptionsService.class), null,
                mock(ExtensionManager.class), beans, 10, 10, 10);
    }

    @org.junit.jupiter.api.AfterEach
    void clearRevokedTenants() {
        TenantCredentials.clearAll();
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
        volatile java.util.concurrent.CountDownLatch initStarted;
        volatile java.util.concurrent.CountDownLatch releaseInit;

        TestableService(BootstrapService proto, int failures, List<TenantScoped> beans,
                MicroserviceSubscriptionsService subscriptions) {
            super(mock(ServiceRegistry.class), mock(C8YAgent.class), mock(MappingService.class),
                    mock(ServiceConfigurationService.class), mock(ConnectorLifecycleService.class), subscriptions,
                    null, mock(ExtensionManager.class), beans, 10, 10, 10);
            this.failures = failures;
        }

        @Override
        void initializeTenantState(String tenant, MicroserviceCredentials credentials) {
            if (initStarted != null) {
                initStarted.countDown();
                try {
                    releaseInit.await(5, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
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

    private static MicroserviceSubscriptionRemovedEvent removedEvent() {
        MicroserviceSubscriptionRemovedEvent removed = mock(MicroserviceSubscriptionRemovedEvent.class);
        when(removed.getTenant()).thenReturn("t1");
        return removed;
    }

    @Test
    void unsubscribeDuringInitializationWaitsForItAndThenRemovesWhatItBuilt() throws Exception {
        List<String> calls = java.util.Collections.synchronizedList(new ArrayList<>());
        TestableService service = new TestableService(null, 0, List.of(new Recording("a", calls, false)),
                runningSubscriptions());
        service.initStarted = new java.util.concurrent.CountDownLatch(1);
        service.releaseInit = new java.util.concurrent.CountDownLatch(1);

        Thread subscribe = new Thread(() -> service.onTenantSubscribed(addedEvent()));
        subscribe.start();
        assertTrue(service.initStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));

        Thread unsubscribe = new Thread(() -> service.onTenantUnsubscribed(removedEvent()));
        unsubscribe.start();
        Thread.sleep(200);
        assertTrue(calls.isEmpty(), "removal must wait while initialization is still building state");

        service.releaseInit.countDown();
        subscribe.join(5000);
        unsubscribe.join(5000);

        assertEquals(List.of("stop:a", "remove:a"), calls, "removal runs after the initialization finished");
        assertNull(service.getTenantState("t1"));
    }

    @Test
    void removeAllRunsEveryStepAndRethrowsTheFirstFailure() {
        List<String> ran = new ArrayList<>();
        IllegalStateException e = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> TenantScoped.removeAll("t1", this,
                        () -> ran.add("1"),
                        () -> { throw new IllegalStateException("first"); },
                        () -> ran.add("3"),
                        () -> { throw new IllegalStateException("second"); }));
        assertEquals("first", e.getMessage());
        assertEquals(List.of("1", "3"), ran);
    }

    @Test
    void beansInitializeInReverseOrderAndTheFirstFailureAbortsTheAttempt() {
        List<String> calls = new ArrayList<>();
        // beans arrive sorted by @Order: a = connectors (first), c = registry (last)
        List<TenantScoped> beans = List.of(new Recording("a", calls, false), new Recording("b", calls, false),
                new Recording("c", calls, false) {
                    @Override
                    public void initializeTenant(TenantInitContext ctx) {
                        super.initializeTenant(ctx);
                    }
                });
        List<String> started = new ArrayList<>();
        BootstrapService service = new BootstrapService(mock(ServiceRegistry.class), mock(C8YAgent.class),
                mock(MappingService.class), mock(ServiceConfigurationService.class),
                mock(ConnectorLifecycleService.class), mock(MicroserviceSubscriptionsService.class), null,
                mock(ExtensionManager.class), beans, 10, 10, 10) {
            @Override
            TenantInitContext prepareTenant(String tenant, MicroserviceCredentials credentials) {
                return new TenantInitContext(tenant, credentials, null, null, null);
            }

            @Override
            void startTenant(TenantInitContext ctx) {
                started.add("start");
            }
        };

        service.initializeTenantState("t1", mock(MicroserviceCredentials.class));

        assertEquals(List.of("init:c", "init:b", "init:a"), calls, "registry first, connectors last");
        assertEquals(List.of("start"), started, "start runs after all beans are initialized");

        // a failing bean aborts: later beans and the start phase must not run
        List<String> calls2 = new ArrayList<>();
        List<String> started2 = new ArrayList<>();
        TenantScoped failing = new Recording("fail", calls2, false) {
            @Override
            public void initializeTenant(TenantInitContext ctx) {
                throw new IllegalStateException("boom");
            }
        };
        BootstrapService service2 = new BootstrapService(mock(ServiceRegistry.class), mock(C8YAgent.class),
                mock(MappingService.class), mock(ServiceConfigurationService.class),
                mock(ConnectorLifecycleService.class), mock(MicroserviceSubscriptionsService.class), null,
                mock(ExtensionManager.class), List.of(new Recording("a", calls2, false), failing), 10, 10, 10) {
            @Override
            TenantInitContext prepareTenant(String tenant, MicroserviceCredentials credentials) {
                return new TenantInitContext(tenant, credentials, null, null, null);
            }

            @Override
            void startTenant(TenantInitContext ctx) {
                started2.add("start");
            }
        };
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> service2.initializeTenantState("t1", mock(MicroserviceCredentials.class)));
        assertTrue(calls2.isEmpty(), "bean a is after the failing one in init order and must not run");
        assertTrue(started2.isEmpty());
    }
}
