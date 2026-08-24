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

package dynamic.mapper.connector.core.client;

import static java.util.Map.entry;

import java.security.KeyStore;
import java.security.cert.X509Certificate;

import dynamic.mapper.configuration.ConnectorConfiguration;
import dynamic.mapper.configuration.ConnectorId;
import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.ConnectorSpecification;
import dynamic.mapper.connector.core.callback.GenericMessageCallback;
import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.model.API;
import dynamic.mapper.model.ConnectorStatus;
import dynamic.mapper.model.ConnectorStatusEvent;
import dynamic.mapper.model.ConnectorStatusHistory;
import dynamic.mapper.model.DeploymentMapEntry;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.LoggingEventType;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.Qos;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.service.ConnectorConfigurationService;
import dynamic.mapper.service.MappingService;
import dynamic.mapper.service.ServiceConfigurationService;
import dynamic.mapper.util.CumulocityErrors;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.apache.commons.lang3.mutable.MutableInt;
import org.joda.time.DateTime;

import com.cumulocity.model.idtype.GId;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Base class for connector clients.
 * Simplified with extracted managers for subscriptions and connection state.
 */
@Slf4j
public abstract class AConnectorClient {

    // Constants
    protected static final int HOUSEKEEPING_INTERVAL_SECONDS = 30;
    protected static final int WAIT_PERIOD_MS = 10000;
    protected static final int CONNECTION_TIMEOUT_SECONDS = 30;

    private static final long SUBSCRIPTION_INIT_RETRY_INITIAL_DELAY_SECONDS = 10L;
    private static final long SUBSCRIPTION_INIT_RETRY_MAX_DELAY_SECONDS = 300L;

    public static final String MQTT_PROTOCOL_MQTT = "mqtt://";
    public static final String MQTT_PROTOCOL_MQTTS = "mqtts://";
    public static final String MQTT_PROTOCOL_WS = "ws://";
    public static final String MQTT_PROTOCOL_WSS = "wss://";
    public static final String MQTT_VERSION_3_1_1 = "3.1.1";
    public static final String MQTT_VERSION_5_0 = "5.0";

    // Identity
    @Getter
    protected String connectorIdentifier;
    @Getter
    protected String additionalSubscriptionIdTest;
    @Getter
    protected String connectorName;
    @Getter
    protected ConnectorId connectorId;
    @Getter
    @Setter
    protected String tenant;
    @Getter
    @Setter
    protected ConnectorType connectorType;
    @Getter
    @Setter
    protected boolean singleton;

    // Configuration
    @Getter
    @Setter
    protected ConnectorConfiguration connectorConfiguration;
    @Getter
    @Setter
    protected ConnectorSpecification connectorSpecification;
    @Getter
    @Setter
    protected ServiceConfiguration serviceConfiguration;
    @Getter
    @Setter
    protected Certificate cert;

    // Dependencies
    @Getter
    protected ConfigurationRegistry configurationRegistry;
    @Getter
    protected ConnectorRegistry connectorRegistry;
    @Getter
    protected ExecutorService virtualThreadPool;
    @Getter
    protected MappingService mappingService;
    @Getter
    protected ServiceConfigurationService serviceConfigurationService;
    @Getter
    protected ConnectorConfigurationService connectorConfigurationService;
    @Getter
    protected C8YAgent c8yAgent;
    @Getter
    @Setter
    protected GenericMessageCallback dispatcher;

    // Explorer listeners: notified for every raw message (topic-independent), one registry per direction.
    // Constructed in initializeManagers() (not as a field initializer) since `tenant` is only assigned
    // by the subclass constructor body, which runs after field initializers.
    private ExplorerListenerRegistry explorerListeners;
    private ExplorerListenerRegistry outboundExplorerListeners;

    /** Register a listener that receives every raw inbound {@link dynamic.mapper.connector.core.callback.ConnectorMessage}. */
    public void addExplorerListener(java.util.function.Consumer<dynamic.mapper.connector.core.callback.ConnectorMessage> listener) {
        explorerListeners.add(listener);
    }

    /** Remove a previously registered explorer listener. */
    public void removeExplorerListener(java.util.function.Consumer<dynamic.mapper.connector.core.callback.ConnectorMessage> listener) {
        explorerListeners.remove(listener);
    }

    /** Notify all registered explorer listeners (called by the inbound dispatcher). */
    public void notifyExplorerListeners(dynamic.mapper.connector.core.callback.ConnectorMessage message) {
        explorerListeners.notifyListeners(message);
    }

    /** Register a listener that receives every outbound {@link dynamic.mapper.connector.core.callback.ConnectorMessage}. */
    public void addOutboundExplorerListener(java.util.function.Consumer<dynamic.mapper.connector.core.callback.ConnectorMessage> listener) {
        outboundExplorerListeners.add(listener);
    }

    /** Remove a previously registered outbound explorer listener. */
    public void removeOutboundExplorerListener(java.util.function.Consumer<dynamic.mapper.connector.core.callback.ConnectorMessage> listener) {
        outboundExplorerListeners.remove(listener);
    }

    /** Notify all registered outbound explorer listeners (called by SendOutboundProcessor). */
    public void notifyOutboundExplorerListeners(dynamic.mapper.connector.core.callback.ConnectorMessage message) {
        outboundExplorerListeners.notifyListeners(message);
    }

    /**
     * Subscribe to a topic on the broker on behalf of an explorer session.
     * Only subscribes if no existing mapping or other explorer is already subscribed (best-effort).
     * Swallows errors since not all connector types require explicit subscriptions.
     */
    public void subscribeExplorerTopic(String topic) {
        try {
            boolean alreadySubscribed = mappingSubscriptionManager != null
                    && mappingSubscriptionManager.isTopicSubscribed(topic);
            if (alreadySubscribed) {
                log.debug("{} - Explorer topic [{}] already subscribed via mapping — skipping duplicate subscribe", tenant, topic);
                return;
            }
            subscribe(topic, Qos.AT_LEAST_ONCE);
            log.info("{} - Explorer subscribed to topic: [{}] on connector: {}", tenant, topic, connectorName);
        } catch (Exception e) {
            // Some connectors (HTTP, WebHook) don't support topic subscriptions — that's fine
            log.debug("{} - Explorer topic subscription not applicable for connector {}: {}", tenant, connectorName, e.getMessage());
        }
    }

