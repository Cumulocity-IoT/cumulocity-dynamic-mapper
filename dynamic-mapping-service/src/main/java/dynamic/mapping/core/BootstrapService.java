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

package dynamic.mapping.core;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import dynamic.mapping.configuration.*;
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

import dynamic.mapping.connector.core.client.AConnectorClient;
import dynamic.mapping.connector.core.client.ConnectorException;
import dynamic.mapping.connector.core.client.ConnectorType;
import dynamic.mapping.connector.core.registry.ConnectorRegistry;
import dynamic.mapping.connector.core.registry.ConnectorRegistryException;
import dynamic.mapping.connector.http.HttpClient;
import dynamic.mapping.notification.C8YNotificationSubscriber;
import dynamic.mapping.processor.inbound.DispatcherInbound;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

@Service
@EnableScheduling
@Slf4j
public class BootstrapService {
    private final ConnectorRegistry connectorRegistry;
    final ConfigurationRegistry configurationRegistry;
    private final C8YAgent c8YAgent;
    private final MappingComponent mappingComponent;
    private final ServiceConfigurationComponent serviceConfigurationComponent;
    private final ConnectorConfigurationComponent connectorConfigurationComponent;
    private final MicroserviceSubscriptionsService subscriptionsService;
    private final String additionalSubscriptionIdTest;
    private final Integer inboundExternalIdCacheSize;
    private final Integer inventoryCacheSize;
    private final Map<String, Instant> cacheInboundExternalIdRetentionStartMap;
    private final Map<String, Instant> cacheInventoryRetentionStartMap;

    @Qualifier("virtualThreadPool")
    private ExecutorService virtualThreadPool;

    @Autowired
    public void setVirtualThreadPool(ExecutorService virtualThreadPool) {
        this.virtualThreadPool = virtualThreadPool;
    }

    public BootstrapService(
            ConnectorRegistry connectorRegistry,
            ConfigurationRegistry configurationRegistry,
            C8YAgent c8YAgent,
            MappingComponent mappingComponent,
            ServiceConfigurationComponent serviceConfigurationComponent,
            ConnectorConfigurationComponent connectorConfigurationComponent,
            MicroserviceSubscriptionsService subscriptionsService,
            @Value("${APP.additionalSubscriptionIdTest}") String additionalSubscriptionIdTest,
            @Value("#{new Integer('${APP.inboundExternalIdCacheSize}')}") Integer inboundExternalIdCacheSize,
            @Value("#{new Integer('${APP.inventoryCacheSize}')}") Integer inventoryCacheSize) {

        this.connectorRegistry = connectorRegistry;
        this.configurationRegistry = configurationRegistry;
        this.c8YAgent = c8YAgent;
        this.mappingComponent = mappingComponent;
        this.serviceConfigurationComponent = serviceConfigurationComponent;
        this.connectorConfigurationComponent = connectorConfigurationComponent;
        this.subscriptionsService = subscriptionsService;
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
        subscriptionsService.runForEachTenant(
                () -> configurationRegistry.getNotificationSubscriber().disconnect(subscriptionsService.getTenant()));
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
        C8YNotificationSubscriber subscriber = configurationRegistry.getNotificationSubscriber();
        subscriber.disconnect(tenant);
        subscriber.unsubscribeDeviceSubscriber(tenant);

        connectorRegistry.unregisterAllClientsForTenant(tenant);

        // Clean up configurations
        configurationRegistry.removeServiceConfiguration(tenant);
        configurationRegistry.removeMappingServiceRepresentation(tenant);
        configurationRegistry.removePayloadProcessorsInbound(tenant);
        configurationRegistry.removePayloadProcessorsOutbound(tenant);
        configurationRegistry.removeExtensibleProcessor(tenant);
        configurationRegistry.removeGraalsResources(tenant);
        configurationRegistry.removeMicroserviceCredentials(tenant);

        mappingComponent.removeResources(tenant);

        connectorRegistry.removeResources(tenant);

        c8YAgent.removeInboundExternalIdCache(tenant);
        c8YAgent.removeInventoryCache(tenant);
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
        c8YAgent.createExtensibleProcessor(tenant);
        c8YAgent.loadProcessorExtensions(tenant);

        
        ServiceConfiguration serviceConfiguration = initializeServiceConfiguration(tenant);
        initializeCaches(tenant, serviceConfiguration);
        
        configurationRegistry.addMicroserviceCredentials(tenant, credentials);
        configurationRegistry.initializeResources(tenant);
        configurationRegistry.createGraalsResources(tenant, serviceConfiguration);
        configurationRegistry.initializeMappingServiceRepresentation(tenant);
        
        mappingComponent.createResources(tenant);

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
        mappingComponent.initializeResources(tenant);

        handleOutboundMapping(tenant, serviceConfiguration);
    }

