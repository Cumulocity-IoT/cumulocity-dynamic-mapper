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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

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
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.fasterxml.jackson.core.JsonProcessingException;

import dynamic.mapping.configuration.ConnectorConfiguration;
import dynamic.mapping.configuration.ConnectorConfigurationComponent;
import dynamic.mapping.configuration.ServiceConfiguration;
import dynamic.mapping.configuration.ServiceConfigurationComponent;
import dynamic.mapping.connector.core.client.AConnectorClient;
import dynamic.mapping.connector.core.client.ConnectorException;
import dynamic.mapping.connector.core.client.ConnectorType;
import dynamic.mapping.connector.core.registry.ConnectorRegistry;
import dynamic.mapping.connector.core.registry.ConnectorRegistryException;
import dynamic.mapping.connector.http.HttpClient;
import dynamic.mapping.connector.kafka.KafkaClient;
import dynamic.mapping.connector.mqtt.MQTTClient;
import dynamic.mapping.connector.mqtt.MQTTServiceClient;
import dynamic.mapping.connector.webhook.WebHook;
import dynamic.mapping.model.Direction;
import dynamic.mapping.model.MappingServiceRepresentation;
import dynamic.mapping.notification.C8YNotificationSubscriber;
import dynamic.mapping.processor.inbound.DispatcherInbound;
import dynamic.mapping.processor.outbound.DispatcherOutbound;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

@Service
@EnableScheduling
@Slf4j
public class BootstrapService {
    private final ConnectorRegistry connectorRegistry;
    private final ConfigurationRegistry configurationRegistry;
    private final C8YAgent c8YAgent;
    private final MappingComponent mappingComponent;
    private final ServiceConfigurationComponent serviceConfigurationComponent;
    private final ConnectorConfigurationComponent connectorConfigurationComponent;
    private final MicroserviceSubscriptionsService subscriptionsService;
    private final String additionalSubscriptionIdTest;
    private final Integer inboundExternalIdCacheSize;
    private final Map<String, Instant> cacheRetentionStartMap;

    @Qualifier("virtThreadPool")
    private ExecutorService virtThreadPool;

    @Autowired
    public void setVirtThreadPool(ExecutorService virtThreadPool) {
        this.virtThreadPool = virtThreadPool;
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
            @Value("#{new Integer('${APP.inboundExternalIdCacheSize}')}") Integer inboundExternalIdCacheSize) {

        this.connectorRegistry = connectorRegistry;
        this.configurationRegistry = configurationRegistry;
        this.c8YAgent = c8YAgent;
        this.mappingComponent = mappingComponent;
        this.serviceConfigurationComponent = serviceConfigurationComponent;
        this.connectorConfigurationComponent = connectorConfigurationComponent;
        this.subscriptionsService = subscriptionsService;
        this.additionalSubscriptionIdTest = additionalSubscriptionIdTest;
        this.inboundExternalIdCacheSize = inboundExternalIdCacheSize;
        this.cacheRetentionStartMap = new ConcurrentHashMap<>();
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
        log.info("Tenant {} - Microservice unsubscribed", tenant);

        try {
            cleanupTenantResources(tenant);
        } catch (Exception e) {
            log.error("Tenant {} - Error during unsubscription cleanup: {}", tenant, e.getMessage());
        }
    }

    private void cleanupTenantResources(String tenant) throws ConnectorRegistryException {
        C8YNotificationSubscriber subscriber = configurationRegistry.getNotificationSubscriber();
        subscriber.disconnect(tenant);
        subscriber.unsubscribeDeviceSubscriber(tenant);

        connectorRegistry.unregisterAllClientsForTenant(tenant);

        // Clean up configurations
        configurationRegistry.getServiceConfigurations().remove(tenant);
        configurationRegistry.getMappingServiceRepresentations().remove(tenant);
        mappingComponent.cleanMappingStatus(tenant);
        configurationRegistry.getPayloadProcessorsInbound().remove(tenant);
        configurationRegistry.getPayloadProcessorsOutbound().remove(tenant);

        c8YAgent.deleteInboundExternalIdCache(tenant);
    }

    @EventListener
    public void initialize(MicroserviceSubscriptionAddedEvent event) {
        String tenant = event.getCredentials().getTenant();
        log.info("Tenant {} - Microservice subscribed", tenant);

        try {
            initializeTenantResources(tenant, event.getCredentials());
        } catch (Exception e) {
            log.error("Tenant {} - Initialization error: {}", tenant, e.getMessage());
        }
    }

