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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
import dynamic.mapper.connector.core.client.ConnectionTestResult;
import dynamic.mapper.connector.core.client.ConnectorException;
import dynamic.mapper.connector.core.client.ConnectorType;
import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.connector.core.registry.ConnectorRegistryException;
import dynamic.mapper.connector.core.ConnectorSpecification;
import dynamic.mapper.connector.http.HttpClient;
import dynamic.mapper.connector.test.TestClient;
import dynamic.mapper.mapping.deployment.DeploymentMapService;
import dynamic.mapper.notification.NotificationSubscriber;
import dynamic.mapper.notification.service.SubscriptionManager;
import dynamic.mapper.model.Direction;
import dynamic.mapper.processor.outbound.OutboundMessageDispatcher;
import dynamic.mapper.processor.inbound.InboundMessageDispatcher;
import dynamic.mapper.processor.inbound.InboundMessageRouter;
import dynamic.mapper.processor.outbound.OutboundMessageRouter;
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

    /** Why a connector operation was refused or failed. */
    public enum Reason {
        NOT_FOUND, NOT_ALLOWED, FAILED
    }

    /** A refused or failed connector operation; the REST layer maps the reason to an HTTP status. */
    public static class ConnectorOperationException extends RuntimeException {
        @lombok.Getter
        private final Reason reason;

        public ConnectorOperationException(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }

        public ConnectorOperationException(Reason reason, String message, Throwable cause) {
            super(message, cause);
            this.reason = reason;
        }
    }

    /** How long enabling a connector waits for a fast connection before reporting success. */
    private static final long CONNECT_WAIT_SECONDS = 10;

    private final ConnectorRegistry connectorRegistry;
    private final ConnectorClientFactory connectorClientFactory;
    private final ServiceRegistry serviceRegistry;
    private final ConnectorConfigurationService connectorConfigurationService;
    private final ServiceConfigurationService serviceConfigurationService;
    private final DeploymentMapService deploymentMapService;
    private final NotificationSubscriber notificationSubscriber;
    private final InboundMessageRouter inboundMessageRouter;
    private final OutboundMessageRouter outboundMessageRouter;
    private final String additionalSubscriptionIdTest;

    public ConnectorLifecycleService(
            ConnectorRegistry connectorRegistry,
            ConnectorClientFactory connectorClientFactory,
            ServiceRegistry serviceRegistry,
            ConnectorConfigurationService connectorConfigurationService,
            ServiceConfigurationService serviceConfigurationService,
            DeploymentMapService deploymentMapService,
            NotificationSubscriber notificationSubscriber,
            InboundMessageRouter inboundMessageRouter,
            OutboundMessageRouter outboundMessageRouter,
            @Value("${APP.additionalSubscriptionIdTest:}") String additionalSubscriptionIdTest) {
        this.connectorRegistry = connectorRegistry;
        this.connectorClientFactory = connectorClientFactory;
        this.serviceRegistry = serviceRegistry;
        this.connectorConfigurationService = connectorConfigurationService;
        this.serviceConfigurationService = serviceConfigurationService;
        this.deploymentMapService = deploymentMapService;
        this.notificationSubscriber = notificationSubscriber;
        this.inboundMessageRouter = inboundMessageRouter;
        this.outboundMessageRouter = outboundMessageRouter;
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
        notificationSubscriber
                .cleanupOrphanedSubscribers(tenant, allConnectorConfigs, additionalSubscriptionIdTest);

        Set<String> validConnectorIds = allConnectorConfigs.stream()
                .map(ConnectorConfiguration::getIdentifier)
                .collect(Collectors.toSet());
        deploymentMapService.cleanupStaleConnectors(tenant, validConnectorIds);
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
                    connectorClient, inboundMessageRouter);
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
                    connectorClient, outboundMessageRouter, notificationSubscriber);
            notificationSubscriber.addConnector(tenant,
                    connectorClient.getConnectorIdentifier(), dispatcherOutbound);
        }
    }

    // === Connector configuration (REST) ===

    /** Saves a new connector configuration. HTTP connectors are system-managed and cannot be created. */
    public void createConnectorConfiguration(String tenant, ConnectorConfiguration configuration) {
        if (ConnectorType.HTTP.equals(configuration.getConnectorType())) {
            throw new ConnectorOperationException(Reason.NOT_ALLOWED, "Can't create a HttpConnector!");
        }
        ConnectorSpecification spec = connectorRegistry.getConnectorSpecification(configuration.getConnectorType());
        // Remove sensitive data before printing to log
        log.info("{} - Post Connector configuration: {}", tenant, configuration.getCleanedConfig(spec));
        try {
            // A new configuration has no stored secret, so a masked value (e.g. from an import) is dropped, not saved.
            configuration.restoreMaskedSecrets(spec, null);
            dropInactiveProperties(tenant, spec, configuration);
            connectorConfigurationService.saveConnectorConfiguration(configuration);
        } catch (Exception ex) {
            log.error("{} - Error creating connector instance", tenant, ex);
            throw new ConnectorOperationException(Reason.FAILED, ex.getLocalizedMessage(), ex);
        }
    }

    /**
     * Saves a changed connector configuration. A secret sent as {@code ****} keeps the stored value.
     * A running connector is not reconnected; the new configuration applies on the next connect.
     */
    public ConnectorConfiguration updateConnectorConfiguration(String tenant, String identifier,
            ConnectorConfiguration configuration) {
        // make sure we are using the correct identifier
        configuration.setIdentifier(identifier);
        ConnectorSpecification spec = connectorRegistry.getConnectorSpecification(configuration.getConnectorType());
        // Remove sensitive data before printing to log
        log.info("{} - Post Connector configuration: {}", tenant, configuration.getCleanedConfig(spec));
        try {
            ConnectorConfiguration original = connectorConfigurationService.getConnectorConfiguration(identifier,
                    tenant);
            configuration.restoreMaskedSecrets(spec, original);
            dropInactiveProperties(tenant, spec, configuration);
            connectorConfigurationService.saveConnectorConfiguration(configuration);
        } catch (Exception ex) {
            log.error("{} - Error updating connector instance: {}", tenant, identifier, ex);
            throw new ConnectorOperationException(Reason.FAILED, ex.getLocalizedMessage(), ex);
        }
        return configuration;
    }

    /**
     * Removes the values of properties that do not apply to the configuration (their condition is not
     * met), so that a stale value the UI no longer shows cannot stay active in the connector.
     */
    private void dropInactiveProperties(String tenant, ConnectorSpecification spec,
            ConnectorConfiguration configuration) {
        if (spec == null || configuration.getProperties() == null) {
            return;
        }
        List<String> removed = spec.removeInactiveProperties(configuration.getProperties());
        if (!removed.isEmpty()) {
            log.info("{} - Dropped properties that do not apply to connector {}: {}", tenant,
                    configuration.getIdentifier(), removed);
        }
    }

    /**
     * Checks connectivity and authentication of a (possibly unsaved) configuration with a throwaway
     * client. Nothing is saved, registered, or subscribed. Failures are reported in the result, not thrown.
     * <p>
     * The probe flag of the client is thread-bound, so this must run on the calling thread.
     */
    public ConnectionTestResult testConnection(String tenant, ConnectorConfiguration configuration) {
        ConnectorSpecification spec = connectorRegistry.getConnectorSpecification(configuration.getConnectorType());
        if (spec == null) {
            throw new ConnectorOperationException(Reason.NOT_ALLOWED, "Unsupported connector type");
        }
        try {
            ConnectorConfiguration original = configuration.getIdentifier() == null ? null
                    : connectorConfigurationService.getConnectorConfiguration(configuration.getIdentifier(), tenant);
            configuration.restoreMaskedSecrets(spec, original);
            configuration.copyPredefinedValues(spec);
            AConnectorClient client = AConnectorClient
                    .constructForProbe(() -> connectorClientFactory.createConnectorClient(configuration, "", tenant));
            try {
                if (client == null || !client.supportsConnectionTest()) {
                    return ConnectionTestResult.failed(
                            "Connection test is not supported for this connector type", null);
                }
                // The registry's specification only has generic defaults; a client may predefine values of its own
                // (e.g. the MQTT Service URL and the tenant's credentials), exactly as loadConfiguration() applies them.
                configuration.copyPredefinedValues(client.getConnectorSpecification());
                log.info("{} - Testing connector configuration, type: {}", tenant, configuration.getConnectorType());
                return client.testConnection();
            } finally {
                if (client != null) {
                    client.discardProbe();
                }
            }
        } catch (Exception ex) {
            log.error("{} - Error testing connector configuration", tenant, ex);
            return ConnectionTestResult.failed("Test failed: " + ex.getMessage(), null);
        }
    }

    // === Enable, disable, delete ===

    /**
     * Enables a connector and connects it. Waits up to {@value #CONNECT_WAIT_SECONDS} seconds for a fast
     * connection; a connection that is still pending then continues in the background and counts as
     * success. Afterwards the Notification 2.0 subscriptions are reconnected if the connector supports
     * the outbound direction.
     */
    public void connect(String tenant, String connectorIdentifier) {
        try {
            ConnectorConfiguration configuration = connectorConfigurationService
                    .getConnectorConfiguration(connectorIdentifier, tenant);
            if (configuration == null) {
                throw new ConnectorOperationException(Reason.NOT_FOUND,
                        "No connector configuration found for identifier: " + connectorIdentifier);
            }
            ServiceConfiguration serviceConfiguration = serviceConfigurationService.getServiceConfiguration(tenant);

            // Must be set and persisted before triggering the connection, because
            // AConnectorClient.loadConfiguration() reloads from persistence inside submitConnect().
            // If we save after, loadConfiguration() reads enabled=false and shouldConnect() returns false.
            configuration.setEnabled(true);
            connectorConfigurationService.saveConnectorConfiguration(configuration);

            // If the client is already registered (e.g. DISCONNECTED after a failed startup),
            // reconnect it directly. initializeConnectorByConfiguration uses putIfAbsent, so the
            // old client would stay in the registry and the new one would be silently dropped,
            // leaving the registered client's status unchanged.
            AConnectorClient existingClient = connectorRegistry.getClientForTenant(tenant, connectorIdentifier);
            Future<?> connectTask;
            if (existingClient != null) {
                log.info("{} - Client {} already registered, reconnecting existing client", tenant,
                        configuration.getName());
                connectTask = existingClient.reconnect();
            } else {
                connectTask = initializeConnectorByConfiguration(configuration, serviceConfiguration, tenant);
            }

            if (connectTask != null) {
                try {
                    connectTask.get(CONNECT_WAIT_SECONDS, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    log.info("{} - Connector {} still connecting after {}s, returning success (async)", tenant,
                            configuration.getName(), CONNECT_WAIT_SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("{} - Interrupted while waiting for connector to connect: {}", tenant,
                            configuration.getName());
                } catch (Exception e) {
                    log.error("{} - Connector {} failed to connect: {}", tenant, configuration.getName(),
                            e.getMessage(), e);
                    throw new ConnectorOperationException(Reason.FAILED, e.getMessage(), e);
                }
            }

            // Reconnect outbound notification subscriptions after the connector is ready
            AConnectorClient client = connectorRegistry.getClientForTenant(tenant, connectorIdentifier);
            if (client != null && client.supportedDirections().contains(Direction.OUTBOUND)) {
                notificationSubscriber.notificationSubscriberReconnect(tenant);
            }
        } catch (ConnectorOperationException e) {
            throw e;
        } catch (Exception e) {
            throw new ConnectorOperationException(Reason.FAILED, e.getMessage(), e);
        }
    }

    /**
     * Disables a connector and disconnects it. A connector that is not running is only marked as
     * disabled. The Notification 2.0 subscriptions of the remaining connectors are reconnected; if that
     * fails the connector is still disconnected, but the failure is reported.
     */
    public void disconnect(String tenant, String connectorIdentifier) {
        try {
            ConnectorConfiguration configuration = connectorConfigurationService
                    .getConnectorConfiguration(connectorIdentifier, tenant);
            if (configuration == null) {
                throw new ConnectorOperationException(Reason.NOT_FOUND,
                        "No connector configuration found for identifier: " + connectorIdentifier);
            }

            AConnectorClient client = connectorRegistry.getClientForTenant(tenant, connectorIdentifier);
            if (client == null) {
                // Connector is not actively running — mark as disabled and return success
                log.info("{} - Connector {} is not active, marking as disabled", tenant, configuration.getName());
                configuration.setEnabled(false);
                connectorConfigurationService.saveConnectorConfiguration(configuration);
                return;
            }

            configuration.setEnabled(false);
            connectorConfigurationService.saveConnectorConfiguration(configuration);
            disableConnector(tenant, client.getConnectorIdentifier());
            // Reconnect other notification clients for remaining connectors
            boolean reconnected = notificationSubscriber.notificationSubscriberReconnect(tenant);
            if (!reconnected) {
                // Connector is disconnected and marked disabled locally, but reconnecting the
                // remaining notification subscriptions against Cumulocity failed (e.g. backend
                // temporarily unavailable) - surface this instead of silently reporting success.
                throw new ConnectorOperationException(Reason.FAILED,
                        "Connector " + (configuration.getName() != null ? configuration.getName() : connectorIdentifier)
                                + " was disconnected, but reconnecting remaining notification subscriptions failed");
            }
        } catch (ConnectorOperationException e) {
            throw e;
        } catch (Exception e) {
            throw new ConnectorOperationException(Reason.FAILED, e.getMessage(), e);
        }
    }

    /**
     * Deletes a connector: its broker resources, client, Notification 2.0 subscriptions, configuration,
     * deployment-map entries, and status. HTTP connectors are system-managed and cannot be deleted.
     */
    public void deleteConnector(String tenant, String identifier) {
        try {
            ConnectorConfiguration configuration = connectorConfigurationService.getConnectorConfiguration(identifier,
                    tenant);
            if (configuration == null) {
                // Defensive: nothing to delete. Avoids a NullPointerException (and a misleading 500)
                // when deleting an already-removed connector.
                log.info("{} - Connector {} not found, nothing to delete", tenant, identifier);
                throw new ConnectorOperationException(Reason.NOT_FOUND, "Connector not found: " + identifier);
            }
            if (ConnectorType.HTTP.equals(configuration.getConnectorType())) {
                throw new ConnectorOperationException(Reason.NOT_ALLOWED, "Can't delete a HttpConnector!");
            }

            boolean needsReconnect = !configuration.getEnabled()
                    && ConnectorType.CUMULOCITY_MQTT_SERVICE_PULSAR.equals(configuration.getConnectorType());

            // The teardown below MUST always run — even if the (optional) temporary
            // re-enable + reconnect or the resource cleanup fails. Otherwise a failed
            // delete of a disabled Pulsar connector would leave it persisted with
            // enabled=true (resurrected and reconnected) instead of being removed.
            try {
                // For Pulsar connectors that are disabled, temporarily enable them so the
                // broker subscription can be removed. This briefly persists enabled=true;
                // the finally block guarantees the configuration is still deleted.
                if (needsReconnect) {
                    log.info("{} - Temporarily enabling connector {} to delete Pulsar subscription", tenant,
                            identifier);
                    configuration.setEnabled(true);
                    connectorConfigurationService.saveConnectorConfiguration(configuration);

                    ServiceConfiguration serviceConfiguration = serviceConfigurationService
                            .getServiceConfiguration(tenant);
                    Future<?> connectTask = initializeConnectorByConfiguration(configuration, serviceConfiguration,
                            tenant);

                    // Wait for connector to connect (with timeout)
                    try {
                        connectTask.get(CONNECT_WAIT_SECONDS, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        log.warn("{} - Could not reconnect connector {} for subscription cleanup: {}", tenant,
                                identifier, e.getMessage());
                        // Continue anyway - deleteResources() has fallback logic
                    }
                }

                // Delete connector-specific resources (e.g., Pulsar subscriptions)
                deleteConnectorResources(tenant, identifier);
            } finally {
                // Best-effort teardown: one failing step must not skip the others or
                // leave the connector configuration behind.
                try {
                    disableConnector(tenant, identifier);
                } catch (Exception e) {
                    log.warn("{} - Error disabling connector {} during delete: {}", tenant, identifier,
                            e.getMessage());
                }
                try {
                    // Clean up Notification 2.0 subscriptions
                    shutdownAndRemoveConnector(tenant, identifier);
                } catch (Exception e) {
                    log.warn("{} - Error shutting down connector {} during delete: {}", tenant, identifier,
                            e.getMessage());
                }
                connectorConfigurationService.deleteConnectorConfiguration(identifier);
                try {
                    deploymentMapService.removeConnectorFromAllMappings(tenant, identifier);
                } catch (Exception e) {
                    log.warn("{} - Error removing connector {} from deployment map during delete: {}", tenant,
                            identifier, e.getMessage());
                }
                connectorRegistry.removeClientFromStatusMap(tenant, identifier);
            }
        } catch (ConnectorOperationException e) {
            throw e;
        } catch (Exception ex) {
            log.error("{} - Error deleting connector instance: {}", tenant, identifier, ex);
            throw new ConnectorOperationException(Reason.FAILED, ex.getLocalizedMessage(), ex);
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
            notificationSubscriber.unsubscribeDeviceSubscriberByConnector(tenant,
                    connectorIdentifier);
            notificationSubscriber.removeConnector(tenant, connectorIdentifier);
        }
    }

    /** Removes the client and disconnects its Notification 2.0 connection; the queues are kept. */
    public void disableConnector(String tenant, String connectorIdentifier) throws ConnectorRegistryException {
        connectorRegistry.unregisterClient(tenant, connectorIdentifier);
        if (isOutboundMappingEnabled(tenant)) {
            notificationSubscriber.removeConnector(tenant, connectorIdentifier);
        }
    }

    private boolean isOutboundMappingEnabled(String tenant) {
        ServiceConfiguration serviceConfiguration = serviceConfigurationService.getServiceConfiguration(tenant);
        return serviceConfiguration.getOutboundMappingEnabled();
    }

    // === Service configuration ===

    /**
     * Applies a partial update to the service configuration and reacts to a change of the outbound
     * mapping switch.
     * <p>
     * Switching outbound mapping off disconnects Notification 2.0 when it is connected. Switching it on
     * (while Notification 2.0 is not connected) initializes every connector again and opens the device
     * and management clients. The new configuration is published to the {@link ServiceRegistry} last.
     */
    public void updateServiceConfiguration(String tenant, java.util.Map<String, Object> updates) {
        ServiceConfiguration previous = serviceRegistry.getServiceConfiguration(tenant);
        try {
            ServiceConfiguration updated = serviceConfigurationService.applyPartialUpdate(tenant, updates);
            applyOutboundMappingChange(tenant, previous, updated);
            serviceRegistry.addServiceConfiguration(tenant, updated);
        } catch (Exception ex) {
            log.error("{} - Error updating service configuration", tenant, ex);
            throw new ConnectorOperationException(Reason.FAILED, ex.getLocalizedMessage(), ex);
        }
    }

    private void applyOutboundMappingChange(String tenant, ServiceConfiguration previous,
            ServiceConfiguration updated) throws ConnectorRegistryException, ConnectorException {
        NotificationSubscriber notifications = notificationSubscriber;
        Integer status = notifications.getDeviceConnectionStatus(tenant);
        boolean connected = status != null && status == 200;

        if (!updated.getOutboundMappingEnabled() && connected) {
            notifications.disconnect(tenant);
        } else if (!connected && updated.getOutboundMappingEnabled() && !previous.getOutboundMappingEnabled()) {
            for (ConnectorConfiguration connectorConfiguration : connectorConfigurationService
                    .getConnectorConfigurations(tenant)) {
                initializeConnectorByConfiguration(connectorConfiguration, updated, tenant);
            }
            notifications.initializeDeviceClient(tenant);
            notifications.initializeManagementClient(tenant);
        }
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

        if (notificationSubscriber.checkNotificationServiceAvailability(tenant)
                == SubscriptionManager.ServiceAvailability.UNAVAILABLE) {
            disableOutboundMapping(tenant, serviceConfig);
        } else {
            // AVAILABLE, or UNKNOWN after a transient probe failure: never persist a disabled
            // configuration because of a blip. The reconnect has its own failure handling.
            notificationSubscriber.notificationSubscriberReconnect(tenant);
            notificationSubscriber.startTokenRefresh(tenant);
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
