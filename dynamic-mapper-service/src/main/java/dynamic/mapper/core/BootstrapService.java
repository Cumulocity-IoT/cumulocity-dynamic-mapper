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

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import dynamic.mapper.configuration.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.cumulocity.microservice.context.credentials.MicroserviceCredentials;
import com.cumulocity.microservice.subscription.model.MicroserviceSubscriptionAddedEvent;
import com.cumulocity.microservice.subscription.model.MicroserviceSubscriptionRemovedEvent;
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
import dynamic.mapper.processor.inbound.CamelDispatcherInbound;

import dynamic.mapper.service.ConnectorConfigurationService;
import dynamic.mapper.service.ExtensionInboundRegistry;
import dynamic.mapper.service.MappingService;
import dynamic.mapper.service.ServiceConfigurationService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

@Service
@EnableScheduling
@Slf4j
public class BootstrapService {
    private final ConnectorRegistry connectorRegistry;
    final ConfigurationRegistry configurationRegistry;
    private final C8YAgent c8YAgent;
    private final MappingService mappingService;
    private final ServiceConfigurationService serviceConfigurationService;
    private final ConnectorConfigurationService connectorConfigurationService;
    private final MicroserviceSubscriptionsService subscriptionsService;
    private final String additionalSubscriptionIdTest;
    private final Integer inboundExternalIdCacheSize;
    private final Integer inventoryCacheSize;
    private final Map<String, Instant> cacheInboundExternalIdRetentionStartMap;
    private final Map<String, Instant> cacheInventoryRetentionStartMap;
    private final ExtensionInboundRegistry extensionInboundRegistry;

    @Qualifier("virtualThreadPool")
    private ExecutorService virtualThreadPool;

    @Autowired
    public void setVirtualThreadPool(ExecutorService virtualThreadPool) {
        this.virtualThreadPool = virtualThreadPool;
    }

    @Autowired
    private AIAgentService aiAgentService;

    @Autowired
    private ExtensionManager extensionManager;

    public BootstrapService(
            ConnectorRegistry connectorRegistry,
            ConfigurationRegistry configurationRegistry,
            C8YAgent c8YAgent,
            MappingService mappingService,
            ServiceConfigurationService serviceConfigurationService,
            ConnectorConfigurationService connectorConfigurationService,
            MicroserviceSubscriptionsService subscriptionsService,
            ExtensionInboundRegistry extensionInboundRegistry,
            @Value("${APP.additionalSubscriptionIdTest}") String additionalSubscriptionIdTest,
            @Value("#{new Integer('${APP.inboundExternalIdCacheSize}')}") Integer inboundExternalIdCacheSize,
            @Value("#{new Integer('${APP.inventoryCacheSize}')}") Integer inventoryCacheSize) {

        this.connectorRegistry = connectorRegistry;
        this.configurationRegistry = configurationRegistry;
        this.c8YAgent = c8YAgent;
        this.mappingService = mappingService;
        this.serviceConfigurationService = serviceConfigurationService;
        this.connectorConfigurationService = connectorConfigurationService;
        this.subscriptionsService = subscriptionsService;
        this.extensionInboundRegistry = extensionInboundRegistry;
        this.additionalSubscriptionIdTest = additionalSubscriptionIdTest;
        this.inboundExternalIdCacheSize = inboundExternalIdCacheSize;
        this.inventoryCacheSize = inventoryCacheSize;
        this.cacheInboundExternalIdRetentionStartMap = new ConcurrentHashMap<>();
        this.cacheInventoryRetentionStartMap = new ConcurrentHashMap<>();
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"));
    }

    @PreDestroy
    public void destroy() {
        log.info("Shutting down mapper...");
        subscriptionsService.runForEachTenant(() -> {
            String tenant = subscriptionsService.getTenant();
            try {
                log.info("{} - Cleaning up tenant resources", tenant);

                // Disconnect notification subscriber
                configurationRegistry.getNotificationSubscriber().disconnect(tenant);

                // Unregister all connector clients
                connectorRegistry.unregisterAllClientsForTenant(tenant);

                log.info("{} - Successfully cleaned up tenant resources", tenant);
            } catch (ConnectorRegistryException e) {
                log.error("{} - Error during shutdown: {}", tenant, e.getMessage(), e);
                throw new RuntimeException(e);
            }
        });

        log.info("Mapper shutdown completed");
    }