    /**
     * Unsubscribe a topic that was subscribed by an explorer session.
     * Only unsubscribes if there are no active mappings still using this topic.
     */
    public void unsubscribeExplorerTopic(String topic) {
        try {
            // Only unsubscribe if no active mapping covers this topic
            boolean mappingStillActive = mappingSubscriptionManager != null
                    && mappingSubscriptionManager.isTopicSubscribed(topic);
            if (!mappingStillActive) {
                unsubscribe(topic);
                log.info("{} - Explorer unsubscribed from topic: [{}] on connector: {}", tenant, topic, connectorName);
            } else {
                log.debug("{} - Kept broker subscription for topic [{}] — still used by a mapping", tenant, topic);
            }
        } catch (Exception e) {
            log.debug("{} - Explorer topic unsubscription not applicable for connector {}: {}", tenant, connectorName, e.getMessage());
        }
    }

    // Managers
    protected MappingSubscriptionManager mappingSubscriptionManager;
    @Getter
    protected ConnectionStateManager connectionStateManager;

    // Synchronization primitives for connection management
    protected final Object connectionLock = new Object();
    protected final Object disconnectionLock = new Object();
    protected volatile boolean isConnecting = false;
    protected volatile boolean isDisconnecting = false;
    protected volatile boolean intentionalDisconnect = false;

    // Lifecycle tasks. Access is guarded by lifecycleLock: submitInitialize()/submitConnect()/
    // submitDisconnect()/reconnect() are each invoked from multiple, unsynchronized call sites
    // (scheduled health checks, user-triggered operations, connection-lost callbacks) and must
    // not race on the "check if done, else create" logic below.
    private final Object lifecycleLock = new Object();
    private volatile CompletableFuture<Void> initializeTask;
    private volatile CompletableFuture<Void> connectTask;
    private volatile CompletableFuture<Void> disconnectTask;
    private volatile CompletableFuture<Void> reconnectTask;
    private ScheduledThreadPoolExecutor housekeepingExecutor;

    // Ensures connect() and disconnect() bodies never run concurrently with each other for a
    // given connector instance. lifecycleLock (above) only dedupes overlapping submitConnect() vs
    // submitConnect() (or submitDisconnect() vs submitDisconnect()) calls, but does nothing about
    // a connectTask and a disconnectTask running at the same time on separate virtual threads.
    // beginConnection()/beginDisconnection() don't close this gap either — they synchronize on
    // two DIFFERENT locks (connectionLock vs disconnectionLock), so they never exclude each other,
    // and three connectors (HttpClient, WebHook, TestClient) don't call them at all. A
    // ReentrantLock (not `synchronized`) is used because it's held across the full connect()/
    // disconnect() call — including blocking network I/O — and `synchronized` would pin the
    // virtual thread's carrier thread for that entire duration.
    private final ReentrantLock connectDisconnectExecutionLock = new ReentrantLock();
    // Guards against scheduling overlapping retry chains for initializeSubscriptionsAfterConnect()
    private final AtomicBoolean subscriptionInitRetryScheduled = new AtomicBoolean(false);
    // When the current subscription-init retry chain started, so the success log can report
    // how long the connector spent in RETRYING before recovering.
    private volatile long subscriptionInitRetryStartedAtMs;

    // Abstract methods to be implemented by subclasses
    public abstract boolean initialize();

    public abstract void connect();

    public abstract void disconnect();

    /**
     * Release the connector. Default implementation delegates to {@link #disconnect()}.
     * Subclasses that own additional resources (e.g. channel references, thread pools)
     * should override this method, call {@code super.close()} or {@code disconnect()} first,
     * then release those resources.
     */
    public void close() {
        connectDisconnectExecutionLock.lock();
        try {
            disconnect();
        } finally {
            connectDisconnectExecutionLock.unlock();
        }
    }

    public abstract boolean isConfigValid(ConnectorConfiguration configuration);

    public abstract void publishMEAO(ProcessingContext<?> context);

    /**
     * Returns whether this connector can publish requests of the given API type.
     * Broker connectors (MQTT, Kafka, AMQP, …) return {@code false} for {@link API#CUSTOM}
     * because CUSTOM requests target Cumulocity REST microservice endpoints, not broker topics.
     * Those requests are handled by {@code C8YAgent.createMEAO} instead.
     * Default: {@code true} (all APIs supported).
     */
    public boolean supportsRequestAPI(API api) {
        return true;
    }

    public abstract Boolean supportsWildcardInTopic(Direction direction);

    public abstract List<Direction> supportedDirections();

    /**
     * Monitor subscriptions for health and connectivity
     * This method is specifically for Kafka, since it does not have the concept of
     * a client. Kafka rather supports consumer on topic level. They can fail to
     * connect.
     * Default implementation is a no-op. Subclasses should override if needed.
     */
    public void monitorSubscriptions() {
        // Default no-op implementation
        // Subclasses like KafkaClientV2 can override to provide specific monitoring
    }

    // Helper
    @Getter
    protected ObjectMapper objectMapper;

    // Existing fields
    protected SSLContext sslContext;

    // SSL/certificate handling is delegated to sslSupport (see ConnectorSslSupport); these
    // constants are kept here since AMQTTClient references them as inherited protected constants.
    protected static final List<String> DEFAULT_TLS_PROTOCOLS = ConnectorSslSupport.DEFAULT_TLS_PROTOCOLS;
    protected static final String CACERTS_PASSWORD = ConnectorSslSupport.CACERTS_PASSWORD;

    @Getter
    protected ConnectorSslSupport sslSupport;

    /**
     * Begin connection operation - returns true if connection should proceed
     * Subclasses should call this at the start of their connect() method
     */
    protected boolean beginConnection() {
        synchronized (connectionLock) {
            if (isConnecting) {
                log.debug("{} - Connection already in progress", tenant);
                return false;
            }
            isConnecting = true;
            intentionalDisconnect = false;
            return true;
        }
    }

    /**
     * End connection operation
     * Subclasses should call this at the end of their connect() method
     */
    protected void endConnection() {
        log.debug("{} - Calling end connection...", tenant);
        synchronized (connectionLock) {
            log.debug("{} - Setting isConnecting to false...", tenant);
            isConnecting = false;
        }
    }

    /**
     * Begin disconnection operation - returns true if disconnection should proceed
     * Subclasses should call this at the start of their disconnect() method
     */
    protected boolean beginDisconnection() {
        synchronized (disconnectionLock) {
            if (isDisconnecting) {
                log.debug("{} - Disconnection already in progress", tenant);
                return false;
            }
            isDisconnecting = true;
            intentionalDisconnect = true;
            return true;
        }
    }