    private void initializeTenantResources(String tenant, MicroserviceCredentials credentials) {
        configurationRegistry.getMicroserviceCredentials().put(tenant, credentials);

        ServiceConfiguration serviceConfig = initializeServiceConfiguration(tenant);
        initializeCache(tenant, serviceConfig);
        initializeTimeZoneAndMappings(tenant);
        initializeConnectors(tenant, serviceConfig);

        handleOutboundMapping(tenant, serviceConfig);
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

        if (requiresSave) {
            try {
                serviceConfigurationComponent.saveServiceConfiguration(serviceConfig);
            } catch (JsonProcessingException e) {
                log.error("Tenant {} - Error saving service configuration: {}", tenant, e.getMessage());
            }
        }

        configurationRegistry.getServiceConfigurations().put(tenant, serviceConfig);
        return serviceConfig;
    }

    private void initializeCache(String tenant, ServiceConfiguration serviceConfig) {
        int cacheSize = Optional.ofNullable(serviceConfig.inboundExternalIdCacheSize)
                .filter(size -> size != 0)
                .orElse(inboundExternalIdCacheSize);

        c8YAgent.initializeInboundExternalIdCache(tenant, cacheSize);
        cacheRetentionStartMap.put(tenant, Instant.now());
    }

    private void initializeTimeZoneAndMappings(String tenant) {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"));

        ManagedObjectRepresentation mappingServiceMOR = configurationRegistry.getC8yAgent()
                .initializeMappingServiceObject(tenant);

        configurationRegistry.getC8yAgent().createExtensibleProcessor(tenant);
        configurationRegistry.getC8yAgent().loadProcessorExtensions(tenant);

        MappingServiceRepresentation mappingServiceRepresentation = configurationRegistry.getObjectMapper()
                .convertValue(mappingServiceMOR, MappingServiceRepresentation.class);

        configurationRegistry.getMappingServiceRepresentations().put(tenant, mappingServiceRepresentation);

        initializeMappingComponents(tenant);
    }

    private void initializeMappingComponents(String tenant) {
        mappingComponent.initializeMappingStatus(tenant, false);
        mappingComponent.initializeDeploymentMap(tenant, false);
        mappingComponent.initializeMappingCaches(tenant);
        mappingComponent.rebuildMappingOutboundCache(tenant);
        mappingComponent.rebuildMappingInboundCache(tenant);
    }

    private void initializeConnectors(String tenant, ServiceConfiguration serviceConfig) {
        try {
            initializeConnectorRegistry(tenant);
            registerDefaultConnectors();
            setupConnectorConfigurations(tenant, serviceConfig);
        } catch (Exception e) {
            log.error("Tenant {} - Error initializing connectors: {}", tenant, e.getMessage());
        }
    }

    private void initializeConnectorRegistry(String tenant) {
        if (connectorRegistry.getConnectorStatusMap().get(tenant) == null) {
            connectorRegistry.getConnectorStatusMap().put(tenant, new HashMap<>());
        }
    }

    private void registerDefaultConnectors() throws ConnectorRegistryException, ConnectorException {
        connectorRegistry.registerConnector(ConnectorType.MQTT, new MQTTClient().getConnectorSpecification());
        connectorRegistry.registerConnector(ConnectorType.CUMULOCITY_MQTT_SERVICE,
                new MQTTServiceClient().getConnectorSpecification());
        connectorRegistry.registerConnector(ConnectorType.KAFKA, new KafkaClient().getConnectorSpecification());
        connectorRegistry.registerConnector(ConnectorType.WEB_HOOK, new WebHook().getConnectorSpecification());

        HttpClient initialHttpClient = new HttpClient();
        connectorRegistry.registerConnector(ConnectorType.HTTP, initialHttpClient.getConnectorSpecification());
    }

    private void setupConnectorConfigurations(String tenant, ServiceConfiguration serviceConfig)
            throws ConnectorRegistryException, ConnectorException {
        List<ConnectorConfiguration> connectorConfigs = connectorConfigurationComponent
                .getConnectorConfigurations(tenant);

        ConnectorConfiguration httpConfig = null;

        for (ConnectorConfiguration config : connectorConfigs) {
            initializeConnectorByConfiguration(config, serviceConfig, tenant);
            if (ConnectorType.HTTP.equals(config.connectorType)) {
                httpConfig = config;
            }
        }

        if (httpConfig == null) {
            createAndInitializeDefaultHttpConnector(tenant, serviceConfig);
        }
    }