    @EventListener
    public void unsubscribeTenant(MicroserviceSubscriptionRemovedEvent event) {
        String tenant = event.getTenant();
        log.info("{} - Microservice unsubscribed", tenant);

        try {
            cleanTenantResources(tenant);
        } catch (Exception e) {
            log.error("{} - Error during unsubscribing cleanup: {}", tenant, e.getMessage(), e);
        }
    }

    private void cleanTenantResources(String tenant) throws ConnectorRegistryException {
        log.info("{} - Starting tenant resource cleanup", tenant);

        NotificationSubscriber subscriber = configurationRegistry.getNotificationSubscriber();

        try {
            // Disconnect all notification connections
            subscriber.disconnect(tenant);
            log.debug("{} - Disconnected notification subscriber", tenant);
        } catch (Exception e) {
            log.error("{} - Error disconnecting subscriber: {}", tenant, e.getMessage(), e);
        }

        try {
            // Unsubscribe device tokens
            subscriber.unsubscribeDeviceSubscriber(tenant);
            log.debug("{} - Unsubscribed device subscriber", tenant);
        } catch (Exception e) {
            log.error("{} - Error unsubscribing device subscriber: {}", tenant, e.getMessage(), e);
        }

        try {
            // Unsubscribe device group tokens
            subscriber.unsubscribeDeviceGroupSubscriber(tenant);
            log.debug("{} - Unsubscribed device group subscriber", tenant);
        } catch (Exception e) {
            log.error("{} - Error unsubscribing device group subscriber: {}", tenant, e.getMessage(), e);
        }

        try {
            // Unregister all connector clients
            connectorRegistry.unregisterAllClientsForTenant(tenant);
            log.debug("{} - Unregistered all connector clients", tenant);
        } catch (Exception e) {
            log.error("{} - Error unregistering clients: {}", tenant, e.getMessage(), e);
        }

        try {
            // Clean up configurations
            configurationRegistry.removeServiceConfiguration(tenant);
            log.debug("{} - Removed service configuration", tenant);
        } catch (Exception e) {
            log.error("{} - Error removing service configuration: {}", tenant, e.getMessage(), e);
        }

        try {
            // DO NOT REMOVE DeviceIsolationMQTTService feature
            ServiceConfiguration serviceConfiguration = serviceConfigurationService.getServiceConfiguration(tenant);
            if (serviceConfiguration.getDeviceIsolationMQTTServiceEnabled()) {
                configurationRegistry.clearCacheDeviceToClient(tenant);
            }

            configurationRegistry.removeMapperServiceRepresentation(tenant);
            configurationRegistry.removeGraalsResources(tenant);
            configurationRegistry.removeMicroserviceCredentials(tenant);
            log.debug("{} - Removed configuration registry resources", tenant);
        } catch (Exception e) {
            log.error("{} - Error removing configuration registry resources: {}", tenant, e.getMessage(), e);
        }

        try {
            extensionInboundRegistry.deleteExtensions(tenant);
            log.debug("{} - Deleted extensions", tenant);
        } catch (Exception e) {
            log.error("{} - Error deleting extensions: {}", tenant, e.getMessage(), e);
        }

        try {
            mappingService.removeResources(tenant);
            log.debug("{} - Removed mapping service resources", tenant);
        } catch (Exception e) {
            log.error("{} - Error removing mapping service resources: {}", tenant, e.getMessage(), e);
        }

        try {
            connectorRegistry.removeResources(tenant);
            log.debug("{} - Removed connector registry resources", tenant);
        } catch (Exception e) {
            log.error("{} - Error removing connector registry resources: {}", tenant, e.getMessage(), e);
        }

        try {
            c8YAgent.removeInboundExternalIdCache(tenant);
            c8YAgent.removeInventoryCache(tenant);
            log.debug("{} - Removed C8Y agent caches", tenant);
        } catch (Exception e) {
            log.error("{} - Error removing C8Y agent caches: {}", tenant, e.getMessage(), e);
        }

        log.info("{} - Completed tenant resource cleanup", tenant);
    }

