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

package dynamic.mapper.connector.httppolling;

import dynamic.mapper.configuration.ConnectorConfiguration;
import dynamic.mapper.connector.core.ConnectorPropertyBuilder;
import dynamic.mapper.connector.core.ConnectorPropertyType;
import dynamic.mapper.connector.core.ConnectorSpecification;
import dynamic.mapper.connector.core.ConnectorSpecificationBuilder;
import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.connector.core.client.ConnectorException;
import dynamic.mapper.connector.core.client.ConnectorType;
import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.core.ServiceRegistry;
import dynamic.mapper.model.status.ConnectorStatus;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.Qos;
import dynamic.mapper.processor.inbound.CamelDispatcherInbound;
import dynamic.mapper.processor.runtime.ProcessingContext;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * REST/HTTP Polling Connector Client (inbound only).
 * <p>
 * Periodically issues a GET request against a configured REST endpoint and feeds every
 * successful response into the standard inbound processing pipeline as a {@link ConnectorMessage},
 * exactly as {@code AbstractMqttCallback} does for a broker-pushed message.
 * <p>
 * "Subscribing" here does not mean a broker topic subscription: {@link #subscribe(String, Qos)}
 * is called once per distinct inbound mapping topic (the same dedup semantics
 * {@code MappingSubscriptionManager} already applies for MQTT) and registers a scheduled poll job
 * for that topic. This yields one HTTP call per mapping topic, not one shared call per connector
 * — a deliberate v1 tradeoff: mappings that happen to share the same topic/URL are not deduplicated.
 * <p>
 * v1 scope: plain "GET full response every interval", no pagination / incremental fetch (cursor)
 * support — see {@code attic/feature/http-polling/PLANNING.md}.
 */
@Slf4j
public class HttpPollingConnector extends AConnectorClient {

    /** Hard floor for pollIntervalSeconds — protects tenants' target APIs and this service from
     * too many concurrent per-mapping poll jobs. */
    public static final long MIN_POLL_INTERVAL_SECONDS = 30L;

    /** Default poll interval when not configured. */
    private static final long DEFAULT_POLL_INTERVAL_SECONDS = 60L;

    /** Consecutive poll failures (per connector instance) before escalating RETRYING -> FAILED. */
    private static final int MAX_CONSECUTIVE_FAILURES = 5;

    /** Cap on the linear backoff delay between retries, mirroring AMQTTClient.RECONNECT_DELAY_MAX_MS. */
    private static final long BACKOFF_CAP_MS = 300_000L;

    /** Bounds every GET call, independent of TCP-level connect/socket timeouts. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    protected WebClient pollingClient;
    protected String baseUrl;

    /** Topics currently subscribed. Membership (not the future map below) gates whether
     * {@link #scheduleNextPoll}/{@link #executePoll} keep rescheduling — tracked separately from
     * {@link #pollTasks} because {@code ConcurrentHashMap} does not allow null values, so a topic
     * can't be marked "subscribed" there before its first {@link ScheduledFuture} exists. */
    private final Set<String> subscribedTopics = ConcurrentHashMap.newKeySet();

    /** The currently scheduled poll job per subscribed topic, once one exists. */
    private final Map<String, ScheduledFuture<?>> pollTasks = new ConcurrentHashMap<>();

    /** Connector-wide consecutive-failure counter feeding the RETRYING/FAILED backoff logic. */
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

    private volatile ScheduledExecutorService pollScheduler;

    public HttpPollingConnector() {
        this.connectorType = ConnectorType.REST_POLLING;
        this.singleton = false;
        // A poll is a plain HTTP GET: it either succeeds or is retried on the next scheduled
        // attempt, i.e. at-least-once, same reasoning as WebHook's outbound calls.
        this.supportedQos = List.of(Qos.AT_LEAST_ONCE);
        this.connectorSpecification = createConnectorSpecification();
    }

    public HttpPollingConnector(ServiceRegistry serviceRegistry,
            ConnectorRegistry connectorRegistry,
            ConnectorConfiguration connectorConfiguration,
            CamelDispatcherInbound dispatcher,
            String additionalSubscriptionIdTest,
            String tenant) {
        this();
        wireFromRegistry(serviceRegistry, connectorRegistry, connectorConfiguration,
                dispatcher, additionalSubscriptionIdTest, tenant);
        initializeManagers();
    }

    @Override
    public boolean initialize() {
        loadConfiguration();

        try {
            baseUrl = (String) connectorConfiguration.getProperties().getOrDefault("url", null);
            if (baseUrl == null) {
                throw new ConnectorException("url is required but not configured");
            }

            log.info("{} - HttpPolling connector initialized, url: {}, pollIntervalSeconds: {}",
                    tenant, baseUrl, getEffectivePollIntervalSeconds());
            if (isConfigValid(connectorConfiguration)) {
                connectionStateManager.updateStatus(ConnectorStatus.CONFIGURED, true, true);
            }
            return true;
        } catch (Exception e) {
            log.error("{} - Error initializing HttpPolling connector: {}", tenant, e.getMessage(), e);
            connectionStateManager.updateStatusWithError(e);
            return false;
        }
    }

    @Override
    public void connect() {
        log.info("{} - Connecting HttpPolling connector: {}", tenant, connectorName);

        if (isConnected()) {
            log.debug("{} - Already connected, disconnecting first", tenant);
            disconnect();
        }

        if (!shouldConnect()) {
            log.info("{} - Connector disabled or invalid configuration", tenant);
            return;
        }

        try {
            connectionStateManager.updateStatus(ConnectorStatus.CONNECTING, true, true);

            pollingClient = buildWebClient();
            ensureScheduler();
            consecutiveFailures.set(0);

            connectionStateManager.setConnected(true);
            connectionStateManager.updateStatus(ConnectorStatus.CONNECTED, true, true);

            // Initialize inbound subscriptions -> schedules one poll job per mapping topic
            List<Mapping> inboundMappings = mappingService.getMappings(tenant, Direction.INBOUND);
            initializeSubscriptionsInbound(inboundMappings, true);

            log.info("{} - HttpPolling connector connected successfully, url: {}", tenant, baseUrl);
        } catch (Exception e) {
            log.error("{} - Error connecting HttpPolling connector: {}", tenant, e.getMessage(), e);
            connectionStateManager.setConnected(false);
            connectionStateManager.updateStatusWithError(e);
        }
    }

    @Override
    public void disconnect() {
        if (!isConnected()) {
            log.debug("{} - Already disconnected", tenant);
            // Still cancel any leftover poll jobs defensively (e.g. a previous disconnect that
            // didn't fully run), never leak a scheduled task across reconnects/tenants.
            cancelAllPollTasks();
            return;
        }

        log.info("{} - Disconnecting HttpPolling connector", tenant);
        connectionStateManager.updateStatus(ConnectorStatus.DISCONNECTING, true, true);

        try {
            // Safety-critical: cancel every outstanding scheduled poll job for this connector
            // instance before flipping the connection state, so nothing keeps firing against a
            // tenant/connector that is going away.
            cancelAllPollTasks();

            connectionStateManager.setConnected(false);
            connectionStateManager.updateStatus(ConnectorStatus.DISCONNECTED, true, true);

            log.info("{} - HttpPolling connector disconnected", tenant);
        } catch (Exception e) {
            log.error("{} - Error during disconnect: {}", tenant, e.getMessage(), e);
        }
    }

    /**
     * Also shuts down the poll scheduler entirely (not just the scheduled jobs cancelled by
     * {@link #disconnect()}), since {@code close()}/{@code stopHousekeepingAndClose()} means this
     * connector instance is being torn down for good — confirmed reached from tenant cleanup via
     * {@code ConnectorRegistry.unregisterAllClientsForTenant()} ->
     * {@code AConnectorClient.stopHousekeepingAndClose()} -> {@code close()} -> {@code disconnect()}.
     */
    @Override
    public void close() {
        super.close();
        ScheduledExecutorService scheduler = pollScheduler;
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }
        pollScheduler = null;
    }

    @Override
    protected void subscribe(String topic, Qos qos) throws ConnectorException {
        ensureScheduler();
        // Replace any previous job for this topic (e.g. a resubscribe after a config change)
        cancelPollTask(topic);
        // Mark as subscribed before scheduling so executePoll's "still subscribed" check passes
        // even if the first run fires almost immediately.
        subscribedTopics.add(topic);
        scheduleNextPoll(topic, 0L);
        sendSubscriptionEvents(topic, "Subscribed");
        log.info("{} - Scheduled poll job for mapping topic: [{}], interval: {}s", tenant, topic,
                getEffectivePollIntervalSeconds());
    }

    @Override
    protected void unsubscribe(String topic) throws ConnectorException {
        subscribedTopics.remove(topic);
        cancelPollTask(topic);
        sendSubscriptionEvents(topic, "Unsubscribed");
        log.info("{} - Cancelled poll job for mapping topic: [{}]", tenant, topic);
    }

    @Override
    public void publishMEAO(ProcessingContext<?> context) {
        // Inbound-only connector: nothing should ever route an outbound publish here.
        log.warn("{} - HttpPolling connector does not support outbound publishing", tenant);
    }

    @Override
    public boolean isConfigValid(ConnectorConfiguration configuration) {
        if (configuration == null) {
            return false;
        }

        String url = (String) configuration.getProperties().get("url");
        if (StringUtils.isEmpty(url)) {
            return false;
        }

        Long pollIntervalSeconds = readPollIntervalSeconds(configuration);
        if (pollIntervalSeconds != null && pollIntervalSeconds < MIN_POLL_INTERVAL_SECONDS) {
            log.warn("{} - pollIntervalSeconds {} is below the enforced minimum of {}s", tenant,
                    pollIntervalSeconds, MIN_POLL_INTERVAL_SECONDS);
            return false;
        }

        String authentication = (String) configuration.getProperties().get("authentication");
        String user = (String) configuration.getProperties().get("user");
        String password = (String) configuration.getProperties().get("password");
        String token = (String) configuration.getProperties().get("token");

        if ("Basic".equalsIgnoreCase(authentication)) {
            if (StringUtils.isEmpty(user) || StringUtils.isEmpty(password)) {
                return false;
            }
        } else if ("Bearer".equalsIgnoreCase(authentication)) {
            if (StringUtils.isEmpty(token)) {
                return false;
            }
        }

        return true;
    }

    @Override
    public Boolean supportsWildcardInTopic(Direction direction) {
        // Each subscribed "topic" is a concrete poll-job key, not a broker wildcard pattern.
        return readWildcardFlag(direction, false, false);
    }

    @Override
    protected boolean isPassiveReceiver() {
        // No broker connection to be "connected" to before (un)subscribing — inbound mapping
        // changes must reconcile poll jobs immediately, same reasoning as the HTTP connector.
        return true;
    }

    @Override
    public void monitorSubscriptions() {
        // No external broker connection to monitor; poll job health is reported per-poll via
        // connectionStateManager (see executePoll()).
    }

    @Override
    protected void connectorSpecificHousekeeping(String tenant) {
        // Safety net: if the connector is not connected (e.g. a disconnect that failed partway,
        // or a stale instance left around across a reconnect), make sure no poll job survives.
        if (!isConnected() && !subscribedTopics.isEmpty()) {
            log.warn("{} - HttpPolling connector not connected but {} poll job(s) still scheduled — cancelling",
                    tenant, subscribedTopics.size());
            cancelAllPollTasks();
        }
    }

    @Override
    public List<Direction> supportedDirections() {
        return Collections.singletonList(Direction.INBOUND);
    }

    @Override
    public String getConnectorIdentifier() {
        return connectorIdentifier;
    }

    @Override
    public String getConnectorName() {
        return connectorName;
    }

    // -------------------------------------------------------------------------
    // Poll scheduling
    // -------------------------------------------------------------------------

    private void ensureScheduler() {
        ScheduledExecutorService scheduler = pollScheduler;
        if (scheduler == null || scheduler.isShutdown()) {
            synchronized (this) {
                if (pollScheduler == null || pollScheduler.isShutdown()) {
                    ThreadFactory threadFactory = r -> {
                        Thread t = new Thread(r, "http-polling-" + connectorIdentifier);
                        t.setDaemon(true);
                        return t;
                    };
                    pollScheduler = Executors.newScheduledThreadPool(4, threadFactory);
                }
            }
        }
    }

    private void cancelPollTask(String topic) {
        ScheduledFuture<?> future = pollTasks.remove(topic);
        if (future != null) {
            future.cancel(false);
        }
    }

    private void cancelAllPollTasks() {
        for (String topic : new ArrayList<>(subscribedTopics)) {
            subscribedTopics.remove(topic);
            cancelPollTask(topic);
        }
    }

    private void scheduleNextPoll(String topic, long delayMs) {
        // Don't reschedule if the topic was unsubscribed while we were about to schedule it.
        if (!subscribedTopics.contains(topic)) {
            return;
        }
        ScheduledExecutorService scheduler = pollScheduler;
        if (scheduler == null || scheduler.isShutdown()) {
            return;
        }
        ScheduledFuture<?> future = scheduler.schedule(() -> executePoll(topic), delayMs, TimeUnit.MILLISECONDS);
        pollTasks.put(topic, future);
    }

    private void executePoll(String topic) {
        if (!subscribedTopics.contains(topic)) {
            // Unsubscribed since this run was scheduled.
            return;
        }

        long pollIntervalMs = getEffectivePollIntervalSeconds() * 1000L;

        try {
            ResponseEntity<String> response = executeGet().timeout(REQUEST_TIMEOUT).block();

            if (response != null && response.getStatusCode().is2xxSuccessful()) {
                consecutiveFailures.set(0);
                connectionStateManager.updateStatus(ConnectorStatus.CONNECTED, true, true);

                byte[] payload = response.getBody() != null
                        ? response.getBody().getBytes(StandardCharsets.UTF_8)
                        : new byte[0];

                ConnectorMessage connectorMessage = ConnectorMessage.builder()
                        .tenant(tenant)
                        .topic(topic)
                        .payload(payload)
                        .connectorIdentifier(connectorIdentifier)
                        .sendPayload(true)
                        .build();

                if (serviceConfiguration.getLogPayload()) {
                    log.info("{} - Poll succeeded for topic [{}], status: {}", tenant, topic,
                            response.getStatusCode());
                }

                if (dispatcher != null) {
                    dispatcher.onMessage(connectorMessage);
                } else {
                    log.warn("{} - No dispatcher wired, dropping poll result for topic [{}]", tenant, topic);
                }
            } else {
                handlePollFailure(topic,
                        new ConnectorException("Poll returned unsuccessful status: "
                                + (response != null ? response.getStatusCode() : "unknown")));
            }
        } catch (Exception e) {
            handlePollFailure(topic, e);
            return;
        }

        scheduleNextPoll(topic, pollIntervalMs);
    }

    private void handlePollFailure(String topic, Exception e) {
        long pollIntervalMs = getEffectivePollIntervalSeconds() * 1000L;
        int attempt = consecutiveFailures.incrementAndGet();
        long delayMs = Math.min(attempt * pollIntervalMs, BACKOFF_CAP_MS);

        if (attempt <= MAX_CONSECUTIVE_FAILURES) {
            log.warn("{} - Poll failed for topic [{}] (attempt {}/{}), retrying in {}ms: {}",
                    tenant, topic, attempt, MAX_CONSECUTIVE_FAILURES, delayMs, e.getMessage());
            connectionStateManager.updateStatusRetrying(e, delayMs / 1000);
        } else {
            log.error("{} - Poll failed for topic [{}] {} consecutive times, marking connector FAILED: {}",
                    tenant, topic, attempt, e.getMessage());
            connectionStateManager.updateStatusWithError(e);
        }

        scheduleNextPoll(topic, delayMs);
    }

    private long getEffectivePollIntervalSeconds() {
        Long configured = readPollIntervalSeconds(connectorConfiguration);
        long interval = configured != null ? configured : DEFAULT_POLL_INTERVAL_SECONDS;
        // Defensive floor even if isConfigValid() was bypassed somehow (e.g. config changed after
        // connect without a reconnect) — never poll faster than the enforced minimum.
        return Math.max(interval, MIN_POLL_INTERVAL_SECONDS);
    }

    private Long readPollIntervalSeconds(ConnectorConfiguration configuration) {
        if (configuration == null || configuration.getProperties() == null) {
            return null;
        }
        Object value = configuration.getProperties().get("pollIntervalSeconds");
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            log.warn("{} - Invalid pollIntervalSeconds value '{}', ignoring", tenant, value);
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // HTTP client (minimal GET-only equivalent of WebHook's buildWebClient()/executeHttpRequest();
    // WebHook's methods are private and outbound-shaped (method/path/payload from a
    // ProcessingContext), so a small, separate GET-only implementation is less invasive here
    // than changing WebHook's visibility or extracting a shared base class.)
    // -------------------------------------------------------------------------

    private WebClient buildWebClient() {
        String authentication = (String) connectorConfiguration.getProperties().get("authentication");
        String user = (String) connectorConfiguration.getProperties().get("user");
        String password = (String) connectorConfiguration.getProperties().get("password");
        String token = (String) connectorConfiguration.getProperties().get("token");
        @SuppressWarnings("unchecked")
        Map<String, String> headers = (Map<String, String>) connectorConfiguration.getProperties().get("headers");

        WebClient.Builder builder = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Accept", "application/json");

        if ("Basic".equalsIgnoreCase(authentication) && !StringUtils.isEmpty(user) && !StringUtils.isEmpty(password)) {
            String credentials = Base64.getEncoder()
                    .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
            builder.defaultHeader("Authorization", "Basic " + credentials);
        } else if ("Bearer".equalsIgnoreCase(authentication) && !StringUtils.isEmpty(token)) {
            builder.defaultHeader("Authorization", "Bearer " + token);
        }

        if (headers != null && !headers.isEmpty()) {
            headers.forEach((key, value) -> {
                if (value != null) {
                    builder.defaultHeader(key, value);
                }
            });
        }

        return builder.build();
    }

    private Mono<ResponseEntity<String>> executeGet() {
        return pollingClient.get()
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, response -> {
                    String error = "Poll failed with client error: " + response.statusCode();
                    return Mono.error(new ConnectorException(error));
                })
                .onStatus(HttpStatusCode::is5xxServerError, response -> {
                    String error = "Poll failed with server error: " + response.statusCode();
                    return Mono.error(new ConnectorException(error));
                })
                .toEntity(String.class);
    }

    // -------------------------------------------------------------------------
    // Config schema
    // -------------------------------------------------------------------------

    private ConnectorSpecification createConnectorSpecification() {
        return ConnectorSpecificationBuilder
                .create("REST Polling", ConnectorType.REST_POLLING)
                .description("Periodically issues an HTTP GET request against the configured REST endpoint " +
                        "and feeds each successful response into the inbound mapping pipeline. " +
                        "v1: plain full-response polling, no pagination or incremental (cursor) fetch. " +
                        "pollIntervalSeconds has a hard minimum of " + MIN_POLL_INTERVAL_SECONDS + " seconds.")
                .supportsMessageContext(false)
                .supportedDirections(supportedDirections())

                .property("url", ConnectorPropertyBuilder.requiredString()
                        .order(0)
                        .description("The REST endpoint to poll with GET."))

                .property("pollIntervalSeconds", ConnectorPropertyBuilder.create(ConnectorPropertyType.NUMERIC_PROPERTY)
                        .order(1)
                        .required(false)
                        .defaultValue(DEFAULT_POLL_INTERVAL_SECONDS)
                        .description("Poll interval in seconds. Hard minimum: " + MIN_POLL_INTERVAL_SECONDS + "s."))

                .property("authentication", ConnectorPropertyBuilder.optionalOption()
                        .order(2)
                        .options("None", "Basic", "Bearer"))

                .property("user", ConnectorPropertyBuilder.optionalString()
                        .order(3)
                        .condition("authentication", "Basic"))

                .property("password", ConnectorPropertyBuilder.optionalSensitive()
                        .order(4)
                        .condition("authentication", "Basic"))

                .property("token", ConnectorPropertyBuilder.optionalSensitive()
                        .order(5)
                        .condition("authentication", "Bearer"))

                .property("headers", ConnectorPropertyBuilder.create(ConnectorPropertyType.MAP_PROPERTY)
                        .order(6)
                        .description("Additional headers sent with every poll request.")
                        .required(false))

                .property("supportsWildcardInTopicInbound", ConnectorPropertyBuilder.optionalBoolean()
                        .order(7)
                        .readonly(true)
                        .defaultValue(false))

                .property("supportsWildcardInTopicOutbound", ConnectorPropertyBuilder.optionalBoolean()
                        .order(8)
                        .readonly(true)
                        .defaultValue(false))

                .build();
    }
}