    private ServiceConfiguration initializeServiceConfiguration(String tenant) {
        ServiceConfiguration serviceConfig = serviceConfigurationComponent.getServiceConfiguration(tenant);
        boolean requiresSave = false;

        if (serviceConfig.inboundExternalIdCacheSize == null || serviceConfig.inboundExternalIdCacheSize == 0) {
            serviceConfig.inboundExternalIdCacheSize = inboundExternalIdCacheSize;
            requiresSave = true;
        }

        if (serviceConfig.inboundExternalIdCacheRetention == null) {
            serviceConfig.inboundExternalIdCacheRetention = 1;
            requiresSave = true;
        }

        if (serviceConfig.inventoryCacheSize == null || serviceConfig.inventoryCacheSize == 0) {
            serviceConfig.inventoryCacheSize = inventoryCacheSize;
            requiresSave = true;
        }

        Map<String, CodeTemplate> codeTemplates = serviceConfig.getCodeTemplates();
        if (codeTemplates == null || codeTemplates.isEmpty()) {
            // Initialize code templates from properties if not already set
            serviceConfigurationComponent.initCodeTemplates(serviceConfig, false);
            requiresSave = true;
        }

        if (requiresSave) {
            try {
                serviceConfigurationComponent.saveServiceConfiguration(tenant, serviceConfig);
            } catch (JsonProcessingException e) {
                log.error("{} - Error saving service configuration: {}", tenant, e.getMessage(), e);
            }
        }

        configurationRegistry.addServiceConfiguration(tenant, serviceConfig);
        return serviceConfig;
    }

    private void initializeCaches(String tenant, ServiceConfiguration serviceConfig) {
        int cacheSizeInbound = Optional.ofNullable(serviceConfig.inboundExternalIdCacheSize)
                .filter(size -> size != 0)
                .orElse(inboundExternalIdCacheSize);

        int cacheSizeInventory = Optional.ofNullable(serviceConfig.inventoryCacheSize)
                .filter(size -> size != 0)
                .orElse(inventoryCacheSize);

        c8YAgent.initializeInboundExternalIdCache(tenant, cacheSizeInbound);
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
        List<ConnectorConfiguration> connectorConfigs = connectorConfigurationComponent
                .getConnectorConfigurations(tenant);

        ConnectorConfiguration httpConfig = null;
        List<Future<?>> connectTasks = new ArrayList<>();
        for (ConnectorConfiguration config : connectorConfigs) {
            Future<?> connectTask = initializeConnectorByConfiguration(config, serviceConfig, tenant);
            if (connectTask != null)
                connectTasks.add(connectTask);

            if (ConnectorType.HTTP.equals(config.connectorType)) {
                httpConfig = config;
            }
        }

        if (httpConfig == null) {
            createAndInitializeDefaultHttpConnector(tenant, serviceConfig);
        }
        return connectTasks;
    }

