/*
 * Copyright (c) 2025 Cumulocity GmbH.
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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;

import dynamic.mapper.configuration.ConnectorConfiguration;
import dynamic.mapper.configuration.ConnectorConfigurationService;
import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.configuration.ServiceConfigurationService;
import dynamic.mapper.connector.core.callback.GenericMessageCallback;
import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.connector.core.client.ConnectorException;
import dynamic.mapper.connector.core.client.ConnectorType;
import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.connector.core.registry.ConnectorRegistryException;
import dynamic.mapper.connector.http.HttpClient;
import dynamic.mapper.connector.test.TestClient;
import dynamic.mapper.mapping.MappingService;
import dynamic.mapper.model.Direction;
import dynamic.mapper.processor.outbound.OutboundMessageDispatcher;
import dynamic.mapper.processor.inbound.InboundMessageDispatcher;
import lombok.extern.slf4j.Slf4j;

/**
 * Creates, connects, disables, and deletes the connectors of a tenant, and starts the outbound
 * (Notification 2.0) resources that depend on them.
 * <p>
 * {@link BootstrapService} calls this service in the startup sequence of a tenant; the REST
 * controllers call it when a user creates, enables, disables, or deletes a connector.
 */
@Slf4j
@Service
public class ConnectorLifecycleService {

    private final ConnectorRegistry connectorRegistry;
    private final ConnectorClientFactory connectorClientFactory;
    private final ServiceRegistry serviceRegistry;
    private final ConnectorConfigurationService connectorConfigurationService;
    private final ServiceConfigurationService serviceConfigurationService;
    private final MappingService mappingService;
    private final String additionalSubscriptionIdTest;

    public ConnectorLifecycleService(
            ConnectorRegistry connectorRegistry,
            ConnectorClientFactory connectorClientFactory,
            ServiceRegistry serviceRegistry,
            ConnectorConfigurationService connectorConfigurationService,
            ServiceConfigurationService serviceConfigurationService,
            MappingService mappingService,
            @Value("${APP.additionalSubscriptionIdTest:}") String additionalSubscriptionIdTest) {
        this.connectorRegistry = connectorRegistry;
        this.connectorClientFactory = connectorClientFactory;
        this.serviceRegistry = serviceRegistry;
        this.connectorConfigurationService = connectorConfigurationService;
        this.serviceConfigurationService = serviceConfigurationService;
        this.mappingService = mappingService;
        this.additionalSubscriptionIdTest = additionalSubscriptionIdTest;
    }

    // === Tenant startup ===

    /**
     * Removes references to connectors that no longer exist: orphaned Notifications 2.0 subscribers
     * of an earlier run, and connector identifiers in the deployment map. Both are left behind when a
     * connector is deleted or disabled without going through the normal flow.
     */
    public void cleanupStaleConnectorReferences(String tenant) {
        List<ConnectorConfiguration> allConnectorConfigs = connectorConfigurationService
                .getConnectorConfigurations(tenant);
        serviceRegistry.getNotificationSubscriber()
                .cleanupOrphanedSubscribers(tenant, allConnectorConfigs, additionalSubscriptionIdTest);

        Set<String> validConnectorIds = allConnectorConfigs.stream()
                .map(ConnectorConfiguration::getIdentifier)
                .collect(Collectors.toSet());
        mappingService.cleanupStaleDeploymentConnectors(tenant, validConnectorIds);
    }

    /**
     * Registers the connector specifications and starts every configured connector of the tenant,
     * plus the default HTTP and test connectors when they do not exist yet.
     *
     * @return the pending connection of each started connector, or {@code null} if the setup failed
     */
    public List<Future<?>> initializeConnectors(String tenant, ServiceConfiguration serviceConfig) {
        try {
            connectorRegistry.registerConnectors();
            return setupConnectorConfigurations(tenant, serviceConfig);
        } catch (Exception e) {
            log.error("{} - Error initializing connectors: {}", tenant, e.getMessage(), e);
        }
        return null;
    }

    /** Waits until all given connections are established or have failed; failures are logged. */
    public void awaitConnections(String tenant, List<Future<?>> connectorTasks) {
        if (connectorTasks == null) {
            return;
        }
        connectorTasks.forEach(connectorTask -> {
            try {
                connectorTask.get();
            } catch (InterruptedException | ExecutionException e) {
                log.error("{} - Error initializing  connector: {}", tenant, e.getMessage(), e);
            }
        });
    }