    /**
     * End disconnection operation
     * Subclasses should call this at the end of their disconnect() method
     */
    protected void endDisconnection() {
        log.debug("{} - Calling end disconnection...", tenant);
        synchronized (disconnectionLock) {
            log.debug("{} - Setting isDisconnecting to false...", tenant);
            isDisconnecting = false;
        }
    }

    /**
     * Functional interface for operations that can throw exceptions
     */
    @FunctionalInterface
    protected interface SupplierWithException<T> {
        T get() throws Exception;
    }

    /**
     * Retry operation with exponential backoff
     * @param operationName name of the operation for logging
     * @param maxAttempts maximum number of attempts
     * @param baseDelayMs base delay in milliseconds (will be doubled on each retry)
     * @param operation the operation to execute
     * @return result of the operation
     * @throws ConnectorException if all retries fail
     */
    protected <T> T retryOperation(String operationName, int maxAttempts, long baseDelayMs,
            SupplierWithException<T> operation) throws ConnectorException {
        Exception lastException = null;
        long delay = baseDelayMs;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                log.debug("{} - Attempting {} (attempt {}/{})", tenant, operationName, attempt, maxAttempts);
                T result = operation.get();
                if (attempt > 1) {
                    log.info("{} - {} succeeded on attempt {}", tenant, operationName, attempt);
                }
                return result;
            } catch (Exception e) {
                lastException = e;
                if (attempt < maxAttempts) {
                    log.warn("{} - {} failed on attempt {}/{}: {}. Retrying in {}ms",
                            tenant, operationName, attempt, maxAttempts, e.getMessage(), delay);
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new ConnectorException("Retry interrupted", ie);
                    }
                    delay *= 2; // Exponential backoff
                } else {
                    log.error("{} - {} failed after {} attempts", tenant, operationName, maxAttempts, e);
                }
            }
        }

        throw new ConnectorException(
                String.format("%s failed after %d attempts", operationName, maxAttempts),
                lastException);
    }

    /**
     * Initialize SSL configuration if needed
     * Checks configuration for SSL requirements and sets up SSL context and sslConfig
     * @return true if SSL was initialized or not needed, false if SSL is required but failed
     * @throws Exception if SSL initialization fails
     */
    protected boolean initializeSslIfNeeded() throws Exception {
        // Check if SSL is required (protocol uses SSL/TLS)
        boolean sslRequired = isSslRequired();

        if (!sslRequired) {
            log.debug("{} - SSL not required for this connection", tenant);
            return true;
        }

        ConnectorSslSupport.SslInitResult result = sslSupport.initializeSsl(connectorConfiguration);
        this.sslContext = result.sslContext();
        if (result.cert() != null) {
            this.cert = result.cert();
        }
        return true;
    }

    /**
     * Check if SSL is required based on configuration
     * Subclasses can override this method for protocol-specific logic
     * @return true if SSL/TLS is required
     */
    protected boolean isSslRequired() {
        // Default implementation checks for common SSL indicators
        Object urlProperty = connectorConfiguration.getProperties().get("url");
        if (urlProperty instanceof String) {
            String url = (String) urlProperty;
            return url.startsWith("mqtts://") ||
                   url.startsWith("wss://") ||
                   url.startsWith("ssl://") ||
                   url.startsWith("https://") ||
                   url.startsWith("amqps://");
        }
        return false;
    }

    /**
     * Initialize managers
     */
    protected void initializeManagers() {
        this.explorerListeners = new ExplorerListenerRegistry(tenant, "inbound");
        this.outboundExplorerListeners = new ExplorerListenerRegistry(tenant, "outbound");
        this.sslSupport = new ConnectorSslSupport(tenant, connectorName, c8yAgent);

        this.mappingSubscriptionManager = new MappingSubscriptionManager(
                tenant,
                connectorName,
                new MappingSubscriptionManager.SubscriptionCallback() {
                    @Override
                    public void subscribe(String topic, Qos qos) throws ConnectorException {
                        AConnectorClient.this.subscribe(topic, qos);
                    }

                    @Override
                    public void unsubscribe(String topic) throws ConnectorException {
                        AConnectorClient.this.unsubscribe(topic);
                    }
                });

        this.connectionStateManager = new ConnectionStateManager(
                tenant,
                connectorName,
                connectorIdentifier,
                this::sendConnectorLifecycle, connectorRegistry);

        this.housekeepingExecutor = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "housekeeping-" + connectorIdentifier);
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Submit initialization task
     */
    public CompletableFuture<Void> submitInitialize() {
        synchronized (lifecycleLock) {
            if (initializeTask == null || initializeTask.isDone()) {
                log.debug("{} - Initializing connector: {}", tenant, connectorName);
                initializeTask = CompletableFuture
                        .runAsync(() -> {
                            try {
                                boolean success = initialize();
                                if (!success) {
                                    throw new ConnectorException("Initialization failed");
                                }
                                // Set status to CONFIGURED after successful initialization
                                if (isConfigValid(connectorConfiguration)) {
                                    connectionStateManager.updateStatus(ConnectorStatus.CONFIGURED, true, true);
                                }
                                log.debug("{} - Connector initialized successfully", tenant);
                            } catch (Exception e) {
                                log.error("{} - Initialization failed: {}", tenant, e.getMessage(), e);
                                connectionStateManager.updateStatusWithError(e);
                                throw new CompletionException("Initialization failed for connector " + connectorName, e);
                            }
                        }, virtualThreadPool);
            }
            return initializeTask;
        }
    }

    /**
     * Submit connection task
     */
    public CompletableFuture<Void> submitConnect() {
        loadConfiguration();

        synchronized (lifecycleLock) {
            if (connectTask == null || connectTask.isDone()) {
                log.debug("{} - Connecting connector: {}", tenant, connectorName);
                connectTask = CompletableFuture
                        .runAsync(() -> {
                            connectDisconnectExecutionLock.lock();
                            try {
                                connectionStateManager.updateStatus(ConnectorStatus.CONNECTING, true, true);
                                connect();
                                log.debug("{} - Connector connected successfully", tenant);
                            } catch (Exception e) {
                                log.error("{} - Connection failed: {}", tenant, e.getMessage(), e);
                                connectionStateManager.updateStatusWithError(e);
                                throw new CompletionException("Connection failed for connector " + connectorName, e);
                            } finally {
                                connectDisconnectExecutionLock.unlock();
                            }
                        }, virtualThreadPool);
            }
            return connectTask;
        }
    }

    /**
     * Submit disconnection task
     */
    public CompletableFuture<Void> submitDisconnect() {
        synchronized (lifecycleLock) {
            // Cancel ongoing connect task
            if (connectTask != null && !connectTask.isDone()) {
                log.debug("{} - Cancelling ongoing connection", tenant);
                connectTask.cancel(true);
            }

            if (disconnectTask == null || disconnectTask.isDone()) {
                log.debug("{} - Disconnecting connector: {}", tenant, connectorName);
                // Set DISCONNECTING synchronously before the async task runs.
                // This prevents a race where stopHousekeepingAndClose() → close() → disconnect()
                // completes synchronously (setting DISCONNECTED) before the async task starts,
                // causing the async task's updateStatus(DISCONNECTING) to overwrite DISCONNECTED.
                connectionStateManager.updateStatus(ConnectorStatus.DISCONNECTING, true, true);
                disconnectTask = CompletableFuture
                        .runAsync(() -> {
                            connectDisconnectExecutionLock.lock();
                            try {
                                disconnect();
                                connectionStateManager.setConnected(false);
                                log.debug("{} - Connector disconnected successfully", tenant);
                            } catch (Exception e) {
                                log.error("{} - Disconnection failed: {}", tenant, e.getMessage(), e);
                                connectionStateManager.updateStatusWithError(e);
                                throw new CompletionException("Disconnection failed for connector " + connectorName, e);
                            } finally {
                                connectDisconnectExecutionLock.unlock();
                            }
                        }, virtualThreadPool);
            }
            return disconnectTask;
        }
    }

    /**
     * Start housekeeping tasks.
     * Idempotent: no-op if already scheduled (executor has pending tasks).
     */
    public void submitHousekeeping() {
        if (!housekeepingExecutor.isShutdown() && housekeepingExecutor.getQueue().isEmpty()) {
            log.debug("{} - Starting housekeeping for connector: {}", tenant, connectorName);
            housekeepingExecutor.scheduleAtFixedRate(
                    this::runHousekeeping,
                    HOUSEKEEPING_INTERVAL_SECONDS,
                    HOUSEKEEPING_INTERVAL_SECONDS,
                    TimeUnit.SECONDS);
        } else {
            log.debug("{} - Housekeeping already scheduled, skipping", tenant);
        }
    }

    /**
     * Run housekeeping tasks
     */
    private void runHousekeeping() {
        try {
            performHousekeeping();
        } catch (Exception e) {
            log.error("{} - Error during housekeeping: {}", tenant, e.getMessage(), e);
        }
    }

    /**
     * Pre-builds mapping caches and pre-populates the effective mapping registry WITHOUT
     * performing any broker subscribe calls.
     * <p>
     * Called by {@link dynamic.mapper.connector.mqtt.AMQTTClient#connect()} when
     * {@code cleanSession=false} so that mapping resolution is ready before the TCP
     * connection is established.  When the broker immediately delivers queued messages
     * upon reconnect, the {@link dynamic.mapper.service.MappingService} can resolve them
     * to their mappings even before {@link #initializeSubscriptionsAfterConnect()} runs.
     */
    public void prepareForPersistentSessionReconnect() {
        try {
            log.debug("{} - Pre-building mapping caches for persistent session reconnect on connector: {}",
                    tenant, connectorName);
            mappingService.rebuildMappingCaches(tenant, connectorId);

            List<Mapping> inboundMappings = new ArrayList<>(
                    mappingService.getCacheInboundMappings(tenant).values());
            List<Mapping> deployedMappings = inboundMappings.stream()
                    .filter(this::isDeployedInConnector)
                    .toList();
            mappingSubscriptionManager.prePopulateEffectiveMappingsInbound(
                    deployedMappings, this::isMappingCompatibleWithConnector);

            log.debug("{} - Pre-populated {} effective inbound mappings for persistent session on connector: {}",
                    tenant, deployedMappings.size(), connectorName);
        } catch (Exception e) {
            log.warn("{} - Error pre-building caches for persistent session (will retry after connect): {}",
                    tenant, e.getMessage(), e);
        }
    }

    public void initializeSubscriptionsAfterConnect() {
        try {
            doInitializeSubscriptionsAfterConnect();
        } catch (Exception e) {
            if (isRetryableConnectorError(e)) {
                log.warn("{} - Transient error initializing subscriptions for connector {}, will retry in {}s: {}",
                        tenant, connectorName, SUBSCRIPTION_INIT_RETRY_INITIAL_DELAY_SECONDS, e.getMessage());
                scheduleSubscriptionInitRetry(SUBSCRIPTION_INIT_RETRY_INITIAL_DELAY_SECONDS, e);
            } else {
                throw e;
            }
        }
    }

    private void doInitializeSubscriptionsAfterConnect() {
        // Rebuild caches
        mappingService.rebuildMappingCaches(tenant, connectorId);
        List<Mapping> outboundMappings = new ArrayList<>(
                mappingService.getCacheOutboundMappings(tenant).values());
        List<Mapping> inboundMappings = new ArrayList<>(
                mappingService.getCacheInboundMappings(tenant).values());

        // Initialize subscriptions
        initializeSubscriptionsInbound(inboundMappings, true);
        initializeSubscriptionsOutbound(outboundMappings);

        log.info("{} - Initialized {} inbound and {} outbound mappings",
                tenant, inboundMappings.size(), outboundMappings.size());
    }

    /**
     * True if the exception (or one of its causes) is a platform-side error known to be
     * transient (e.g. a 502/503/504 during a platform rollout), rather than a permanent
     * configuration/permission problem that retrying won't fix.
     */
    private boolean isRetryableConnectorError(Throwable t) {
        return CumulocityErrors.isTransientPlatformError(t);
    }

    /**
     * Schedules a retry of {@link #doInitializeSubscriptionsAfterConnect()} on the housekeeping
     * executor after {@code delaySeconds}, marking the connector as {@link ConnectorStatus#RETRYING}.
     * The retry chain doubles its delay (capped at {@link #SUBSCRIPTION_INIT_RETRY_MAX_DELAY_SECONDS})
     * on each further failure and keeps going until it succeeds or a non-retryable error occurs.
     * Pending retries are cancelled automatically when the connector disconnects, since they run
     * on {@code housekeepingExecutor}, which is shut down in {@link #stopHousekeepingAndClose()}.
     */
    private void scheduleSubscriptionInitRetry(long delaySeconds, Exception lastError) {
        if (!subscriptionInitRetryScheduled.compareAndSet(false, true)) {
            // A retry chain is already in flight for this connector; let it continue.
            return;
        }
        subscriptionInitRetryStartedAtMs = System.currentTimeMillis();
        connectionStateManager.updateStatusRetrying(lastError, delaySeconds);
        if (housekeepingExecutor == null || housekeepingExecutor.isShutdown()) {
            subscriptionInitRetryScheduled.set(false);
            return;
        }
        housekeepingExecutor.schedule(() -> runSubscriptionInitRetry(delaySeconds), delaySeconds, TimeUnit.SECONDS);
    }

    private void runSubscriptionInitRetry(long previousDelaySeconds) {
        if (!isConnected()) {
            log.debug("{} - Connector {} no longer connected, abandoning subscription-init retry",
                    tenant, connectorName);
            subscriptionInitRetryScheduled.set(false);
            return;
        }
        try {
            doInitializeSubscriptionsAfterConnect();
            long elapsedSeconds = (System.currentTimeMillis() - subscriptionInitRetryStartedAtMs) / 1000;
            log.info("{} - Subscription initialization succeeded after retry for connector {} (after {}s)",
                    tenant, connectorName, elapsedSeconds);
            subscriptionInitRetryScheduled.set(false);
            connectionStateManager.updateStatus(ConnectorStatus.CONNECTED, true, true);
        } catch (Exception e) {
            if (!isRetryableConnectorError(e)) {
                log.error("{} - Non-retryable error re-initializing subscriptions for connector {}: {}",
                        tenant, connectorName, e.getMessage(), e);
                subscriptionInitRetryScheduled.set(false);
                connectionStateManager.updateStatusWithError(e);
                return;
            }
            long nextDelaySeconds = Math.min(previousDelaySeconds * 2, SUBSCRIPTION_INIT_RETRY_MAX_DELAY_SECONDS);
            log.warn("{} - Retry of subscription initialization failed for connector {}, next attempt in {}s: {}",
                    tenant, connectorName, nextDelaySeconds, e.getMessage());
            connectionStateManager.updateStatusRetrying(e, nextDelaySeconds);
            if (housekeepingExecutor == null || housekeepingExecutor.isShutdown()) {
                subscriptionInitRetryScheduled.set(false);
                return;
            }
            housekeepingExecutor.schedule(() -> runSubscriptionInitRetry(nextDelaySeconds), nextDelaySeconds, TimeUnit.SECONDS);
        }
    }

    /**
     * Perform housekeeping tasks - can be overridden
     */
    protected void performHousekeeping() {
        ConnectorStatus currentStatus = connectionStateManager.getCurrentStatus();

        // Update connector status if needed - check for UNKNOWN or DISCONNECTED
        if ((ConnectorStatus.UNKNOWN.equals(currentStatus) ||
                ConnectorStatus.DISCONNECTED.equals(currentStatus)) &&
                isConfigValid(connectorConfiguration)) {
            connectionStateManager.updateStatus(ConnectorStatus.CONFIGURED, true, true);
        }

        // Delegate to subclass-specific housekeeping
        connectorSpecificHousekeeping(tenant);

        mappingService.cleanDirtyMappings(tenant);
        mappingService.sendMappingStatus(tenant);

        monitorSubscriptions();
    }

    /**
     * Connector-specific housekeeping - to be implemented by subclasses
     */
    protected abstract void connectorSpecificHousekeeping(String tenant);

    /**
     * Initialize subscriptions for inbound mappings
     */
    public void initializeSubscriptionsInbound(List<Mapping> mappings, boolean reset) {
        List<Mapping> mappingsEffective = mappings.stream()
                .filter(this::isDeployedInConnector)
                .toList();
        mappingSubscriptionManager.updateSubscriptionsInbound(
                mappingsEffective,
                reset,
                isConnected(),
                this::isMappingCompatibleWithConnector);
    }

    /**
     * Initialize subscriptions for outbound mappings.
     * <p>
     * Delegates to {@link MappingSubscriptionManager#updateSubscriptionsOutbound} which
     * clears and rebuilds the effective outbound set. This makes the operation a true
     * reconcile: mappings that are no longer active or no longer deployed to this connector
     * are dropped, not just added.
     */
    public void initializeSubscriptionsOutbound(List<Mapping> mappings) {
        List<Mapping> deployedMappings = mappings.stream()
                .filter(this::isDeployedInConnector)
                .toList();
        mappingSubscriptionManager.updateSubscriptionsOutbound(deployedMappings,
                this::isMappingCompatibleWithConnector);
    }

    /**
     * Re-evaluate all inbound and outbound subscriptions for this connector against the
     * current mapping caches and deployment map.
     * <p>
     * Called when the deployment map changes (a mapping is assigned to / removed from this
     * connector) so that newly deployed mappings are subscribed and un-deployed mappings are
     * unsubscribed live, without requiring a connector reconnect or a manual mappings reload.
     */
    public void reconcileSubscriptions() {
        if (!isConnected() && !isPassiveReceiver()) {
            log.debug("{} - Not connected, skipping subscription reconcile for connector: {}",
                    tenant, connectorName);
            return;
        }

        List<Mapping> inboundMappings = new ArrayList<>(
                mappingService.getCacheInboundMappings(tenant).values());
        List<Mapping> outboundMappings = new ArrayList<>(
                mappingService.getCacheOutboundMappings(tenant).values());

        initializeSubscriptionsInbound(inboundMappings, false);
        initializeSubscriptionsOutbound(outboundMappings);

        log.info("{} - Reconciled subscriptions for connector: {}", tenant, connectorName);
    }

    /**
     * Whether this connector is a passive receiver that does not maintain an
     * outbound broker connection to receive inbound messages — it is driven by
     * incoming requests instead (e.g. the HTTP connector, fed by REST calls).
     * <p>
     * For such connectors there is no broker {@code subscribe} to perform, and
     * messages can arrive at any time via {@link #onMessage} regardless of the
     * reported connection state. Inbound subscription/resolver updates must
     * therefore be applied even when {@link #isConnected()} is {@code false};
     * otherwise a mapping deployed/activated after the connector was last
     * connected would never be added to the dispatch resolver.
     * <p>
     * Default: {@code false} (connection-backed connectors like MQTT/Kafka must
     * be connected to (un)subscribe at the broker).
     */
    protected boolean isPassiveReceiver() {
        return false;
    }

    /**
     * Update subscription for inbound mapping
     * Called when a mapping is created, updated, or its activation state changes
     * returns true if successful, false if connector is not connected or mapping is
     * invalid
     */
    public boolean updateSubscriptionForInbound(Mapping mapping, Boolean create, Boolean activationChanged) {
        boolean result = true;

        // Passive receivers (e.g. HTTP) have no broker subscribe and accept messages
        // at any time, so their resolver must be updated even when not "connected".
        if (!isConnected() && !isPassiveReceiver()) {
            log.debug("{} - Not connected, skipping subscription update for mapping: {}",
                    tenant, mapping.getIdentifier());
            return true;
        }

        // Always allow deactivation
        boolean isDeactivation = activationChanged && !mapping.getActive();

        // Only check compatibility if the mapping is actually assigned to this connector;
        // otherwise the incompatibility is irrelevant and the warning would be misleading.
        if (!isDeactivation && isDeployedInConnector(mapping) && !isMappingCompatibleWithConnector(mapping)) {
            result = false;
            return result;
        }

        try {
            handleSubscriptionUpdateInbound(mapping);
        } catch (Exception e) {
            log.error("{} - Error updating subscription for mapping {}: {}",
                    tenant, mapping.getIdentifier(), e.getMessage(), e);
            result = false;
        }

        return result;
    }

    private void handleSubscriptionUpdateInbound(Mapping mapping) throws ConnectorException {
        // The desired state is simply: subscribed if and only if the mapping is active AND
        // deployed to this connector. Both add and remove are idempotent, so this single
        // invariant correctly covers activation, deactivation, (un)deployment and no-op
        // updates — without depending on a separate "activationChanged" hint that callers
        // do not always set (e.g. a mapping update that also flips the active flag).
        if (mapping.getActive() && isDeployedInConnector(mapping)) {
            mappingSubscriptionManager.addSubscriptionInbound(mapping, mapping.getQos());
        } else {
            mappingSubscriptionManager.removeSubscriptionInbound(mapping);
        }
    }

    /**
     * Update subscription for outbound mapping
     * Called when a mapping is created, updated, or its activation state changes
     */
    public void updateSubscriptionForOutbound(Mapping mapping, Boolean create, Boolean activationChanged) {
        // Same invariant as inbound: applied if and only if active AND deployed here.
        // removeSubscriptionOutbound is idempotent and only logs on an actual removal.
        if (mapping.getActive() && isDeployedInConnector(mapping)) {
            mappingSubscriptionManager.addSubscriptionOutbound(mapping.getIdentifier(), mapping);
            log.debug("{} - Added outbound mapping: {}", tenant, mapping.getIdentifier());
        } else {
            mappingSubscriptionManager.removeSubscriptionOutbound(mapping.getIdentifier());
        }
    }

    /**
     * Delete active subscription for a mapping
     * Called when a mapping is deleted
     */
    public void deleteActiveSubscription(Mapping mapping) {
        if (mapping.getDirection() == Direction.INBOUND) {
            deleteInboundSubscription(mapping);
        } else {
            deleteOutboundSubscription(mapping);
        }
    }

    private void deleteInboundSubscription(Mapping mapping) {
        boolean wasEffective = mappingSubscriptionManager.isMappingInboundEffective(mapping.getIdentifier());
        if (!wasEffective) {
            log.debug("{} - Skip deleting inbound subscription for non-effective mapping: {}",
                    tenant, mapping.getIdentifier());
            return;
        }

        try {
            // Single source of truth: manager handles effective-mapping removal,
            // reference-count decrement and broker unsubscribe when count reaches zero.
            mappingSubscriptionManager.removeSubscriptionInbound(mapping);
            log.info("{} - Deleted inbound subscription for mapping: {}", tenant, mapping.getIdentifier());
        } catch (Exception e) {
            log.error("{} - Error deleting inbound subscription for mapping: {}", tenant, mapping.getIdentifier(), e);
        }
    }

    private void deleteOutboundSubscription(Mapping mapping) {
        mappingSubscriptionManager.removeSubscriptionOutbound(mapping.getIdentifier());
        log.info("{} - Deleted outbound subscription for mapping: {}", tenant, mapping.getIdentifier());
    }

    /**
     * Get subscription counts per topic for inbound mappings
     */
    public Map<String, MutableInt> getCountSubscriptionsPerTopicInbound() {
        return mappingSubscriptionManager.getSubscriptionCountsView();
    }

    /**
     * Check if a mapping's activation state has changed
     */
    public boolean activationChanged(Mapping mapping) {
        Optional<Mapping> activeMappingOptional = findActiveMappingInbound(mapping);
        if (activeMappingOptional.isPresent()) {
            Mapping activeMapping = activeMappingOptional.get();
            return !mapping.getActive().equals(activeMapping.getActive());
        }
        return false;
    }

    private Optional<Mapping> findActiveMappingInbound(Mapping mapping) {
        Map<String, Mapping> cacheMappings = mappingService.getCacheMappingInbound(tenant);
        if (cacheMappings == null) {
            return Optional.empty();
        }

        return cacheMappings.values().stream()
                .filter(m -> m.getId().equals(mapping.getId()))
                .findFirst();
    }

    /**
     * Check if mapping is deployed in this connector
     */
    private Boolean isDeployedInConnector(Mapping mapping) {
        List<String> deploymentMapEntry = mappingService.getDeploymentMapEntry(tenant, mapping.getIdentifier());
        // Explicit deployment required: if no deployment entry is configured,
        // the mapping is NOT deployed (default is no-deployment, not deploy-everywhere).
        // Only return true if deployment entry exists AND contains this connector's identifier.
        return deploymentMapEntry != null && deploymentMapEntry.contains(getConnectorIdentifier());
    }

    /**
     * Checks whether this connector is capable of handling the mapping's topic.
     * <p>
     * Currently this means: if the mapping's inbound topic contains MQTT wildcards
     * ({@code #} / {@code +}), the connector must support wildcard subscriptions. Outbound
     * mappings are always compatible (no broker subscription is performed).
     * <p>
     * This is a <em>capability</em> check only. Whether the mapping is actually assigned to
     * this connector is a separate concern handled by {@link #isDeployedInConnector(Mapping)}.
     */
    private boolean isMappingCompatibleWithConnector(Mapping mapping) {
        // Wildcards are only relevant for inbound subscriptions; ignore for outbound.
        boolean containsWildcards = mapping.getDirection().equals(Direction.INBOUND)
                && mapping.getMappingTopic().matches(".*[#+].*");
        boolean compatible = supportsWildcardInTopic(mapping.getDirection()) || !containsWildcards;

        if (!compatible) {
            log.warn("{} - Mapping {} contains unsupported wildcards for connector {}",
                    tenant, mapping.getId(), connectorName);
        }

        return compatible;
    }

    /**
     * Check if inbound mapping is deployed
     */
    public boolean isMappingInboundDeployed(String identifier) {
        return mappingSubscriptionManager.getEffectiveMappingsInbound().containsKey(identifier);
    }

    /**
     * Check if outbound mapping is deployed
     */
    public boolean isMappingOutboundDeployed(String identifier) {
        return mappingSubscriptionManager.getEffectiveMappingsOutbound().containsKey(identifier);
    }

    /**
     * Collect all subscribed mappings for deployment tracking
     */
    public void collectSubscribedMappingsAll(Map<String, DeploymentMapEntry> mappingsDeployed) {
        ConnectorConfiguration cleanedConfiguration = connectorConfiguration
                .getCleanedConfig(connectorSpecification);

        // Collect inbound mappings
        List<String> inboundMappingIds = new ArrayList<>(
                mappingSubscriptionManager.getEffectiveMappingsInbound().keySet());
        updateDeploymentMap(inboundMappingIds, mappingsDeployed, cleanedConfiguration);

        // Collect outbound mappings
        List<String> outboundMappingIds = new ArrayList<>(
                mappingSubscriptionManager.getEffectiveMappingsOutbound().keySet());
        updateDeploymentMap(outboundMappingIds, mappingsDeployed, cleanedConfiguration);
    }

    private void updateDeploymentMap(List<String> mappingIds,
            Map<String, DeploymentMapEntry> mappingsDeployed,
            ConnectorConfiguration cleanedConfiguration) {
        mappingIds.forEach(mappingIdentifier -> {
            DeploymentMapEntry mappingDeployed = mappingsDeployed.computeIfAbsent(
                    mappingIdentifier,
                    k -> new DeploymentMapEntry(mappingIdentifier));

            // Check if connector with same identifier already exists
            boolean exists = mappingDeployed.getConnectors().stream()
                    .anyMatch(c -> c.getIdentifier().equals(cleanedConfiguration.getIdentifier()));

            if (!exists) {
                mappingDeployed.getConnectors().add(cleanedConfiguration);
            }
        });
    }

    /**
     * Determine maximum QoS for inbound mappings on a specific topic
     */
    public Qos determineMaxQosInbound(String topic, List<Mapping> mappings) {
        return determineMaxQos(mappings, m -> m.getMappingTopic().equals(topic) && m.getActive());
    }

    /**
     * Determine maximum QoS for all inbound mappings
     */
    public Qos determineMaxQosInbound(List<Mapping> mappings) {
        return determineMaxQos(mappings, Mapping::getActive);
    }

    /**
     * Determine maximum QoS for all outbound mappings
     */
    public Qos determineMaxQosOutbound(List<Mapping> mappings) {
        return determineMaxQos(mappings, Mapping::getActive);
    }

    private Qos determineMaxQos(List<Mapping> mappings, java.util.function.Predicate<Mapping> filter) {
        int qosOrdinal = mappings.stream()
                .filter(filter)
                .map(m -> m.getQos().ordinal())
                .max(Integer::compareTo)
                .orElse(0);
        return Qos.values()[qosOrdinal];
    }

    /**
     * Abstract subscribe method to be implemented by subclasses
     */
    protected abstract void subscribe(String topic, Qos qos) throws ConnectorException;

    /**
     * Abstract unsubscribe method to be implemented by subclasses
     */
    protected abstract void unsubscribe(String topic) throws ConnectorException;

    /**
     * Check if connector is connected
     * Checks both the connection state manager AND physical connectivity
     */
    public final boolean isConnected() {
        // Handle case where connector hasn't been fully initialized yet
        if (connectionStateManager == null) {
            return false;
        }
        return connectionStateManager.isConnected() && isPhysicallyConnected();
    }

    /**
     * Template method for checking physical connectivity
     * Subclasses can override to perform actual connectivity tests
     * Default implementation returns true (assumes connection state manager is sufficient)
     * @return true if physically connected, false otherwise
     */
    protected boolean isPhysicallyConnected() {
        return true;
    }

    /**
     * Validate certificate configuration
     * Checks if certificate is properly configured when SSL is required
     * @param configuration the connector configuration to validate
     * @return true if certificate configuration is valid or not required
     */
    protected boolean validateCertificateConfig(ConnectorConfiguration configuration) {
        return sslSupport.validateCertificateConfig(configuration);
    }

    /**
     * Validate required properties in configuration
     * Subclasses should override to add connector-specific validation
     * @param configuration the connector configuration to validate
     * @param requiredProperties list of required property names
     * @return true if all required properties are present and non-empty
     */
    protected boolean validateRequiredProperties(ConnectorConfiguration configuration, String... requiredProperties) {
        if (requiredProperties == null || requiredProperties.length == 0) {
            return true;
        }

        List<String> missingProperties = new ArrayList<>();
        for (String propertyName : requiredProperties) {
            Object value = configuration.getProperties().get(propertyName);
            if (value == null || (value instanceof String && ((String) value).trim().isEmpty())) {
                missingProperties.add(propertyName);
            }
        }

        if (!missingProperties.isEmpty()) {
            log.warn("{} - Missing required configuration properties: {}", tenant, missingProperties);
            return false;
        }

        return true;
    }

    /**
     * Load configuration
     */
    protected void loadConfiguration() {
        connectorConfiguration = connectorConfigurationService
                .getConnectorConfiguration(getConnectorIdentifier(), tenant);
        connectorConfiguration.copyPredefinedValues(getConnectorSpecification());

        serviceConfiguration = serviceConfigurationService.getServiceConfiguration(tenant);
        configurationRegistry.addServiceConfiguration(tenant, serviceConfiguration);
    }

    /**
     * Should the connector connect
     */
    public boolean shouldConnect() {
        return isConfigValid(connectorConfiguration) && connectorConfiguration.getEnabled();
    }

    // Id of the Cumulocity Event backing the currently open connection-lifecycle session (see
    // ConnectionStateManager.updateSession), so subsequent transitions of the same session are
    // appended (PUT) to it instead of creating an independent Event per status change.
    private final AtomicReference<GId> activeConnectorStatusEventId = new AtomicReference<>();

    /**
     * Send connector lifecycle event.
     * <p>
     * Bundles all transitions of one connection-lifecycle session (e.g. CONNECTING -> CONNECTED,
     * possibly with RETRYING in between) into a single Cumulocity Event: the first transition of
     * a session creates the Event, further transitions update it in place — mirroring how a
     * Cumulocity Operation accumulates its "history of changes" under one record.
     */
    public void sendConnectorLifecycle(ConnectorStatusHistory session, boolean isNewSession) {
        if (!serviceConfiguration.getSendConnectorLifecycle()) {
            return;
        }
        Map<String, String> statusMap = createStatusMap(session);
        String message = statusMap.get("message");
        String severity = session.getCurrentStatus().toSeverity();

        // Defensive fallback: also (re-)create if no event id is on record even though the
        // session isn't new (e.g. the id was lost across a microservice restart mid-session).
        GId eventId = isNewSession ? null : activeConnectorStatusEventId.get();
        if (eventId == null) {
            eventId = c8yAgent.createConnectorStatusEvent(message, severity, DateTime.now(), tenant, statusMap, session);
            activeConnectorStatusEventId.set(eventId);
        } else {
            c8yAgent.updateConnectorStatusEvent(eventId, message, severity, DateTime.now(), tenant, statusMap, session);
        }
    }

    private Map<String, String> createStatusMap(ConnectorStatusHistory session) {
        List<ConnectorStatusEvent> history = session.getHistory();
        ConnectorStatusEvent latest = history.get(history.size() - 1);
        String message = latest.getMessage();
        if (message == null || "".equals(message)) {
            message = String.format("Connector status: %s", session.getCurrentStatus());
        }

        return Map.ofEntries(
                entry("status", session.getCurrentStatus().name()),
                entry("message", message),
                entry("connectorName", getConnectorName()),
                entry("connectorIdentifier", getConnectorIdentifier()));
    }

    /**
     * Reconnect
     * Performs disconnect, initialize, and connect sequence
     * <p>
     * Single-flight: {@code reconnect()} can be triggered concurrently from independent sources
     * (a scheduled health check, a user-triggered operation, a connection-lost callback). If a
     * reconnection sequence is already in flight, that same future is returned instead of
     * starting a second, overlapping disconnect/initialize/connect chain.
     * @return CompletableFuture for the reconnection sequence (never returns null)
     */
    public CompletableFuture<Void> reconnect() {
        synchronized (lifecycleLock) {
            if (reconnectTask != null && !reconnectTask.isDone()) {
                log.debug("{} - Reconnection already in progress for {}, joining existing attempt",
                        tenant, connectorName);
                return reconnectTask;
            }
            log.info("{} - Starting reconnection sequence for {}", tenant, connectorName);
            reconnectTask = CompletableFuture.runAsync(() -> {
                try {
                    submitDisconnect().get();
                    submitInitialize().get();
                    submitConnect().get();
                    log.info("{} - Reconnection completed successfully for {}", tenant, connectorName);
                } catch (java.util.concurrent.CancellationException e) {
                    // A newer reconnect attempt cancelled this one — this is expected behaviour
                    // when a CONNECT operation arrives while a previous reconnect is still in flight.
                    log.debug("{} - Reconnection for {} was superseded by a newer reconnect request",
                            tenant, connectorName);
                } catch (Exception e) {
                    log.error("{} - Reconnection failed for {}: {}", tenant, connectorName, e.getMessage(), e);
                    connectionStateManager.updateStatusWithError(e);
                    throw new CompletionException(e);
                }
            }, virtualThreadPool);
            return reconnectTask;
        }
    }

    /**
     * Stop housekeeping and close
     */
    public void stopHousekeepingAndClose() {
        if (housekeepingExecutor != null && !housekeepingExecutor.isShutdown()) {
            List<Runnable> stoppedTasks = housekeepingExecutor.shutdownNow();
            log.info("{} - Stopped {} housekeeping tasks", tenant, stoppedTasks.size());
        }
        close();
    }

    /**
     * Cleanup
     */
    public void cleanup() {
        stopHousekeepingAndClose();
        if (mappingSubscriptionManager != null) {
            mappingSubscriptionManager.clear();
        }
        // housekeepingExecutor is shut down above, cancelling any pending retry task —
        // reset the guard so a future reuse of this instance wouldn't be wedged with the
        // flag permanently stuck at true.
        subscriptionInitRetryScheduled.set(false);
    }

    public void sendSubscriptionEvents(String topic, String action) {
        if (!serviceConfiguration.getSendSubscriptionEvents()) {
            return;
        }

        String message = String.format("%s topic: %s", action, topic);
        Map<String, String> eventMap = createSubscriptionEventMap(message);

        c8yAgent.createLoggingEvent(
                message,
                LoggingEventType.SUBSCRIPTION_EVENT_TYPE,
                DateTime.now(),
                tenant,
                eventMap);
    }

    private Map<String, String> createSubscriptionEventMap(String message) {
        return Map.ofEntries(
                entry("message", message),
                entry("connectorName", getConnectorName()),
                entry("connectorIdentifier", getConnectorIdentifier()));
    }

    /**
     * Load certificate from configuration properties
     * Supports both inline PEM and C8Y certificate store
     */
    protected Certificate loadCertificateFromConfiguration() throws ConnectorException {
        return sslSupport.loadCertificateFromConfiguration(connectorConfiguration);
    }

    /**
     * Log certificate information
     */
    protected void logCertificateInfo(Certificate cert) {
        sslSupport.logCertificateInfo(cert);
    }

    /**
     * Create truststore with system CA certificates and custom certificates
     *
     * @param includeSystemCAs   if true, loads default Java cacerts; if false,
     *                           creates empty truststore
     * @param customCertificates list of custom certificates to add
     * @param cert               the Certificate object containing certificate info
     *                           (can be null if no custom certs)
     * @return configured KeyStore
     */
    protected KeyStore createTrustStore(boolean includeSystemCAs, List<X509Certificate> customCertificates,
            Certificate cert)
            throws Exception {
        return sslSupport.createTrustStore(includeSystemCAs, customCertificates, cert);
    }

    /**
     * Create TrustManagerFactory from KeyStore
     */
    protected TrustManagerFactory createTrustManagerFactory(KeyStore trustStore) throws Exception {
        return sslSupport.createTrustManagerFactory(trustStore);
    }

    /**
     * Log chain structure
     */
    protected void logChainStructure(Certificate cert) {
        sslSupport.logChainStructure(cert);
    }

    /**
     * Create custom hostname verifier for MQTT
     * Can be disabled via configuration property 'disableHostnameValidation'
     */
    protected HostnameVerifier createHostnameVerifier() {
        return sslSupport.createHostnameVerifier(connectorConfiguration);
    }

}