    private void createAndInitializeDefaultHttpConnector(String tenant, ServiceConfiguration serviceConfig)
            throws ConnectorRegistryException {
        ConnectorConfiguration httpConfig = new ConnectorConfiguration();
        httpConfig.connectorType = ConnectorType.HTTP;
        httpConfig.identifier = HttpClient.HTTP_CONNECTOR_IDENTIFIER;
        httpConfig.enabled = true;
        httpConfig.name = "Default HTTP Connector";

        HttpClient initialHttpClient = new HttpClient();
        initialHttpClient.getConnectorSpecification().getProperties()
                .forEach((key, prop) -> httpConfig.properties.put(key, prop.defaultValue));

        try {
            connectorConfigurationComponent.saveConnectorConfiguration(httpConfig);
            initializeConnectorByConfiguration(httpConfig, serviceConfig, tenant);
        } catch (ConnectorException | JsonProcessingException e) {
            throw new ConnectorRegistryException(e.getMessage());
        }
    }

    // shutdownAndRemoveConnector will unsubscribe the subscriber which drops all
    // queues
    public void shutdownAndRemoveConnector(String tenant, String connectorIdentifier)
            throws ConnectorRegistryException {
        // connectorRegistry.unregisterClient(tenant, connectorIdentifier);
        ServiceConfiguration serviceConfiguration = serviceConfigurationComponent.getServiceConfiguration(tenant);
        if (serviceConfiguration.isOutboundMappingEnabled()) {
            configurationRegistry.getNotificationSubscriber().unsubscribeDeviceSubscriberByConnector(tenant,
                    connectorIdentifier);
            configurationRegistry.getNotificationSubscriber().removeConnector(tenant, connectorIdentifier);
        }
    }

    // DisableConnector will just clean-up maps and disconnects Notification 2.0 -
    // queues will be kept
    public void disableConnector(String tenant, String connectorIdentifier) throws ConnectorRegistryException {
        connectorRegistry.unregisterClient(tenant, connectorIdentifier);
        ServiceConfiguration serviceConfiguration = serviceConfigurationComponent.getServiceConfiguration(tenant);
        if (serviceConfiguration.isOutboundMappingEnabled()) {
            configurationRegistry.getNotificationSubscriber().removeConnector(tenant, connectorIdentifier);
        }
    }

    private void handleOutboundMapping(String tenant, ServiceConfiguration serviceConfig) {
        log.info("{} - Config mappingOutbound enabled: {}", tenant, serviceConfig.isOutboundMappingEnabled());

        if (!serviceConfig.isOutboundMappingEnabled()) {
            return;
        }

        if (!configurationRegistry.getNotificationSubscriber().isNotificationServiceAvailable(tenant)) {
            disableOutboundMapping(tenant, serviceConfig);
        } else {
            configurationRegistry.getNotificationSubscriber().initDeviceClient();
        }
    }

    private void disableOutboundMapping(String tenant, ServiceConfiguration serviceConfig) {
        try {
            serviceConfig.setOutboundMappingEnabled(false);
            serviceConfigurationComponent.saveServiceConfiguration(tenant, serviceConfig);
        } catch (JsonProcessingException e) {
            log.error("{} - Error saving service configuration: {}", tenant, e.getMessage(), e);
        }
    }

    public Future<?> initializeConnectorByConfiguration(ConnectorConfiguration connectorConfiguration,
            ServiceConfiguration serviceConfiguration, String tenant)
            throws ConnectorRegistryException, ConnectorException {
        AConnectorClient connectorClient = null;
        Future<?> future = null;
        if (connectorConfiguration.isEnabled()) {
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
            DispatcherInbound dispatcherInbound = new DispatcherInbound(configurationRegistry,
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
            cleanupCachesForTenant(tenant);
        });
    }

    private void cleanupCachesForTenant(String tenant) {

        ServiceConfiguration serviceConfig = serviceConfigurationComponent.getServiceConfiguration(tenant);

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

    private boolean shouldClearCache(Instant cacheRetentionStart, int retentionDays) {
        return retentionDays > 0 &&
                Duration.between(cacheRetentionStart, Instant.now()).compareTo(Duration.ofDays(retentionDays)) >= 0;
    }
}