    private void createAndInitializeDefaultHttpConnector(String tenant, ServiceConfiguration serviceConfig)
            throws ConnectorRegistryException {
        ConnectorConfiguration httpConfig = new ConnectorConfiguration();
        httpConfig.connectorType = ConnectorType.HTTP;
        httpConfig.identifier = HttpClient.HTTP_CONNECTOR_IDENTIFIER;
        httpConfig.enabled = true;
        httpConfig.name = "Default Http Connector";

        HttpClient initialHttpClient = new HttpClient();
        initialHttpClient.getConnectorSpecification().getProperties()
                .forEach((key, prop) -> httpConfig.properties.put(key, prop.defaultValue.toString()));

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
        log.info("Tenant {} - OutputMapping Config Enabled: {}", tenant, serviceConfig.isOutboundMappingEnabled());

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
            serviceConfigurationComponent.saveServiceConfiguration(serviceConfig);
        } catch (JsonProcessingException e) {
            log.error("Tenant {} - Error saving service configuration: {}", tenant, e.getMessage());
        }
    }

    public AConnectorClient initializeConnectorByConfiguration(ConnectorConfiguration connectorConfiguration,
            ServiceConfiguration serviceConfiguration, String tenant)
            throws ConnectorRegistryException, ConnectorException {
        AConnectorClient connectorClient = null;
        if (connectorConfiguration.isEnabled()) {
            try {
                connectorClient = configurationRegistry.createConnectorClient(connectorConfiguration,
                        additionalSubscriptionIdTest, tenant);
            } catch (ConnectorException e) {
                log.error("Tenant {} - Error on creating connector {} {}", connectorConfiguration.getConnectorType(),
                        e);
                throw new ConnectorRegistryException(e.getMessage());
            }
            connectorRegistry.registerClient(tenant, connectorClient);
            // initialize AsynchronousDispatcherInbound
            DispatcherInbound dispatcherInbound = new DispatcherInbound(configurationRegistry,
                    connectorClient);
            configurationRegistry.initializePayloadProcessorsInbound(tenant);
            connectorClient.setDispatcher(dispatcherInbound);
            connectorClient.reconnect();
            connectorClient.submitHousekeeping();
            initializeOutboundMapping(tenant, serviceConfiguration, connectorClient);
        }
        return connectorClient;
    }

    public void initializeOutboundMapping(String tenant, ServiceConfiguration serviceConfiguration,
            AConnectorClient connectorClient) {
        if (serviceConfiguration.isOutboundMappingEnabled() && connectorClient.supportedDirections().contains(Direction.OUTBOUND)) {
            // initialize AsynchronousDispatcherOutbound
            configurationRegistry.initializePayloadProcessorsOutbound(connectorClient);
            DispatcherOutbound dispatcherOutbound = new DispatcherOutbound(
                    configurationRegistry, connectorClient);
            // Only initialize Connectors which are enabled
            if (connectorClient.getConnectorConfiguration().isEnabled())
                configurationRegistry.getNotificationSubscriber().addConnector(tenant,
                        connectorClient.getConnectorIdentifier(),
                        dispatcherOutbound);
            // Subscriber must be new initialized for the new added connector
            // configurationRegistry.getNotificationSubscriber().notificationSubscriberReconnect(tenant);

        }
    }

    @Scheduled(cron = "0 * * * * *")
    public void cleanUpCaches() {
        subscriptionsService.runForEachTenant(() -> {
            String tenant = subscriptionsService.getTenant();
            cleanupCacheForTenant(tenant);
        });
    }

    private void cleanupCacheForTenant(String tenant) {
        Instant cacheRetentionStart = cacheRetentionStartMap.get(tenant);
        if (cacheRetentionStart == null)
            return;

        ServiceConfiguration serviceConfig = serviceConfigurationComponent.getServiceConfiguration(tenant);
        int retentionDays = serviceConfig.getInboundExternalIdCacheRetention();

        if (shouldClearCache(cacheRetentionStart, retentionDays)) {
            int cacheSize = c8YAgent.getInboundExternalIdCache(tenant).getCacheSize();
            c8YAgent.clearInboundExternalIdCache(tenant, false, cacheSize);
            cacheRetentionStartMap.put(tenant, Instant.now());

            log.info("Tenant {} - Identity Cache cleared. Old Size: {}, New size: {}",
                    tenant, cacheSize, c8YAgent.getInboundExternalIdCache(tenant).getCacheSize());
        }
    }

    private boolean shouldClearCache(Instant cacheRetentionStart, int retentionDays) {
        return retentionDays > 0 &&
                Duration.between(cacheRetentionStart, Instant.now()).compareTo(Duration.ofDays(retentionDays)) >= 0;
    }
}
