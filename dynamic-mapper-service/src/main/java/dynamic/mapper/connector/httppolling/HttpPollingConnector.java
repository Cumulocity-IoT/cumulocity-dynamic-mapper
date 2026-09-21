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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import com.dashjoin.jsonata.json.Json;

import static com.dashjoin.jsonata.Jsonata.jsonata;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * — a deliberate tradeoff: mappings that happen to resolve to the same final URL are not
 * deduplicated. The topic doubles as the request path appended to the connector's base
 * {@code url} (see {@link #topicPath}, the same convention the Default HTTP Connector already
 * uses for inbound) — so one connector instance (one host, one set of credentials) can poll many
 * distinct endpoints, one per mapping, rather than needing a new connector instance per URL.
 * <p>
 * Optional v2 features, both opt-in and off by default (plain "GET full response every interval"
 * otherwise): incremental fetch (a cursor carried between separate polls) and intra-poll
 * pagination (draining multiple pages within one poll cycle) — see
 * {@code docs/feature/connector-http-polling.md} and
 * {@code docs/planning/IMPLEMENTATION-PLAN-HTTP-POLLING.md}.
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

    /** Default {@code maxPagesPerPoll} when not configured or invalid. */
    private static final int DEFAULT_MAX_PAGES_PER_POLL = 20;

    /** Default {@code pageStartValue} for {@code PageNumber} pagination mode. */
    private static final long DEFAULT_PAGE_START_VALUE = 1L;

    /** Matches the {@code rel="next"} entry of an RFC 5988 {@code Link} header, e.g.
     * {@code <https://api.example.com/x?page=2>; rel="next"}. */
    private static final Pattern NEXT_LINK_PATTERN = Pattern.compile("<([^>]+)>\\s*;\\s*rel=\"?next\"?");

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
        Mapping mapping = resolveMapping(topic);
        String paginationMode = readPaginationMode();
        int maxPages = getMaxPagesPerPoll();

        try {
            String cursor = currentCursor(mapping);
            URI nextUri = null;
            String nextPageParamValue = "PageNumber".equals(paginationMode) ? readPageStartValue() : null;
            int pageCount = 0;
            boolean hasMore = true;

            while (hasMore) {
                pageCount++;
                Map<String, String> queryParams = buildQueryParams(cursor, paginationMode, nextPageParamValue);
                ResponseEntity<String> response = executeGet(topic, queryParams, nextUri)
                        .timeout(REQUEST_TIMEOUT).block();

                if (response == null || !response.getStatusCode().is2xxSuccessful()) {
                    throw new ConnectorException("Poll returned unsuccessful status: "
                            + (response != null ? response.getStatusCode() : "unknown"));
                }

                consecutiveFailures.set(0);
                connectionStateManager.updateStatus(ConnectorStatus.CONNECTED, true, true);

                String body = response.getBody();
                byte[] payload = body != null
                        ? body.getBytes(StandardCharsets.UTF_8)
                        : new byte[0];

                ConnectorMessage connectorMessage = ConnectorMessage.builder()
                        .tenant(tenant)
                        .topic(topic)
                        .payload(payload)
                        .connectorIdentifier(connectorIdentifier)
                        .sendPayload(true)
                        .build();

                if (serviceConfiguration.getLogPayload()) {
                    log.info("{} - Poll succeeded for topic [{}], page {}, status: {}", tenant, topic,
                            pageCount, response.getStatusCode());
                }

                if (dispatcher != null) {
                    dispatcher.onMessage(connectorMessage);
                    // Advance the cursor after every page, not just once at the end of the whole
                    // poll: if a later page in this same cycle fails, the cursor must reflect the
                    // last page that actually made it through, not roll all the way back to
                    // before this poll started (see handlePollFailure / PLANNING.md's "failure
                    // semantics tie the two together").
                    advanceCursor(mapping, body);
                } else {
                    log.warn("{} - No dispatcher wired, dropping poll result for topic [{}]", tenant, topic);
                }

                hasMore = false;
                switch (paginationMode) {
                    case "NextLinkHeader" -> {
                        nextUri = extractNextLinkUri(response);
                        hasMore = nextUri != null;
                    }
                    case "NextFieldInBody" -> {
                        nextPageParamValue = extractNextPageToken(body);
                        hasMore = StringUtils.isNotEmpty(nextPageParamValue);
                    }
                    case "PageNumber" -> {
                        hasMore = !isEmptyPage(body);
                        if (hasMore) {
                            nextPageParamValue = String.valueOf(Long.parseLong(nextPageParamValue) + 1);
                        }
                    }
                    default -> hasMore = false; // "None": always a single page
                }

                if (hasMore && pageCount >= maxPages) {
                    log.warn("{} - Poll for topic [{}] reached maxPagesPerPoll ({}) with more pages " +
                            "available — stopping this cycle; the next scheduled poll resumes from the " +
                            "cursor recorded so far", tenant, topic, maxPages);
                    hasMore = false;
                }
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

        // Strip a trailing slash so appending a mapping's topic as a path (see executeGet /
        // topicPath) always joins with exactly one separator, never "//".
        String normalizedBaseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;

        WebClient.Builder builder = WebClient.builder()
                .baseUrl(normalizedBaseUrl)
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

    /**
     * @param topic       the mapping topic this poll is for — appended to {@link #baseUrl} as the
     *                    request path (see {@link #topicPath}), the same convention the Default
     *                    HTTP Connector already uses for inbound (topic = path segment). Lets one
     *                    connector instance (one base URL, one set of credentials) serve many
     *                    distinct endpoints, one per mapping, instead of needing a new connector
     *                    instance per URL. Ignored when {@code absoluteUri} is given.
     * @param queryParams query parameters to append (cursor and/or page param, whichever apply —
     *                    see {@link #buildQueryParams}). Ignored when {@code absoluteUri} is given.
     * @param absoluteUri when non-null (a {@code NextLinkHeader} pagination continuation), hit
     *                    this URI directly instead of {@link #baseUrl} + path + {@code queryParams}
     *                    — the server already handed back the complete next-page URL.
     */
    private Mono<ResponseEntity<String>> executeGet(String topic, Map<String, String> queryParams, URI absoluteUri) {
        WebClient.RequestHeadersSpec<?> request;
        if (absoluteUri != null) {
            request = pollingClient.get().uri(absoluteUri);
        } else {
            request = pollingClient.get().uri(uriBuilder -> {
                uriBuilder.path(topicPath(topic));
                queryParams.forEach(uriBuilder::queryParam);
                return uriBuilder.build();
            });
        }

        return request
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

    /**
     * Normalizes a mapping topic into a URI path segment: exactly one leading {@code /}, no
     * duplicate slashes when joined onto {@link #baseUrl} (already trailing-slash-stripped in
     * {@link #buildWebClient()}). {@code "devices/measurements"} and {@code "/devices/measurements"}
     * both become {@code "/devices/measurements"}.
     */
    private String topicPath(String topic) {
        return topic.startsWith("/") ? topic : "/" + topic;
    }

    // -------------------------------------------------------------------------
    // Incremental fetch (v2): a topic's cursor is stored on its mapping's MappingStatus
    // (persisted to inventory the same way every other mapping status field already is, which is
    // what makes it survive a service restart). Opt-in per mapping: empty/unset `cursorParam`
    // keeps v1 behavior (plain full-response poll, no cursor sent, nothing extracted or stored).
    // -------------------------------------------------------------------------

    /**
     * Resolves the topic back to its {@link Mapping}. Several mappings can in principle share one
     * topic (the same dedup semantics {@code MappingSubscriptionManager} applies for MQTT) — in
     * that case they already share this connector's one poll job for that topic, so they share
     * its cursor too; this returns the first match. Returns {@code null} for a topic with no
     * backing mapping at all (e.g. a Message Explorer session with no mapping deployed yet), in
     * which case incremental fetch is simply skipped for that poll.
     */
    private Mapping resolveMapping(String topic) {
        return mappingService.getCacheMappingInbound(tenant).values().stream()
                .filter(m -> topic.equals(m.getMappingTopic()))
                .findFirst()
                .orElse(null);
    }

    private String currentCursor(Mapping mapping) {
        if (mapping == null) {
            return null;
        }
        return mappingService.getMappingStatus(tenant, mapping).getCursor();
    }

    /**
     * Evaluates {@code cursorExtractionExpression} (JSONata) against the poll response and
     * stores the result as the mapping's new cursor. A no-op if incremental fetch isn't
     * configured, the mapping couldn't be resolved, or extraction fails (logged, not fatal —
     * a broken extraction expression degrades to "always full-response poll", not a failed poll).
     */
    private void advanceCursor(Mapping mapping, String responseBody) {
        if (mapping == null || StringUtils.isEmpty(responseBody)) {
            return;
        }
        String extractionExpression = (String) connectorConfiguration.getProperties().get("cursorExtractionExpression");
        if (StringUtils.isEmpty(extractionExpression)) {
            return;
        }

        try {
            Object parsed = Json.parseJson(responseBody);
            Object extracted = jsonata(extractionExpression).evaluate(parsed);
            if (extracted != null) {
                mappingService.getMappingStatus(tenant, mapping).setCursor(extracted.toString());
            }
        } catch (Exception e) {
            log.warn("{} - Failed to evaluate cursorExtractionExpression [{}] for mapping [{}]: {}",
                    tenant, extractionExpression, mapping.getIdentifier(), e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Intra-poll pagination (v2, part B): a single poll interval can drain multiple pages before
    // the connector considers that cycle done. Three data-driven modes, each with its own natural
    // "no more pages" signal from the response itself (no separate stop-condition config needed):
    //  - NextLinkHeader:  a Link response header (RFC 5988) with rel="next"; absent = last page.
    //  - NextFieldInBody: `nextPageExpression` (JSONata) extracts a token from the body; empty
    //                     result = last page.
    //  - PageNumber:      increments `pageParam` from `pageStartValue`; an empty response body
    //                     ([] or {}) = last page.
    // `maxPagesPerPoll` caps all three regardless of what the response claims, so one runaway or
    // misconfigured API can't starve this connector's other topics' scheduled polls forever.
    // -------------------------------------------------------------------------

    private String readPaginationMode() {
        String mode = (String) connectorConfiguration.getProperties().get("paginationMode");
        return StringUtils.isNotEmpty(mode) ? mode : "None";
    }

    private int getMaxPagesPerPoll() {
        Object value = connectorConfiguration.getProperties().get("maxPagesPerPoll");
        if (value == null) {
            return DEFAULT_MAX_PAGES_PER_POLL;
        }
        try {
            int parsed = Integer.parseInt(value.toString());
            return parsed > 0 ? parsed : DEFAULT_MAX_PAGES_PER_POLL;
        } catch (NumberFormatException e) {
            return DEFAULT_MAX_PAGES_PER_POLL;
        }
    }

    private String readPageStartValue() {
        Object value = connectorConfiguration.getProperties().get("pageStartValue");
        if (value == null) {
            return String.valueOf(DEFAULT_PAGE_START_VALUE);
        }
        try {
            return String.valueOf(Long.parseLong(value.toString()));
        } catch (NumberFormatException e) {
            return String.valueOf(DEFAULT_PAGE_START_VALUE);
        }
    }

    /**
     * Composes the query parameters for one page request: the incremental-fetch cursor (if
     * configured, independent of pagination — the two features combine freely) plus, for
     * {@code NextFieldInBody}/{@code PageNumber} modes, the current page token/number under
     * {@code pageParam}. {@code NextLinkHeader} mode contributes nothing here — its continuation
     * is the absolute URI handled separately in {@link #executeGet}.
     */
    private Map<String, String> buildQueryParams(String cursor, String paginationMode, String pageParamValue) {
        Map<String, String> params = new LinkedHashMap<>();
        String cursorParam = (String) connectorConfiguration.getProperties().get("cursorParam");
        if (StringUtils.isNotEmpty(cursorParam) && cursor != null) {
            params.put(cursorParam, cursor);
        }
        if (("NextFieldInBody".equals(paginationMode) || "PageNumber".equals(paginationMode))
                && pageParamValue != null) {
            String pageParam = (String) connectorConfiguration.getProperties().get("pageParam");
            if (StringUtils.isNotEmpty(pageParam)) {
                params.put(pageParam, pageParamValue);
            }
        }
        return params;
    }

    /** Parses an RFC 5988 {@code Link} header for the {@code rel="next"} entry. */
    private URI extractNextLinkUri(ResponseEntity<String> response) {
        List<String> linkHeaders = response.getHeaders().get(HttpHeaders.LINK);
        if (linkHeaders == null) {
            return null;
        }
        for (String headerValue : linkHeaders) {
            for (String part : headerValue.split(",")) {
                Matcher m = NEXT_LINK_PATTERN.matcher(part.trim());
                if (m.find()) {
                    try {
                        return URI.create(m.group(1));
                    } catch (IllegalArgumentException e) {
                        log.warn("{} - Ignoring unparsable next-page Link header value: {}", tenant, m.group(1));
                        return null;
                    }
                }
            }
        }
        return null;
    }

    /** Evaluates {@code nextPageExpression} (JSONata) against the response body. */
    private String extractNextPageToken(String responseBody) {
        String expression = (String) connectorConfiguration.getProperties().get("nextPageExpression");
        if (StringUtils.isEmpty(expression) || StringUtils.isEmpty(responseBody)) {
            return null;
        }
        try {
            Object parsed = Json.parseJson(responseBody);
            Object extracted = jsonata(expression).evaluate(parsed);
            return extracted != null ? extracted.toString() : null;
        } catch (Exception e) {
            log.warn("{} - Failed to evaluate nextPageExpression [{}]: {}", tenant, expression, e.getMessage());
            return null;
        }
    }

    /** {@code PageNumber} mode's stop condition: an empty JSON array or object body. */
    private boolean isEmptyPage(String responseBody) {
        if (StringUtils.isBlank(responseBody)) {
            return true;
        }
        try {
            Object parsed = Json.parseJson(responseBody);
            if (parsed instanceof Collection<?> collection) {
                return collection.isEmpty();
            }
            if (parsed instanceof Map<?, ?> map) {
                return map.isEmpty();
            }
        } catch (Exception e) {
            // Unparsable body: don't guess — treat as non-empty so pagination halts on the next
            // maxPagesPerPoll cap rather than silently stopping early on a transient parse issue.
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Config schema
    // -------------------------------------------------------------------------

    private ConnectorSpecification createConnectorSpecification() {
        return ConnectorSpecificationBuilder
                .create("REST Polling", ConnectorType.REST_POLLING)
                .description("Periodically issues an HTTP GET request against the configured REST endpoint " +
                        "and feeds each successful response into the inbound mapping pipeline. " +
                        "Optional incremental fetch: set cursorParam + cursorExtractionExpression to send " +
                        "a cursor with each request and advance it from the response. " +
                        "Optional pagination: set paginationMode to drain multiple pages per poll cycle. " +
                        "All optional features are off by default (plain full-response polling). " +
                        "pollIntervalSeconds has a hard minimum of " + MIN_POLL_INTERVAL_SECONDS + " seconds.")
                .supportsMessageContext(false)
                .supportedDirections(supportedDirections())

                .property("url", ConnectorPropertyBuilder.requiredString()
                        .order(0)
                        .description("Base REST endpoint. Each mapping's topic is appended as the request " +
                                "path — e.g. url=https://api.example.com/v1, mapping topic=devices/measurements " +
                                "-> GET https://api.example.com/v1/devices/measurements. Lets one connector " +
                                "instance (one host, one set of credentials) serve many mappings/endpoints."))

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

                .property("cursorParam", ConnectorPropertyBuilder.optionalString()
                        .order(7)
                        .description("Query parameter name used to send the incremental-fetch cursor with " +
                                "each poll (e.g. \"since\"). Leave empty to disable incremental fetch — every " +
                                "poll then fetches the full response, as in v1."))

                .property("cursorExtractionExpression", ConnectorPropertyBuilder.optionalString()
                        .order(8)
                        .description("JSONata expression evaluated against each response to compute the next " +
                                "cursor value (e.g. \"items[-1].timestamp\"). Only takes effect together with " +
                                "cursorParam; a response the expression can't evaluate leaves the cursor " +
                                "unchanged rather than failing the poll."))

                .property("paginationMode", ConnectorPropertyBuilder.optionalOption()
                        .order(9)
                        .options("None", "NextLinkHeader", "NextFieldInBody", "PageNumber")
                        .description("How to drain multiple pages within one poll cycle. None (default): a " +
                                "single GET per poll. NextLinkHeader: follow an RFC 5988 Link response header " +
                                "with rel=\"next\" until absent. NextFieldInBody: follow nextPageExpression " +
                                "until it returns nothing. PageNumber: increment pageParam from pageStartValue " +
                                "until a response is an empty [] or {}."))

                .property("maxPagesPerPoll", ConnectorPropertyBuilder.create(ConnectorPropertyType.NUMERIC_PROPERTY)
                        .order(10)
                        .required(false)
                        .defaultValue(DEFAULT_MAX_PAGES_PER_POLL)
                        .condition("paginationMode", "NextLinkHeader", "NextFieldInBody", "PageNumber")
                        .description("Safety cap on pages drained per poll cycle, regardless of whether more " +
                                "pages are available — protects other topics' scheduled polls on this connector " +
                                "from one runaway or misconfigured paginated endpoint."))

                .property("pageParam", ConnectorPropertyBuilder.optionalString()
                        .order(11)
                        .condition("paginationMode", "NextFieldInBody", "PageNumber")
                        .description("Query parameter name the next page's token/number is sent under " +
                                "(NextFieldInBody and PageNumber modes only; NextLinkHeader needs none, the " +
                                "server provides the full next-page URL)."))

                .property("nextPageExpression", ConnectorPropertyBuilder.optionalString()
                        .order(12)
                        .condition("paginationMode", "NextFieldInBody")
                        .description("JSONata expression evaluated against each response to extract the next " +
                                "page's token (e.g. \"nextPageToken\"); an empty/missing result ends pagination " +
                                "for this poll cycle."))

                .property("pageStartValue", ConnectorPropertyBuilder.create(ConnectorPropertyType.NUMERIC_PROPERTY)
                        .order(13)
                        .required(false)
                        .defaultValue(DEFAULT_PAGE_START_VALUE)
                        .condition("paginationMode", "PageNumber")
                        .description("First page number sent under pageParam (e.g. 0 for a zero-indexed API)."))

                .property("supportsWildcardInTopicInbound", ConnectorPropertyBuilder.optionalBoolean()
                        .order(14)
                        .readonly(true)
                        .defaultValue(false))

                .property("supportsWildcardInTopicOutbound", ConnectorPropertyBuilder.optionalBoolean()
                        .order(15)
                        .readonly(true)
                        .defaultValue(false))

                .build();
    }
}