    @EventListener
    public void subscribeTenant(MicroserviceSubscriptionAddedEvent event) {
        String tenant = event.getCredentials().getTenant();
        log.info("{} - Microservice subscribed", tenant);

        try {
            initializeTenantResources(tenant, event.getCredentials());
        } catch (Exception e) {
            log.error("{} - Initialization error: {}", tenant, e.getMessage(), e);
        }
    }

    private void initializeTenantResources(String tenant, MicroserviceCredentials credentials) {

        extensionInboundRegistry.initializeExtensions(tenant);

        c8YAgent.createExtensibleProcessor(tenant);
        extensionManager.loadProcessorExtensions(tenant);

        ServiceConfiguration serviceConfiguration = initializeServiceConfiguration(tenant);
        initializeCaches(tenant, serviceConfiguration);

        configurationRegistry.addMicroserviceCredentials(tenant, credentials);
        configurationRegistry.initializeResources(tenant);
        configurationRegistry.createGraalsResources(tenant, serviceConfiguration);
        configurationRegistry.initializeMapperServiceRepresentation(tenant);

        // DO NOT REMOVE DeviceIsolationMQTTService feature
        if (serviceConfiguration.getDeviceIsolationMQTTServiceEnabled()) {
            configurationRegistry.initializeDeviceToClientMapRepresentation(tenant);
        }

        mappingService.createResources(tenant);

        connectorRegistry.initializeResources(tenant);

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
        mappingService.initializeResources(tenant);
        aiAgentService.initializeAIAgents();

        initResourcesForOutbound(tenant, serviceConfiguration);
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

        if (serviceConfig.getInventoryCacheSize() == null || serviceConfig.getInventoryCacheSize() == 0) {
            serviceConfig.setInventoryCacheSize(inventoryCacheSize);
            requiresSave = true;
        }

        Map<String, CodeTemplate> codeTemplates = serviceConfig.getCodeTemplates();
        if (codeTemplates == null || codeTemplates.isEmpty()) {
            // Initialize code templates from properties if not already set
            serviceConfigurationService.initCodeTemplates(serviceConfig, false);
            requiresSave = true;
        }
        // else {
        // serviceConfigurationService.migrateCodeTemplates(serviceConfig);
        // }

        if (requiresSave) {
            try {
                serviceConfigurationService.saveServiceConfiguration(tenant, serviceConfig);
            } catch (JsonProcessingException e) {
                log.error("{} - Error saving service configuration: {}", tenant, e.getMessage(), e);
            }
        }

        configurationRegistry.addServiceConfiguration(tenant, serviceConfig);
        return serviceConfig;
    }

    private void initializeCaches(String tenant, ServiceConfiguration serviceConfig) {
        int cacheSizeInbound = Optional.ofNullable(serviceConfig.getInboundExternalIdCacheSize())
                .filter(size -> size != 0)
                .orElse(inboundExternalIdCacheSize);

        int cacheSizeInventory = Optional.ofNullable(serviceConfig.getInventoryCacheSize())
                .filter(size -> size != 0)
                .orElse(inventoryCacheSize);

        c8YAgent.initializeInboundExternalIdCache(tenant, cacheSizeInbound);

        // to test cache eviction
        // c8YAgent.initializeInventoryCache(tenant, 1);
        c8YAgent.initializeInventoryCache(tenant, cacheSizeInventory);

        cacheInboundExternalIdRetentionStartMap.put(tenant, Instant.now());
        cacheInventoryRetentionStartMap.put(tenant, Instant.now());
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
        initialTestClient.getConnectorSpecification().getProperties()
                .forEach((key, prop) -> testConnectorConfig.getProperties().put(key, prop.defaultValue));
        initialTestClient.setConnectorConfiguration(testConnectorConfig);

        try {
            connectorConfigurationService.saveConnectorConfiguration(testConnectorConfig);
            initializeConnectorByConfiguration(testConnectorConfig, serviceConfiguration, tenant);
        } catch (ConnectorException | JsonProcessingException e) {
            throw new ConnectorRegistryException(e.getMessage());
        }

        if (serviceConfiguration.getOutboundMappingEnabled()) {
            configurationRegistry.initializeOutboundMapping(tenant, serviceConfiguration, initialTestClient);
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
            configurationRegistry.getNotificationSubscriber().unsubscribeDeviceSubscriberByConnector(tenant,
                    connectorIdentifier);
            configurationRegistry.getNotificationSubscriber().removeConnector(tenant, connectorIdentifier);
        }
    }

