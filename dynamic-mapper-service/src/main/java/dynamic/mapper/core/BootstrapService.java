/*
 * Copyright (c) 2022-2025 Cumulocity GmbH.
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

import dynamic.mapper.ai.AIAgentService;

import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import dynamic.mapper.configuration.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.cumulocity.microservice.context.credentials.MicroserviceCredentials;
import com.cumulocity.microservice.subscription.model.MicroserviceSubscriptionAddedEvent;
import com.cumulocity.microservice.subscription.model.MicroserviceSubscriptionRemovedEvent;
import io.micrometer.core.instrument.Metrics;
import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;


import dynamic.mapper.mapping.MappingService;
import dynamic.mapper.configuration.ServiceConfigurationService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

@Service
@EnableScheduling
@Slf4j
public class BootstrapService {
    final ServiceRegistry serviceRegistry;
    private final C8YAgent c8YAgent;
    private final MappingService mappingService;
    private final ServiceConfigurationService serviceConfigurationService;
    private final MicroserviceSubscriptionsService subscriptionsService;
    private final ConnectorLifecycleService connectorLifecycleService;
    private final Integer inboundExternalIdCacheSize;
    private final Integer outboundExternalIdCacheSize;
    private final Integer inventoryCacheSize;

    private final AIAgentService aiAgentService;
    private final ExtensionManager extensionManager;
    /** Initialization state per tenant; absent = unknown or already removed. */
    public enum TenantState { INITIALIZING, RETRYING, READY, FAILED }

    /** Attempts of the initial tenant initialization before the tenant is left {@link TenantState#FAILED}. */
    static final int MAX_INIT_ATTEMPTS = 5;
    private static final long INIT_RETRY_BASE_DELAY_SECONDS = 10;
    private static final long INIT_RETRY_MAX_DELAY_SECONDS = 300;

    private final Map<String, TenantState> tenantStates = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> pendingInitRetries = new ConcurrentHashMap<>();
    /**
     * Serializes initialization attempts, subscribe and unsubscribe per tenant, so an unsubscribe never runs
     * while an attempt is still building state (which would resurrect the removed tenant). Entries are tiny and
     * never removed: dropping one while another thread waits on it would break the mutual exclusion.
     */
    private final Map<String, Object> tenantLifecycleLocks = new ConcurrentHashMap<>();

    private Object lifecycleLock(String tenant) {
        return tenantLifecycleLocks.computeIfAbsent(tenant, t -> new Object());
    }
    private final ScheduledExecutorService initRetryExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "tenant-init-retry");
                t.setDaemon(true);
                return t;
            });

    /** All per-tenant beans, sorted by {@code @Order}; see {@link TenantScoped}. */
    private final List<TenantScoped> tenantScoped;

    public BootstrapService(
            ServiceRegistry serviceRegistry,
            C8YAgent c8YAgent,
            MappingService mappingService,
            ServiceConfigurationService serviceConfigurationService,
            ConnectorLifecycleService connectorLifecycleService,
            MicroserviceSubscriptionsService subscriptionsService,
            AIAgentService aiAgentService,
            ExtensionManager extensionManager,
            List<TenantScoped> tenantScoped,
            @Value("#{new Integer('${APP.inboundExternalIdCacheSize}')}") Integer inboundExternalIdCacheSize,
            @Value("#{new Integer('${APP.outboundExternalIdCacheSize}')}") Integer outboundExternalIdCacheSize,
            @Value("#{new Integer('${APP.inventoryCacheSize}')}") Integer inventoryCacheSize) {

        this.serviceRegistry = serviceRegistry;
        this.c8YAgent = c8YAgent;
        this.mappingService = mappingService;
        this.serviceConfigurationService = serviceConfigurationService;
        this.connectorLifecycleService = connectorLifecycleService;
        this.subscriptionsService = subscriptionsService;
        this.aiAgentService = aiAgentService;
        this.extensionManager = extensionManager;
        this.tenantScoped = tenantScoped;
        this.inboundExternalIdCacheSize = inboundExternalIdCacheSize;
        this.outboundExternalIdCacheSize = outboundExternalIdCacheSize;
        this.inventoryCacheSize = inventoryCacheSize;
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"));
    }

    @PreDestroy
    public void destroy() {
        log.info("Shutting down mapper...");
        initRetryExecutor.shutdownNow();
        subscriptionsService.runForEachTenant(() -> {
            String tenant = subscriptionsService.getTenant();
            log.info("{} - Stopping tenant", tenant);
            // a failing tenant must not keep the remaining tenants from being stopped
            stopTenant(tenant);
            log.info("{} - Tenant stopped", tenant);
        });

        log.info("Mapper shutdown completed");
    }

    /**
     * Phase 1 of the tenant lifecycle ({@link TenantScoped#stopTenant}): ends running activity of all beans but
     * keeps their state and the platform subscriptions. Shared by shutdown ({@link #destroy()}) and unsubscribe
     * ({@link #removeTenantState(String)}).
     */
    private void stopTenant(String tenant) {
        forEachTenantScoped(tenant, "stop", TenantScoped::stopTenant);
    }

    /** Calls {@code action} on every {@link TenantScoped} bean in {@code @Order}; a failing bean does not skip the others. */
    private void forEachTenantScoped(String tenant, String phase, BiConsumer<TenantScoped, String> action) {
        for (TenantScoped bean : tenantScoped) {
            try {
                action.accept(bean, tenant);
                log.debug("{} - {} {}", tenant, phase, bean.getClass().getSimpleName());
            } catch (Exception e) {
                log.error("{} - Error during {} of {}: {}", tenant, phase, bean.getClass().getSimpleName(),
                        e.getMessage(), e);
            }
        }
    }

    @EventListener
    public void onTenantUnsubscribed(MicroserviceSubscriptionRemovedEvent event) {
        String tenant = event.getTenant();
        log.info("{} - Microservice unsubscribed", tenant);
        // The platform already deleted the service user: cleanup must not call the platform (would only 401).
        TenantCredentials.markRevoked(tenant);
        // waits for a running initialization attempt, then removes everything it built
        synchronized (lifecycleLock(tenant)) {
            tenantStates.remove(tenant);
            cancelPendingInitRetry(tenant);

            try {
                removeTenantState(tenant);
            } catch (Exception e) {
                log.error("{} - Error during unsubscribing cleanup: {}", tenant, e.getMessage(), e);
            }
        }
    }

    private void cancelPendingInitRetry(String tenant) {
        ScheduledFuture<?> pending = pendingInitRetries.remove(tenant);
        if (pending != null) {
            pending.cancel(false);
        }
    }

    private void removeTenantState(String tenant) {
        removeTenantStateKeepingStatus(tenant);
    }

    /** Stop and remove everything of the tenant; does not touch the init state (used between init retries). */
    private void removeTenantStateKeepingStatus(String tenant) {
        log.info("{} - Starting tenant resource cleanup", tenant);

        // Phase 1 for all beans before phase 2 for any: nothing may still run on resources removed below
        // (connector housekeeping, notification connections).
        stopTenant(tenant);
        forEachTenantScoped(tenant, "remove", TenantScoped::removeTenant);

        try {
            // Per-tenant meters (dispatcher timers/counters, identity counters) would otherwise stay
            // registered for the lifetime of the JVM.
            Metrics.globalRegistry.getMeters().stream()
                    .filter(m -> tenant.equals(m.getId().getTag("tenant")))
                    .forEach(Metrics.globalRegistry::remove);
        } catch (Exception e) {
            log.error("{} - Error removing tenant meters: {}", tenant, e.getMessage(), e);
        }

        log.info("{} - Completed tenant resource cleanup", tenant);
    }

    @EventListener
    public void onTenantSubscribed(MicroserviceSubscriptionAddedEvent event) {
        String tenant = event.getCredentials().getTenant();
        log.info("{} - Microservice subscribed", tenant);
        TenantCredentials.clearRevoked(tenant);
        synchronized (lifecycleLock(tenant)) {
            cancelPendingInitRetry(tenant); // a retry chain of an earlier subscription must not run on
            tenantStates.put(tenant, TenantState.INITIALIZING);
            attemptInitialization(tenant, event.getCredentials(), 1);
        }
    }

    // package-private: overridden in tests
    long initRetryDelaySeconds(int attempt) {
        return Math.min(INIT_RETRY_MAX_DELAY_SECONDS, INIT_RETRY_BASE_DELAY_SECONDS << (attempt - 1));
    }

    /** {@code true} once the tenant is fully initialized; {@code false} while initializing, retrying, or failed. */
    public boolean isTenantReady(String tenant) {
        return tenantStates.get(tenant) == TenantState.READY;
    }

    public TenantState getTenantState(String tenant) {
        return tenantStates.get(tenant);
    }

    /**
     * Initializes the tenant. A failure (typically a platform outage while loading configuration or mappings)
     * would leave the tenant half built, so every message of it could hit missing state. Instead the half-built
     * tenant is removed (clean slate) and the initialization is retried with exponential backoff; after
     * {@link #MAX_INIT_ATTEMPTS} the tenant is left clean and {@link TenantState#FAILED}.
     */
    private void attemptInitialization(String tenant, MicroserviceCredentials credentials, int attempt) {
        synchronized (lifecycleLock(tenant)) {
            attemptInitializationLocked(tenant, credentials, attempt);
        }
    }

    private void attemptInitializationLocked(String tenant, MicroserviceCredentials credentials, int attempt) {
        if (!tenantStates.containsKey(tenant)) {
            return; // unsubscribed before this attempt started
        }
        try {
            initializeTenantState(tenant, credentials);
            tenantStates.replace(tenant, TenantState.INITIALIZING, TenantState.READY);
            tenantStates.replace(tenant, TenantState.RETRYING, TenantState.READY);
            log.info("{} - Tenant initialized (attempt {})", tenant, attempt);
            return;
        } catch (Exception e) {
            log.error("{} - Initialization attempt {}/{} failed: {}", tenant, attempt, MAX_INIT_ATTEMPTS,
                    e.getMessage(), e);
        }

        // Clean slate, so neither a retry nor the failed state leaves half-built resources behind.
        removeTenantStateKeepingStatus(tenant);

        if (attempt >= MAX_INIT_ATTEMPTS) {
            tenantStates.replace(tenant, TenantState.RETRYING, TenantState.FAILED);
            tenantStates.replace(tenant, TenantState.INITIALIZING, TenantState.FAILED);
            log.error("{} - Giving up initializing the tenant after {} attempts; re-subscribe or restart to retry",
                    tenant, attempt);
            return;
        }
        long delay = initRetryDelaySeconds(attempt);
        tenantStates.replace(tenant, TenantState.INITIALIZING, TenantState.RETRYING);
        log.warn("{} - Retrying initialization in {}s", tenant, delay);
        ScheduledFuture<?> previous = pendingInitRetries.put(tenant, initRetryExecutor.schedule(() -> {
            pendingInitRetries.remove(tenant);
            if (tenantStates.get(tenant) != TenantState.RETRYING) {
                return; // unsubscribed or re-subscribed meanwhile
            }
            subscriptionsService.runForTenant(tenant, () -> attemptInitialization(tenant, credentials, attempt + 1));
        }, delay, TimeUnit.SECONDS));
        if (previous != null) {
            previous.cancel(false);
        }
    }

    // package-private: overridden in tests
    void initializeTenantState(String tenant, MicroserviceCredentials credentials) {
        // 1. Prepare: all platform I/O needed to build state. A failure here leaves nothing behind.
        TenantInitContext ctx = prepareTenant(tenant, credentials);
        // 2. Create the empty per-tenant state of every bean (see TenantScoped#initializeTenant).
        initializeBeans(ctx);
        // 3. Start activity: has data dependencies, so it is an explicit sequence, not an @Order.
        startTenant(ctx);
    }

    // package-private: overridden in tests
    TenantInitContext prepareTenant(String tenant, MicroserviceCredentials credentials) {
        ServiceConfiguration serviceConfiguration = serviceConfigurationService.ensureDefaults(tenant,
                inboundExternalIdCacheSize, outboundExternalIdCacheSize, inventoryCacheSize);
        ManagedObjectRepresentation mapperServiceMo = c8YAgent.initializeMapperServiceRepresentation(tenant);
        // DO NOT REMOVE DeviceIsolationMQTTService feature
        ManagedObjectRepresentation deviceToClientMo = Boolean.TRUE
                .equals(serviceConfiguration.getDeviceIsolationMQTTServiceEnabled())
                        ? c8YAgent.initializeDeviceToClientMapRepresentation(tenant)
                        : null;
        return new TenantInitContext(tenant, credentials, serviceConfiguration, mapperServiceMo, deviceToClientMo);
    }

    /**
     * Mirror of removal: beans initialize in reverse {@code @Order} (registry first, connectors last), so what a
     * bean reads during its own initialization (credentials, configuration, representations) already exists.
     * Unlike removal, the first failure aborts: a half-initialized tenant is cleaned up by the caller.
     */
    private void initializeBeans(TenantInitContext ctx) {
        for (int i = tenantScoped.size() - 1; i >= 0; i--) {
            TenantScoped bean = tenantScoped.get(i);
            bean.initializeTenant(ctx);
            log.debug("{} - initialized {}", ctx.tenant(), bean.getClass().getSimpleName());
        }
    }

    // package-private: overridden in tests
    void startTenant(TenantInitContext ctx) {
        String tenant = ctx.tenant();
        ServiceConfiguration serviceConfiguration = ctx.serviceConfiguration();

        c8YAgent.createExtensibleProcessor(tenant);
        extensionManager.loadProcessorExtensions(tenant);

        mappingService.warmupMappingCodes(tenant);

        connectorLifecycleService.cleanupStaleConnectorReferences(tenant);

        // Wait for ALL connectors are successfully connected before handling Outbound
        // Mappings
        connectorLifecycleService.awaitConnections(tenant,
                connectorLifecycleService.initializeConnectors(tenant, serviceConfiguration));

        // only initialize mapping after all connectors are initialized
        // and connected
        mappingService.loadTenantMappings(tenant);
        aiAgentService.initializeAIAgents();

        connectorLifecycleService.initializeOutboundResources(tenant, serviceConfiguration);
    }

    // DO NOT REMOVE DeviceIsolationMQTTService feature
    @Scheduled(cron = "0 30 * * * *")
    public void sendDeviceToClientMap() {
        subscriptionsService.runForEachTenant(() -> {
            String tenant = subscriptionsService.getTenant();
            try {
                ServiceConfiguration serviceConfiguration = serviceConfigurationService.getServiceConfiguration(tenant);
                if (serviceConfiguration == null) {
                    log.warn("{} - Service configuration is null, skipping sendDeviceToClientMap", tenant);
                    return;
                }
                if (Boolean.TRUE.equals(serviceConfiguration.getDeviceIsolationMQTTServiceEnabled())) {
                    // The flag can be switched on after startup, when the map MO was never loaded
                    if (serviceRegistry.getDeviceToClientMapId(tenant) == null) {
                        serviceRegistry.storeDeviceToClientMapRepresentation(tenant,
                                c8YAgent.initializeDeviceToClientMapRepresentation(tenant));
                    }
                    mappingService.sendDeviceToClientMap(tenant);
                }
            } catch (Exception e) {
                log.error("{} - Error executing sendDeviceToClientMaP", tenant, e);
            }
        });
    }
}
