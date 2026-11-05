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
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import dynamic.mapper.configuration.*;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.TransformationType;
import dynamic.mapper.processor.util.JavaScriptModuleStripper;
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
import com.fasterxml.jackson.core.JsonProcessingException;

import dynamic.mapper.connector.core.callback.GenericMessageCallback;
import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.connector.core.client.ConnectorException;
import dynamic.mapper.connector.core.client.ConnectorType;
import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.connector.core.registry.ConnectorRegistryException;
import dynamic.mapper.connector.http.HttpClient;
import dynamic.mapper.connector.test.TestClient;
import dynamic.mapper.notification.NotificationSubscriber;
import dynamic.mapper.processor.inbound.InboundMessageDispatcher;

import dynamic.mapper.configuration.ConnectorConfigurationService;
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
    private final ConnectorRegistry connectorRegistry;
    final ServiceRegistry serviceRegistry;
    private final ConnectorClientFactory connectorClientFactory;
    private final C8YAgent c8YAgent;
    private final CacheManager cacheManager;
    private final MappingService mappingService;
    private final ServiceConfigurationService serviceConfigurationService;
    private final ConnectorConfigurationService connectorConfigurationService;
    private final MicroserviceSubscriptionsService subscriptionsService;
    private final String additionalSubscriptionIdTest;
    private final Integer inboundExternalIdCacheSize;
    private final Integer outboundExternalIdCacheSize;
    private final Integer inventoryCacheSize;
    private final Map<String, Instant> cacheInboundExternalIdRetentionStartMap;
    private final Map<String, Instant> cacheOutboundExternalIdRetentionStartMap;
    private final Map<String, Instant> cacheInventoryRetentionStartMap;
    private final ExtensionInboundRegistry extensionInboundRegistry;

    private final AIAgentService aiAgentService;
    private final ExtensionManager extensionManager;
    private final dynamic.mapper.processor.flow.FlowStateStore flowStateStore;
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
            ConnectorRegistry connectorRegistry,
            ServiceRegistry serviceRegistry,
            ConnectorClientFactory connectorClientFactory,
            C8YAgent c8YAgent,
            CacheManager cacheManager,
            MappingService mappingService,
            ServiceConfigurationService serviceConfigurationService,
            ConnectorConfigurationService connectorConfigurationService,
            MicroserviceSubscriptionsService subscriptionsService,
            ExtensionInboundRegistry extensionInboundRegistry,
            AIAgentService aiAgentService,
            ExtensionManager extensionManager,
            dynamic.mapper.processor.flow.FlowStateStore flowStateStore,
            List<TenantScoped> tenantScoped,
            @Value("${APP.additionalSubscriptionIdTest:}") String additionalSubscriptionIdTest,
            @Value("#{new Integer('${APP.inboundExternalIdCacheSize}')}") Integer inboundExternalIdCacheSize,
            @Value("#{new Integer('${APP.outboundExternalIdCacheSize}')}") Integer outboundExternalIdCacheSize,
            @Value("#{new Integer('${APP.inventoryCacheSize}')}") Integer inventoryCacheSize) {

        this.connectorRegistry = connectorRegistry;
        this.serviceRegistry = serviceRegistry;
        this.connectorClientFactory = connectorClientFactory;
        this.c8YAgent = c8YAgent;
        this.cacheManager = cacheManager;
        this.mappingService = mappingService;
        this.serviceConfigurationService = serviceConfigurationService;
        this.connectorConfigurationService = connectorConfigurationService;
        this.subscriptionsService = subscriptionsService;
        this.extensionInboundRegistry = extensionInboundRegistry;
        this.aiAgentService = aiAgentService;
        this.extensionManager = extensionManager;
        this.flowStateStore = flowStateStore;
        this.tenantScoped = tenantScoped;
        this.additionalSubscriptionIdTest = additionalSubscriptionIdTest;
        this.inboundExternalIdCacheSize = inboundExternalIdCacheSize;
        this.outboundExternalIdCacheSize = outboundExternalIdCacheSize;
        this.inventoryCacheSize = inventoryCacheSize;
        this.cacheInboundExternalIdRetentionStartMap = new ConcurrentHashMap<>();
        this.cacheOutboundExternalIdRetentionStartMap = new ConcurrentHashMap<>();
        this.cacheInventoryRetentionStartMap = new ConcurrentHashMap<>();
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

        cacheInboundExternalIdRetentionStartMap.remove(tenant);
        cacheOutboundExternalIdRetentionStartMap.remove(tenant);
        cacheInventoryRetentionStartMap.remove(tenant);

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
        ServiceConfiguration serviceConfiguration = initializeServiceConfiguration(tenant);
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
        String tenant = ctx.tenant();
        cacheInboundExternalIdRetentionStartMap.put(tenant, Instant.now());
        cacheOutboundExternalIdRetentionStartMap.put(tenant, Instant.now());
        cacheInventoryRetentionStartMap.put(tenant, Instant.now());
    }

    // package-private: overridden in tests
    void startTenant(TenantInitContext ctx) {
        String tenant = ctx.tenant();
        ServiceConfiguration serviceConfiguration = ctx.serviceConfiguration();

        c8YAgent.createExtensibleProcessor(tenant);
        extensionManager.loadProcessorExtensions(tenant);

        serviceRegistry.getGraalVMContextService().warmupMappingCodes(tenant, buildMappingCodeMap(tenant));
        // Register a supplier so the service re-warms all active mapping codes automatically
        // on every future Engine rotation, keeping cold-start latency bounded.
        serviceRegistry.getGraalVMContextService()
                .setMappingCodeSupplier(tenant, () -> buildMappingCodeMap(tenant));

        // Clean up any orphaned Notifications 2.0 subscribers from a previous run
        // (e.g. from connectors that were deleted or disabled without proper cleanup)
        List<dynamic.mapper.configuration.ConnectorConfiguration> allConnectorConfigs =
                connectorConfigurationService.getConnectorConfigurations(tenant);
        serviceRegistry.getNotificationSubscriber()
                .cleanupOrphanedSubscribers(tenant, allConnectorConfigs, additionalSubscriptionIdTest);

        // Remove stale connector identifiers from the deployment map (connectors deleted
        // without going through the normal deletion flow leave behind orphaned references)
        Set<String> validConnectorIds = allConnectorConfigs.stream()
                .map(dynamic.mapper.configuration.ConnectorConfiguration::getIdentifier)
                .collect(java.util.stream.Collectors.toSet());
        mappingService.cleanupStaleDeploymentConnectors(tenant, validConnectorIds);

        // Wait for ALL connectors are successfully connected before handling Outbound
        // Mappings
        List<Future<?>> connectorTasks = initializeConnectors(tenant, serviceConfiguration);
        if (connectorTasks != null) {
            connectorTasks.forEach(connectorTask -> {
                try {
                    connectorTask.get();
                } catch (InterruptedException | ExecutionException e) {
                    log.error("{} - Error initializing  connector: {}", tenant, e.getMessage(), e);
                }
            });
        }
        // only initialize mapping after all connectors are initialized
        // and connected
        mappingService.loadTenantMappings(tenant);
        aiAgentService.initializeAIAgents();

        initResourcesForOutbound(tenant, serviceConfiguration);
    }

    private Map<String, String> buildMappingCodeMap(String tenant) {
        Map<String, String> result = new HashMap<>();
        for (Direction direction : Direction.values()) {
            if (direction == Direction.UNSPECIFIED) continue;
            for (Mapping mapping : mappingService.getMappings(tenant, direction)) {
                if (mapping.getCode() == null || mapping.getCode().isBlank()) continue;
                try {
                    byte[] decoded = java.util.Base64.getDecoder().decode(mapping.getCode());
                    // Strip all ESM export/import declarations so the warm-up context
                    // can compile the code as a plain script regardless of supportESM.
                    String code = JavaScriptModuleStripper.toPlainScript(new String(decoded));
                    if (TransformationType.SMART_FUNCTION.equals(mapping.getTransformationType())) {
                        String id = Mapping.SMART_FUNCTION_NAME + "_" + mapping.getIdentifier();
                        result.put(id + ".js", code);
                    }
                } catch (Exception e) {
                    log.warn("{} - Could not prepare mapping code for warm-up [{}]: {}", tenant,
                            mapping.getName(), e.getMessage());
                }
            }
        }
        return result;
    }

    private ServiceConfiguration initializeServiceConfiguration(String tenant) {
        ServiceConfiguration serviceConfig = serviceConfigurationService.getServiceConfiguration(tenant);
        boolean requiresSave = false;

        if (serviceConfig.getInboundExternalIdCacheSize() == null
                || serviceConfig.getInboundExternalIdCacheSize() == 0) {
            serviceConfig.setInboundExternalIdCacheSize(inboundExternalIdCacheSize);
            requiresSave = true;
        }

        if (serviceConfig.getInboundExternalIdCacheRetention() == null) {
            serviceConfig.setInboundExternalIdCacheRetention(1);
            requiresSave = true;
        }

        if (serviceConfig.getOutboundExternalIdCacheSize() == null
                || serviceConfig.getOutboundExternalIdCacheSize() == 0) {
            serviceConfig.setOutboundExternalIdCacheSize(outboundExternalIdCacheSize);
            requiresSave = true;
        }

        if (serviceConfig.getOutboundExternalIdCacheRetention() == null) {
            serviceConfig.setOutboundExternalIdCacheRetention(1);
            requiresSave = true;
        }

        if (serviceConfig.getInventoryCacheSize() == null || serviceConfig.getInventoryCacheSize() == 0) {
            serviceConfig.setInventoryCacheSize(inventoryCacheSize);
            requiresSave = true;
        }

        Map<String, CodeTemplate> codeTemplates = serviceConfig.getCodeTemplates();
        if (codeTemplates == null || codeTemplates.isEmpty()) {
            // First start: load all templates from classpath
            serviceConfigurationService.initCodeTemplates(serviceConfig, false);
            requiresSave = true;
        } else {
            // Subsequent starts: add any newly deployed internal templates
            if (serviceConfigurationService.addMissingInternalTemplates(serviceConfig)) {
                requiresSave = true;
            }
            // ...and always re-load the framework-owned SYSTEM template. It is @internal/@readonly
            // and holds only the Java.type bindings the runtime needs, so the packaged copy is
            // authoritative. Customer code lives in SHARED, which is never touched here. This runs
            // before initializeTenant() below, so the refreshed code is what gets compiled.
            if (serviceConfigurationService.refreshSystemTemplate(serviceConfig)) {
                requiresSave = true;
            }
        }

        if (requiresSave) {
            try {
                serviceConfigurationService.saveServiceConfiguration(tenant, serviceConfig);
            } catch (JsonProcessingException e) {
                log.error("{} - Error saving service configuration: {}", tenant, e.getMessage(), e);
            }
        }

        return serviceConfig;
    }

    private List<Future<?>> initializeConnectors(String tenant, ServiceConfiguration serviceConfig) {
        try {
            connectorRegistry.registerConnectors();
            return setupConnectorConfigurations(tenant, serviceConfig);
        } catch (Exception e) {
            log.error("{} - Error initializing connectors: {}", tenant, e.getMessage(), e);
        }
        return null;
    }

    private List<Future<?>> setupConnectorConfigurations(String tenant, ServiceConfiguration serviceConfig)
            throws ConnectorRegistryException, ConnectorException, ExecutionException, InterruptedException {
        List<ConnectorConfiguration> connectorConfigs = connectorConfigurationService
                .getConnectorConfigurations(tenant);

        ConnectorConfiguration httpConfig = null;
        List<Future<?>> connectTasks = new ArrayList<>();
        for (ConnectorConfiguration config : connectorConfigs) {
            Future<?> connectTask = initializeConnectorByConfiguration(config, serviceConfig, tenant);
            if (connectTask != null)
                connectTasks.add(connectTask);

            if (ConnectorType.HTTP.equals(config.getConnectorType())) {
                httpConfig = config;
            }
        }

        if (httpConfig == null) {
            createAndInitializeDefaultHttpConnector(tenant, serviceConfig);
        }

        createAndInitializeTestConnector(tenant, serviceConfig);
        return connectTasks;
    }

    private void createAndInitializeDefaultHttpConnector(String tenant, ServiceConfiguration serviceConfig)
            throws ConnectorRegistryException {
        ConnectorConfiguration httpConfig = new ConnectorConfiguration();
        httpConfig.setConnectorType(ConnectorType.HTTP);
        httpConfig.setIdentifier(HttpClient.HTTP_CONNECTOR_IDENTIFIER);
        httpConfig.setEnabled(true);
        httpConfig.setName("Default HTTP Connector");

        HttpClient initialHttpClient = new HttpClient();
        initialHttpClient.getConnectorSpecification().getProperties()
                .forEach((key, prop) -> httpConfig.getProperties().put(key, prop.defaultValue));

        try {
            connectorConfigurationService.saveConnectorConfiguration(httpConfig);
            initializeConnectorByConfiguration(httpConfig, serviceConfig, tenant);
        } catch (ConnectorException | JsonProcessingException e) {
            throw new ConnectorRegistryException(e.getMessage());
        }
    }

    private void createAndInitializeTestConnector(String tenant, ServiceConfiguration serviceConfiguration)
            throws ConnectorRegistryException {
        ConnectorConfiguration testConnectorConfig = new ConnectorConfiguration();
        testConnectorConfig.setConnectorType(ConnectorType.TEST);
        testConnectorConfig.setIdentifier(TestClient.TEST_CONNECTOR_IDENTIFIER);
        testConnectorConfig.setEnabled(true);
        testConnectorConfig.setName("Test Connector");

        TestClient initialTestClient = new TestClient();
        initialTestClient.setTenant(tenant);
        initialTestClient.getConnectorSpecification().getProperties()
                .forEach((key, prop) -> testConnectorConfig.getProperties().put(key, prop.defaultValue));
        initialTestClient.setConnectorConfiguration(testConnectorConfig);

        try {
            connectorConfigurationService.saveConnectorConfiguration(testConnectorConfig);
            // initializeConnectorByConfiguration() already builds a fully-initialized TestClient
            // via the factory (initializeManagers() called) and registers its outbound dispatcher.
            // Do NOT redundantly re-register here with initialTestClient: it was only constructed
            // via the no-arg constructor to read default connector-spec properties above, so its
            // explorerListeners/outboundExplorerListeners are never initialized — wiring it in as
            // the outbound dispatcher replaces the good one and NPEs on the next notification.
            initializeConnectorByConfiguration(testConnectorConfig, serviceConfiguration, tenant);
        } catch (ConnectorException | JsonProcessingException e) {
            throw new ConnectorRegistryException(e.getMessage());
        }
    }

    // deleteConnectorResources will delete connector-specific resources like Pulsar subscriptions
    // This must be called before disconnecting the connector (while client is still in registry)
    public void deleteConnectorResources(String tenant, String connectorIdentifier)
            throws ConnectorRegistryException {
        AConnectorClient client = connectorRegistry.getClientForTenant(tenant, connectorIdentifier);
        if (client == null) {
            log.warn("{} - Cannot delete resources for connector {}: client not found in registry (already disconnected)",
                    tenant, connectorIdentifier);
            return;
        }

        if (client instanceof dynamic.mapper.connector.pulsar.MQTTServicePulsarClient) {
            dynamic.mapper.connector.pulsar.MQTTServicePulsarClient pulsarClient =
                (dynamic.mapper.connector.pulsar.MQTTServicePulsarClient) client;
            pulsarClient.deleteResources();
        }
    }

    // shutdownAndRemoveConnector will unsubscribe the subscriber which drops all
    // queues
    public void shutdownAndRemoveConnector(String tenant, String connectorIdentifier)
            throws ConnectorRegistryException {
        // connectorRegistry.unregisterClient(tenant, connectorIdentifier);
        ServiceConfiguration serviceConfiguration = serviceConfigurationService.getServiceConfiguration(tenant);
        if (serviceConfiguration.getOutboundMappingEnabled()) {
            serviceRegistry.getNotificationSubscriber().unsubscribeDeviceSubscriberByConnector(tenant,
                    connectorIdentifier);
            serviceRegistry.getNotificationSubscriber().removeConnector(tenant, connectorIdentifier);
        }
    }

    // DisableConnector will just clean-up maps and disconnects Notification 2.0 -
    // queues will be kept
    public void disableConnector(String tenant, String connectorIdentifier) throws ConnectorRegistryException {
        connectorRegistry.unregisterClient(tenant, connectorIdentifier);
        ServiceConfiguration serviceConfiguration = serviceConfigurationService.getServiceConfiguration(tenant);
        if (serviceConfiguration.getOutboundMappingEnabled()) {
            serviceRegistry.getNotificationSubscriber().removeConnector(tenant, connectorIdentifier);
        }
    }

    private void initResourcesForOutbound(String tenant, ServiceConfiguration serviceConfig) {
        log.info("{} - Config mappingOutbound enabled: {}", tenant, serviceConfig.getOutboundMappingEnabled());

        if (!serviceConfig.getOutboundMappingEnabled()) {
            return;
        }

        if (!serviceRegistry.getNotificationSubscriber().isNotificationServiceAvailable(tenant)) {
            disableOutboundMapping(tenant, serviceConfig);
        } else {
            // serviceRegistry.getNotificationSubscriber().initializeDeviceClient(tenant);
            // serviceRegistry.getNotificationSubscriber().initializeManagementClient(tenant);
            serviceRegistry.getNotificationSubscriber().notificationSubscriberReconnect(tenant);
            serviceRegistry.getNotificationSubscriber().startTokenRefresh(tenant);
        }
    }

    private void disableOutboundMapping(String tenant, ServiceConfiguration serviceConfig) {
        try {
            serviceConfig.setOutboundMappingEnabled(false);
            serviceConfigurationService.saveServiceConfiguration(tenant, serviceConfig);
        } catch (JsonProcessingException e) {
            log.error("{} - Error saving service configuration: {}", tenant, e.getMessage(), e);
        }
    }

    public Future<?> initializeConnectorByConfiguration(ConnectorConfiguration connectorConfiguration,
            ServiceConfiguration serviceConfiguration, String tenant)
            throws ConnectorRegistryException, ConnectorException {
        AConnectorClient connectorClient = null;
        Future<?> future = null;
        if (connectorConfiguration.getEnabled()) {
            try {
                connectorClient = connectorClientFactory.createConnectorClient(connectorConfiguration,
                        additionalSubscriptionIdTest, tenant);
            } catch (ConnectorException e) {
                log.error("{} - Error on creating connector {}", tenant,
                        connectorConfiguration.getConnectorType(),
                        e);
                throw new ConnectorRegistryException(e.getMessage());
            }
            if (connectorClient == null) {
                log.warn("{} - Connector type {} (identifier: {}) could not be created and will be skipped.",
                        tenant, connectorConfiguration.getConnectorType(),
                        connectorConfiguration.getIdentifier());
                return null;
            }
            connectorRegistry.registerClient(tenant, connectorClient);
            // initialize AsynchronousDispatcherInbound
            // DispatcherInbound dispatcherInbound = new
            // DispatcherInbound(serviceRegistry,
            // connectorClient);
            GenericMessageCallback dispatcherInbound = new InboundMessageDispatcher(serviceRegistry,
                    connectorClient);
            connectorClient.setDispatcher(dispatcherInbound);
            // Connection is done async, future is returned to wait for the connection if
            // needed
            future = connectorClient.reconnect();
            connectorClient.submitHousekeeping();
            serviceRegistry.initializeOutboundMapping(tenant, serviceConfiguration, connectorClient);
        }
        return future;
    }

    @Scheduled(cron = "0 * * * * *")
    public void cleanUpCaches() {
        subscriptionsService.runForEachTenant(() -> {
            String tenant = subscriptionsService.getTenant();
            try {
                cleanupCachesForTenant(tenant);
            } catch (Exception e) {
                log.error("{} - Error executing Cache Cleanup Scheduler", tenant, e);
            }
        });
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

    private void cleanupCachesForTenant(String tenant) {

        ServiceConfiguration serviceConfig = serviceConfigurationService.getServiceConfiguration(tenant);

        Instant cacheRetentionStartInbound = cacheInboundExternalIdRetentionStartMap.get(tenant);
        if (cacheRetentionStartInbound != null) {

            int retentionDaysInbound = serviceConfig.getInboundExternalIdCacheRetention();

            if (shouldClearCache(cacheRetentionStartInbound, retentionDaysInbound)) {
                int cacheSize = cacheManager.getSizeInboundExternalIdCache(tenant);
                cacheManager.clearInboundExternalIdCache(tenant, false, cacheSize);
                cacheInboundExternalIdRetentionStartMap.put(tenant, Instant.now());

                log.info("{} - Identity cache cleared. Old Size: {}, New size: {}",
                        tenant, cacheSize, cacheManager.getSizeInboundExternalIdCache(tenant));
            }
        }

        Instant cacheRetentionStartOutbound = cacheOutboundExternalIdRetentionStartMap.get(tenant);
        if (cacheRetentionStartOutbound != null) {

            int retentionDaysOutbound = serviceConfig.getOutboundExternalIdCacheRetention();

            if (shouldClearCache(cacheRetentionStartOutbound, retentionDaysOutbound)) {
                int cacheSize = cacheManager.getSizeOutboundExternalIdCache(tenant);
                cacheManager.clearOutboundExternalIdCache(tenant, false, cacheSize);
                cacheOutboundExternalIdRetentionStartMap.put(tenant, Instant.now());

                log.info("{} - Outbound identity cache cleared. Old Size: {}, New size: {}",
                        tenant, cacheSize, cacheManager.getSizeOutboundExternalIdCache(tenant));
            }
        }

        Instant cacheRetentionStartInventory = cacheInventoryRetentionStartMap.get(tenant);
        if (cacheRetentionStartInventory != null) {

            int retentionDaysInventory = serviceConfig.getInventoryCacheRetention();

            if (shouldClearCache(cacheRetentionStartInventory, retentionDaysInventory)) {
                int cacheSize = cacheManager.getInventoryCache(tenant).getCacheSize();
                cacheManager.clearInventoryCache(tenant, false, cacheSize);
                cacheInventoryRetentionStartMap.put(tenant, Instant.now());

                log.info("{} - Inventory cache cleared. Old Size: {}, New size: {}",
                        tenant, cacheSize, cacheManager.getInventoryCache(tenant).getCacheSize());
            }
        }

        if (serviceConfig.getFlowStateRetention() != null) {
            flowStateStore.clearExpiredEntries(tenant, serviceConfig.getFlowStateRetention());
        }
    }

    private Boolean shouldClearCache(Instant cacheRetentionStart, int retentionDays) {
        return retentionDays > 0 &&
                Duration.between(cacheRetentionStart, Instant.now()).compareTo(Duration.ofDays(retentionDays)) >= 0;
    }
}