    // DisableConnector will just clean-up maps and disconnects Notification 2.0 -
    // queues will be kept
    public void disableConnector(String tenant, String connectorIdentifier) throws ConnectorRegistryException {
        connectorRegistry.unregisterClient(tenant, connectorIdentifier);
        ServiceConfiguration serviceConfiguration = serviceConfigurationService.getServiceConfiguration(tenant);
        if (serviceConfiguration.getOutboundMappingEnabled()) {
            configurationRegistry.getNotificationSubscriber().removeConnector(tenant, connectorIdentifier);
        }
    }

    private void initResourcesForOutbound(String tenant, ServiceConfiguration serviceConfig) {
        log.info("{} - Config mappingOutbound enabled: {}", tenant, serviceConfig.getOutboundMappingEnabled());

        if (!serviceConfig.getOutboundMappingEnabled()) {
            return;
        }

        if (!configurationRegistry.getNotificationSubscriber().isNotificationServiceAvailable(tenant)) {
            disableOutboundMapping(tenant, serviceConfig);
        } else {
            // configurationRegistry.getNotificationSubscriber().initializeDeviceClient(tenant);
            // configurationRegistry.getNotificationSubscriber().initializeManagementClient(tenant);
            configurationRegistry.getNotificationSubscriber().notificationSubscriberReconnect(tenant);
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
                connectorClient = configurationRegistry.createConnectorClient(connectorConfiguration,
                        additionalSubscriptionIdTest, tenant);
            } catch (ConnectorException e) {
                log.error("{} - Error on creating connector {}", tenant,
                        connectorConfiguration.getConnectorType(),
                        e);
                throw new ConnectorRegistryException(e.getMessage());
            }
            connectorRegistry.registerClient(tenant, connectorClient);
            // initialize AsynchronousDispatcherInbound
            // DispatcherInbound dispatcherInbound = new
            // DispatcherInbound(configurationRegistry,
            // connectorClient);
            GenericMessageCallback dispatcherInbound = new CamelDispatcherInbound(configurationRegistry,
                    connectorClient);
            connectorClient.setDispatcher(dispatcherInbound);
            // Connection is done async, future is returned to wait for the connection if
            // needed
            future = connectorClient.reconnect();
            connectorClient.submitHousekeeping();
            configurationRegistry.initializeOutboundMapping(tenant, serviceConfiguration, connectorClient);
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
    @Scheduled(cron = "* 30 * * * *")
    public void sendDeviceToClientMap() {
        subscriptionsService.runForEachTenant(() -> {
            String tenant = subscriptionsService.getTenant();
            try {
                ServiceConfiguration serviceConfiguration = serviceConfigurationService.getServiceConfiguration(tenant);
                if (serviceConfiguration.getDeviceIsolationMQTTServiceEnabled()) {
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
                int cacheSize = c8YAgent.getInboundExternalIdCacheSize(tenant);
                c8YAgent.clearInboundExternalIdCache(tenant, false, cacheSize);
                cacheInboundExternalIdRetentionStartMap.put(tenant, Instant.now());

                log.info("{} - Identity cache cleared. Old Size: {}, New size: {}",
                        tenant, cacheSize, c8YAgent.getInboundExternalIdCacheSize(tenant));
            }
        }

        Instant cacheRetentionStartInventory = cacheInventoryRetentionStartMap.get(tenant);
        if (cacheRetentionStartInventory != null) {

            int retentionDaysInventory = serviceConfig.getInventoryCacheRetention();

            if (shouldClearCache(cacheRetentionStartInbound, retentionDaysInventory)) {
                int cacheSize = c8YAgent.getInventoryCache(tenant).getCacheSize();
                c8YAgent.clearInventoryCache(tenant, false, cacheSize);
                cacheInventoryRetentionStartMap.put(tenant, Instant.now());

                log.info("{} - Inventory cache cleared. Old Size: {}, New size: {}",
                        tenant, cacheSize, c8YAgent.getInventoryCache(tenant).getCacheSize());
            }
        }
    }

    private Boolean shouldClearCache(Instant cacheRetentionStart, int retentionDays) {
        return retentionDays > 0 &&
                Duration.between(cacheRetentionStart, Instant.now()).compareTo(Duration.ofDays(retentionDays)) >= 0;
    }
}