    private List<Future<?>> setupConnectorConfigurations(String tenant, ServiceConfiguration serviceConfig)
            throws ConnectorRegistryException, ConnectorException {
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

    /**
     * Creates the client of an enabled connector, registers it, attaches the inbound dispatcher, and
     * starts the asynchronous connect, housekeeping, and outbound mapping.
     *
     * @return the pending connection, or {@code null} if the connector is disabled or its type is
     *         unavailable
     */
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
            GenericMessageCallback dispatcherInbound = new InboundMessageDispatcher(serviceRegistry,
                    connectorClient);
            connectorClient.setDispatcher(dispatcherInbound);
            // Connection is done async, future is returned to wait for the connection if
            // needed
            future = connectorClient.reconnect();
            connectorClient.submitHousekeeping();
            initializeOutboundMapping(tenant, serviceConfiguration, connectorClient);
        }
        return future;
    }

    /**
     * Registers the outbound dispatcher of a connector with Notification 2.0 when outbound mapping is
     * enabled and the connector supports the outbound direction.
     * <p>
     * The dispatcher is registered whatever the enabled state of the connector, so that a Notification
     * 2.0 WebSocket is established and the Message Explorer can capture outbound notifications even
     * while connectors are disabled. {@code processNotification()} guards the actual mapping
     * execution with {@code isConnected()}.
     */
    private void initializeOutboundMapping(String tenant, ServiceConfiguration serviceConfiguration,
            AConnectorClient connectorClient) {
        if (serviceConfiguration.getOutboundMappingEnabled()
                && connectorClient.supportedDirections().contains(Direction.OUTBOUND)) {
            OutboundMessageDispatcher dispatcherOutbound = new OutboundMessageDispatcher(serviceRegistry,
                    connectorClient);
            serviceRegistry.getNotificationSubscriber().addConnector(tenant,
                    connectorClient.getConnectorIdentifier(), dispatcherOutbound);
        }
    }

    // === Connector changes at runtime ===

    /**
     * Deletes connector-specific broker resources, such as a Pulsar subscription. Call this before
     * disconnecting the connector, while its client is still in the registry.
     */
    public void deleteConnectorResources(String tenant, String connectorIdentifier)
            throws ConnectorRegistryException {
        AConnectorClient client = connectorRegistry.getClientForTenant(tenant, connectorIdentifier);
        if (client == null) {
            log.warn("{} - Cannot delete resources for connector {}: client not found in registry (already disconnected)",
                    tenant, connectorIdentifier);
            return;
        }
        client.deleteResources();
    }

    /** Unsubscribes the Notification 2.0 subscriber of the connector, which drops all its queues. */
    public void shutdownAndRemoveConnector(String tenant, String connectorIdentifier)
            throws ConnectorRegistryException {
        if (isOutboundMappingEnabled(tenant)) {
            serviceRegistry.getNotificationSubscriber().unsubscribeDeviceSubscriberByConnector(tenant,
                    connectorIdentifier);
            serviceRegistry.getNotificationSubscriber().removeConnector(tenant, connectorIdentifier);
        }
    }

    /** Removes the client and disconnects its Notification 2.0 connection; the queues are kept. */
    public void disableConnector(String tenant, String connectorIdentifier) throws ConnectorRegistryException {
        connectorRegistry.unregisterClient(tenant, connectorIdentifier);
        if (isOutboundMappingEnabled(tenant)) {
            serviceRegistry.getNotificationSubscriber().removeConnector(tenant, connectorIdentifier);
        }
    }

    private boolean isOutboundMappingEnabled(String tenant) {
        ServiceConfiguration serviceConfiguration = serviceConfigurationService.getServiceConfiguration(tenant);
        return serviceConfiguration.getOutboundMappingEnabled();
    }

    // === Outbound resources ===

    /**
     * Connects Notification 2.0 and starts the token refresh when outbound mapping is enabled. If the
     * platform has no Notification 2.0 service, outbound mapping is switched off in the configuration.
     * Call this after the connectors are connected.
     */
    public void initializeOutboundResources(String tenant, ServiceConfiguration serviceConfig) {
        log.info("{} - Config mappingOutbound enabled: {}", tenant, serviceConfig.getOutboundMappingEnabled());

        if (!serviceConfig.getOutboundMappingEnabled()) {
            return;
        }

        if (!serviceRegistry.getNotificationSubscriber().isNotificationServiceAvailable(tenant)) {
            disableOutboundMapping(tenant, serviceConfig);
        } else {
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
}
