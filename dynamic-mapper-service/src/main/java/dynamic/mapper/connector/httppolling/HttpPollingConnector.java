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
import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.processor.inbound.CamelDispatcherInbound;
import dynamic.mapper.processor.runtime.ProcessingContext;
import dynamic.mapper.processor.runtime.ProcessingResultWrapper;
import dynamic.mapper.processor.util.ProcessingResultHelper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import com.dashjoin.jsonata.json.Json;

import static com.dashjoin.jsonata.Jsonata.jsonata;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * REST/HTTP Polling Connector Client (inbound only).
 * <p>
 * Periodically issues a GET request against a configured REST endpoint and feeds every
 * successful response into the standard inbound processing pipeline as a {@link ConnectorMessage},
 * exactly as {@code AbstractMqttCallback} does for a broker-pushed message.
 * <p>
 * "Subscribing" here does not mean a broker topic subscription: {@link #subscribe(String, Qos)}
 * is called once per distinct inbound mapping <em>topic</em>, not once per mapping —
 * {@code MappingSubscriptionManager} reference-counts mappings by topic and only invokes
 * {@code subscribe()} for the first mapping on a given topic, exactly the dedup semantics it
 * already applies for MQTT. One poll job is registered per distinct topic; mappings that share a
 * topic share that one poll job (and, since the topic doubles as the request path below, its one
 * underlying HTTP call) — they are not each polled independently. The topic doubles as the
 * request path appended to the connector's base {@code url} (see {@link #topicPath}, the same
 * convention the Default HTTP Connector already uses for inbound) — so one connector instance
 * (one host, one set of credentials) can poll many distinct endpoints, one per distinct topic,
 * rather than needing a new connector instance per URL.
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

    /** Bounds every GET call, independent of TCP-level connect/socket timeouts. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    protected WebClient pollingClient;
    protected String baseUrl;

    /** Topics currently subscribed. Membership (not the future map below) gates whether
     * {@link #scheduleNextPoll}/{@link #executePoll} keep rescheduling — tracked separately from
     * {@link #pollTasks} because {@code ConcurrentHashMap} does not allow null values, so a topic
     * can't be marked "subscribed" there before its first {@link ScheduledFuture} exists. */
    private final Set<String> subscribedTopics = ConcurrentHashMap.newKeySet();

    /** The currently scheduled (timing-only, see {@link #pollScheduler}) poll job per subscribed
     * topic, once one exists. */
    private final Map<String, ScheduledFuture<?>> pollTasks = new ConcurrentHashMap<>();

    /**
     * Topics with an {@link #executePoll} currently running. Guards against two overlapping
     * executions for the same topic — found in review 2026-09-22: {@link #subscribe} cancels the
     * previously scheduled future and reschedules unconditionally at delay 0, but
     * {@code Future.cancel(false)} cannot stop a run that has already started (e.g. blocked in
     * the HTTP call or in {@link #awaitProcessingSuccess}, up to
     * {@code ServiceConfiguration.PROCESSING_HARD_CEILING_MS} = 120s). A resubscribe of an
     * already-subscribed topic is a real path, not a contrived one:
     * {@code MappingSubscriptionManager.upgradeQosForRetainedTopics()} calls {@code subscribe()}
     * again whenever a second mapping added to an existing topic has a higher configured
     * {@code qos}. Without this guard, two concurrent executions for the same topic would race on
     * the shared {@code MappingStatus.cursor} read/write and could double-dispatch the same data.
     * {@link #executePoll} claims its topic here before doing any work and always releases it in
     * a {@code finally}; a run that finds its topic already claimed bails out immediately — the
     * in-flight run's own eventual {@link #scheduleNextPoll} call continues the chain, so nothing
     * needs to happen on the {@link #subscribe} side.
     */
    private final Set<String> inFlightTopics = ConcurrentHashMap.newKeySet();

    /**
     * Per-topic (not connector-wide) consecutive-failure counters feeding the RETRYING/FAILED
     * backoff logic — found in review 2026-09-22: a single shared counter meant one topic's
     * failures and another topic's successes could interleave, letting an unrelated topic's
     * blip push a healthy topic over {@link #MAX_CONSECUTIVE_FAILURES} (or a healthy topic's
     * successes mask a genuinely broken one's escalation). Significant once this connector's
     * whole point is serving several distinct topics/endpoints from one instance (see
     * {@link #topicPath}) — one bad endpoint must not permanently silence every other mapping on
     * the same connector. The connector-level {@code ConnectorStatus} itself is still shared
     * (one per connector instance, a framework-wide model, not changed here) — it settles back to
     * {@code CONNECTED} on any other topic's next successful poll rather than staying stuck on
     * whichever topic last reported.
     */
    private final Map<String, AtomicInteger> consecutiveFailuresByTopic = new ConcurrentHashMap<>();

    /** Timing only: each scheduled callback just hands the actual (blocking) poll work off to
     * {@link #virtualThreadPool} and returns immediately — found in review 2026-09-22: this used
     * to run {@link #executePoll} (including the HTTP call and the bounded wait in
     * {@link #awaitProcessingSuccess}, together up to ~2 minutes worst case) directly on this
     * small fixed pool, so a connector with more active topics than threads (or a few
     * simultaneously slow endpoints) would see poll intervals silently stretch. Every other
     * blocking-I/O dispatch in this codebase (e.g. {@code AbstractMqttCallback}) already uses
     * {@link #virtualThreadPool} for exactly this reason. */
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
            consecutiveFailuresByTopic.clear();

            connectionStateManager.setConnected(true);
            connectionStateManager.updateStatus(ConnectorStatus.CONNECTED, true, true);

            // Rebuild mapping caches and initialize subscriptions through the shared retry-aware path.
            initializeSubscriptionsAfterConnect();

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
        return HttpPollingRequestHelper.isConfigValid(configuration.getProperties(),
                MIN_POLL_INTERVAL_SECONDS, tenant, log);
    }

    @Override
    public Boolean supportsWildcardInTopic(Direction direction) {
        // Hardcoded false, not delegated to readWildcardFlag(): that helper reads the flag back
        // out of connectorConfiguration's stored properties, so on a connector where it's a real
        // runtime toggle a caller bypassing the UI (a raw API call, an import) could set it to
        // true and readWildcardFlag() would honor it. Each subscribed "topic" here is a concrete
        // poll-job key — there is no subscription-pattern-matching mechanism this connector could
        // honor even if asked to, in either direction, so the runtime answer must not be swayable
        // by a stored property at all. The corresponding ConnectorSpecification properties
        // (readonly, defaultValue false, outbound also hidden) are a UI-level hint on top of this,
        // not the actual guarantee.
        return false;
    }

    @Override
    protected boolean isPassiveReceiver() {
        // No broker connection to be "connected" to before (un)subscribing — inbound mapping
        // changes must reconcile poll jobs immediately, same reasoning as the HTTP connector.
        // Restored 2026-09-22: this had silently become `false` (the inherited default, matching
        // MQTT/Kafka's "must be connected first") with no explanatory comment — unlike every
        // other deliberate change to this file, which is the tell it was likely an accidental
        // drop from an unrelated edit rather than a considered decision. Confirmed the practical
        // difference is narrow either way: isConnected() never flips false due to poll failures
        // (neither updateStatusRetrying nor updateStatusWithError touch it, only an explicit
        // disconnect() does), so this only matters for mapping/topic changes arriving before the
        // very first successful connect() or after an explicit disconnect.
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
                        Thread t = new Thread(r, "http-polling-timer-" + connectorIdentifier);
                        t.setDaemon(true);
                        return t;
                    };
                    // Timing only (see field Javadoc) — 1 thread is enough since every callback
                    // just hands off to virtualThreadPool and returns immediately.
                    pollScheduler = Executors.newScheduledThreadPool(1, threadFactory);
                }
            }
        }
    }

    private void cancelPollTask(String topic) {
        ScheduledFuture<?> future = pollTasks.remove(topic);
        if (future != null) {
            future.cancel(false);
        }
        consecutiveFailuresByTopic.remove(topic);
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
        // Hand off to virtualThreadPool immediately rather than running executePoll (HTTP call +
        // the bounded wait in awaitProcessingSuccess, together up to ~2 minutes worst case)
        // directly on this small timing-only pool — see pollScheduler's field Javadoc.
        ScheduledFuture<?> future = scheduler.schedule(
                () -> virtualThreadPool.execute(() -> executePoll(topic)), delayMs, TimeUnit.MILLISECONDS);
        pollTasks.put(topic, future);
    }

    private void executePoll(String topic) {
        if (!subscribedTopics.contains(topic)) {
            // Unsubscribed since this run was scheduled.
            return;
        }

        // Guards against a resubscribe (e.g. upgradeQosForRetainedTopics()) firing a second,
        // concurrent executePoll() for the same topic while one is still blocked on an HTTP
        // call or awaitProcessingSuccess() — two overlapping polls would race on the same
        // mapping's cursor, each unaware of the other's advanceCursor() call. Only one poll per
        // topic may run at a time; a poll that finds itself shut out just skips this cycle,
        // since the in-flight one supersedes it anyway.
        if (!inFlightTopics.add(topic)) {
            log.debug("{} - Skipping poll for topic [{}]: previous poll still in flight", tenant, topic);
            return;
        }
        try {
            executePollInternal(topic);
        } finally {
            inFlightTopics.remove(topic);
        }
    }

    private void executePollInternal(String topic) {
        long pollIntervalMs = getEffectivePollIntervalSeconds() * 1000L;
        Mapping mapping = resolveMapping(topic);
        Map<String, Object> properties = connectorConfiguration.getProperties();
        String cursorParam = (String) properties.get("cursorParam");
        String pageParam = (String) properties.get("pageParam");
        String nextPageExpression = (String) properties.get("nextPageExpression");
        String paginationMode = readPaginationMode();
        int maxPages = HttpPollingRequestHelper.getMaxPagesPerPoll(properties.get("maxPagesPerPoll"));

        // Defensive, even though isConfigValid() already rejects this at save time (e.g. a
        // connector saved before that validation existed): never loop on duplicate page
        // requests because pageParam/nextPageExpression is missing — fall back to a single
        // page rather than silently re-fetching (and re-dispatching) the same response up to
        // maxPagesPerPoll times.
        if (!HttpPollingRequestHelper.isPaginationRuntimeConfigValid(paginationMode, pageParam, nextPageExpression)) {
            log.warn("{} - paginationMode [{}] misconfigured for topic [{}] (missing pageParam" +
                    "/nextPageExpression) — falling back to a single-page poll this cycle",
                    tenant, paginationMode, topic);
            paginationMode = "None";
        }

        try {
            String cursor = currentCursor(mapping);
            URI nextUri = null;
            String nextPageParamValue = "PageNumber".equals(paginationMode)
                    ? HttpPollingRequestHelper.readPageStartValue(properties.get("pageStartValue"))
                    : null;
            int pageCount = 0;
            boolean hasMore = true;

            while (hasMore) {
                pageCount++;

                // cancelPollTask() uses Future.cancel(false) (no interrupt), so a poll already
                // blocked in .block() below keeps running past an unsubscribe/disconnect that
                // happens while it's in flight. Re-check right before firing the request (skips
                // wasted work mid-pagination) and again immediately before dispatch below (the
                // gap that actually matters: unsubscribed between "request sent" and "response
                // arrived" must not let a disabled/deleted mapping's late data through).
                if (!subscribedTopics.contains(topic)) {
                    log.debug("{} - Poll for topic [{}] abandoned mid-cycle: unsubscribed while in flight",
                            tenant, topic);
                    return;
                }

                Map<String, String> queryParams = HttpPollingRequestHelper.buildQueryParams(cursor, cursorParam,
                        paginationMode, pageParam, nextPageParamValue);
                ResponseEntity<String> response = executeGet(topic, queryParams, nextUri)
                        .timeout(REQUEST_TIMEOUT).block();

                if (response == null || !response.getStatusCode().is2xxSuccessful()) {
                    throw new ConnectorException("Poll returned unsuccessful status: "
                            + (response != null ? response.getStatusCode() : "unknown"));
                }

                if (!subscribedTopics.contains(topic)) {
                    log.debug("{} - Discarding late response for topic [{}]: unsubscribed while the " +
                            "request was in flight", tenant, topic);
                    return;
                }

                consecutiveFailuresByTopic.computeIfAbsent(topic, k -> new AtomicInteger()).set(0);
                connectionStateManager.updateStatus(ConnectorStatus.CONNECTED, true, true);

                String body = response.getBody();
                if ("PageNumber".equals(paginationMode)) {
                    HttpPollingRequestHelper.PageStatus pageStatus = HttpPollingRequestHelper.classifyPage(body);
                    if (pageStatus == HttpPollingRequestHelper.PageStatus.UNPARSABLE) {
                        throw new ConnectorException("paginationMode PageNumber expected a JSON array/object " +
                                "response body but got an unparsable one on page " + pageCount + " for topic [" +
                                topic + "] — failing fast instead of paging through a misconfigured/" +
                                "non-paginated endpoint");
                    }
                    if (pageStatus == HttpPollingRequestHelper.PageStatus.EMPTY) {
                        break;
                    }
                }
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
                    ProcessingResultWrapper<?> resultWrapper = dispatcher.onMessage(connectorMessage);
                    if (!awaitProcessingSuccess(resultWrapper, topic)) {
                        // Treated exactly like a failed fetch: caught by the surrounding
                        // try/catch below, which routes through handlePollFailure (backoff,
                        // RETRYING/FAILED) — the cursor is not advanced for this page, and
                        // pagination stops here, so the next poll resumes from the last page
                        // that actually completed successfully.
                        throw new ConnectorException("Mapping processing did not complete " +
                                "successfully for topic [" + topic + "]");
                    }
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
                        nextUri = HttpPollingRequestHelper.extractNextLinkUri(response, tenant, log);
                        hasMore = nextUri != null;
                    }
                    case "NextFieldInBody" -> {
                        nextPageParamValue = HttpPollingRequestHelper.extractNextPageToken(body,
                                nextPageExpression, tenant, log);
                        hasMore = StringUtils.isNotEmpty(nextPageParamValue);
                    }
                    case "PageNumber" -> {
                        // The classifyPage() check above already returned before reaching here
                        // unless this page was NON_EMPTY (EMPTY breaks the loop, UNPARSABLE
                        // throws) — so there is always a next page number to try.
                        hasMore = true;
                        nextPageParamValue = String.valueOf(Long.parseLong(nextPageParamValue) + 1);
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

    /**
     * Blocks (bounded) on the mapping pipeline's actual result for one dispatched message,
     * mirroring the same wait/timeout/cancellation handling every broker callback (e.g.
     * {@code AbstractMqttCallback}) already uses for QoS &gt; 0 messages. {@code onMessage}
     * starts Camel processing asynchronously and returns a {@link ProcessingResultWrapper}
     * immediately — a future that merely *completing* says nothing about whether every matched
     * mapping actually succeeded, only {@link ProcessingResultHelper#extractMaxHttpStatus} does.
     * Without this, the cursor could advance (see {@link #advanceCursor}) past data a mapping
     * failure, timeout, or downstream Cumulocity error never actually processed.
     *
     * @return {@code true} only if processing completed with no error; {@code false} on any
     *         failure/timeout (already logged) — the caller must not advance the cursor then.
     */
    private boolean awaitProcessingSuccess(ProcessingResultWrapper<?> resultWrapper, String topic) {
        // Found in review 2026-09-22: ProcessingResultHelper.failure() — an early-exit wrapper
        // for a dispatch that never even started real (async) processing, e.g. payload
        // deserialization failure or no mapping resolved — builds a wrapper with NO
        // processingResult set at all, i.e. getProcessingResult() is null. Calling .get() on that
        // unconditionally threw a NullPointerException that happened to be caught two frames up
        // by executePoll's generic catch (Exception e) — accidentally fail-safe, not deliberately.
        // Handle it explicitly instead of relying on that.
        if (resultWrapper.getProcessingResult() == null) {
            log.warn("{} - Dispatch for topic [{}] failed before processing started (no mapping " +
                    "resolved, or the payload could not be deserialized)", tenant, topic);
            return false;
        }

        long timeoutMs = resultWrapper.getPipelineTimeoutMS() > 0
                ? resultWrapper.getPipelineTimeoutMS()
                : ServiceConfiguration.PROCESSING_HARD_CEILING_MS;
        try {
            List<? extends ProcessingContext<?>> results = resultWrapper.getProcessingResult()
                    .get(timeoutMs, TimeUnit.MILLISECONDS);

            // JS CPU timeout may have fired and closed the GraalVM context before the wall-clock
            // timeout expired — the future completes early with cancellationRequested=true.
            if (resultWrapper.getCancellationRequested().get()) {
                log.warn("{} - Processing for topic [{}] was cancelled (JS CPU timeout) before " +
                        "the wall-clock timeout, treating as failed", tenant, topic);
                return false;
            }

            int httpStatusCode = ProcessingResultHelper.extractMaxHttpStatus(results, tenant, topic, log);
            return httpStatusCode < 0;
        } catch (InterruptedException | ExecutionException e) {
            log.warn("{} - Mapping processing failed for topic [{}]: {}", tenant, topic, e.getMessage());
            return false;
        } catch (TimeoutException e) {
            boolean drained = resultWrapper.cancelAndDrain(ProcessingResultWrapper.DEFAULT_DRAIN_MILLIS);
            log.warn("{} - Mapping processing timed out for topic [{}] after {}ms (drained={})",
                    tenant, topic, timeoutMs, drained);
            return false;
        }
    }

    private void handlePollFailure(String topic, Exception e) {
        long pollIntervalMs = getEffectivePollIntervalSeconds() * 1000L;
        int attempt = consecutiveFailuresByTopic.computeIfAbsent(topic, k -> new AtomicInteger()).incrementAndGet();
        long delayMs = Math.min(attempt * pollIntervalMs, BACKOFF_CAP_MS);

        if (attempt <= MAX_CONSECUTIVE_FAILURES) {
            log.warn("{} - Poll failed for topic [{}] (attempt {}/{}), retrying in {}ms: {}",
                    tenant, topic, attempt, MAX_CONSECUTIVE_FAILURES, delayMs, e.getMessage());
            connectionStateManager.updateStatusRetrying(e, delayMs / 1000);
        } else {
            log.error("{} - Poll failed for topic [{}] {} consecutive times, marking connector FAILED: {}",
                    tenant, topic, attempt, e.getMessage());
            connectionStateManager.updateStatusWithError(e);
            // FAILED is terminal for this polling lifecycle; do not allow a later
            // success to overwrite it with CONNECTED.
            return;
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

        // keepAlive(false): every poll cycle is at least MIN_POLL_INTERVAL_SECONDS (30s) apart,
        // and reactor-netty's default HttpClient pools/reuses connections with no idle-eviction
        // by default. A connection left idle across that whole interval is exactly the kind of
        // stale connection an intermediate gateway/load balancer (Cumulocity's own platform
        // gateway in front of a deployed target, or any real-world API's reverse proxy) is likely
        // to have silently dropped — some close it outright (fast failure, harmless), but others
        // "black hole" it (accept the write, never respond), which the client can't distinguish
        // from a slow server until REQUEST_TIMEOUT fires. Found via a live tenant: a poll target
        // deployed as its own Cumulocity microservice intermittently hung for exactly 30s on
        // reused connections while every *fresh* connection succeeded immediately. Disabling
        // keep-alive means a new connection (and, over TLS, a new handshake) per request — real
        // but negligible overhead at a 30s-minimum poll cadence, and it eliminates this whole
        // class of bug outright rather than requiring the pool's idle timeout to be tuned lower
        // than every intermediary's own (unknown, un-configurable-by-us) timeout.
        WebClient.Builder builder = WebClient.builder()
                .baseUrl(normalizedBaseUrl)
                .clientConnector(new ReactorClientHttpConnector(HttpClient.create().keepAlive(false)))
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
                uriBuilder.path(HttpPollingRequestHelper.topicPath(topic));
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
     * its cursor too (stored on that one mapping's {@code MappingStatus}, since there is no
     * separate per-topic storage — see "Persistence" in the class doc).
     * <p>
     * The candidate is picked deterministically, by the lowest {@code identifier} — not "whichever
     * comes first" from {@code getEffectiveMappingsInbound()}'s iteration order. That backing map
     * is a plain {@code ConcurrentHashMap}, whose encounter order isn't guaranteed and can change
     * across a restart, or even mid-session after a rehash triggered by unrelated map churn — a
     * {@code findFirst()} on it could therefore silently switch which mapping's cursor is being
     * read/written between poll cycles, even with the same set of mappings deployed throughout.
     * Sorting by identifier fixes the same mapping every time as long as it continues to exist.
     * Caught in PR review (Copilot) — fixed 2026-09-22.
     * <p>
     * Known residual tradeoff: if that specific (lowest-identifier) mapping is later removed
     * while other same-topic mappings remain, the newly-lowest one has its own separate, likely
     * unset cursor — the group's progress is lost and incremental fetch effectively restarts for
     * it. Accepted since sharing one topic across mappings is itself an edge case (this connector
     * is far more commonly configured with one mapping per topic); a full fix would need cursor
     * storage keyed by (connector, topic) instead of by mapping, which is a bigger change than
     * warranted for that edge case alone.
     * <p>
     * Returns {@code null} for a topic with no backing mapping at all (e.g. a Message Explorer
     * session with no mapping deployed yet), in which case incremental fetch is simply skipped
     * for that poll.
     */
    private Mapping resolveMapping(String topic) {
        return mappingSubscriptionManager.getEffectiveMappingsInbound().values().stream()
                .filter(m -> topic.equals(m.getMappingTopic()))
                .min(Comparator.comparing(Mapping::getIdentifier))
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

    // Pagination requirement checks, query-param composition, and per-page continuation/stop-
    // condition extraction (NextLinkHeader/NextFieldInBody/PageNumber's classifyPage) all now live
    // in HttpPollingRequestHelper — pure functions of a response/body/config value, with no
    // dependency on this connector's mutable state, so they're both simpler to read here at the
    // call sites above and directly unit-testable without a wired-up connector instance.

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
                        .defaultValue(HttpPollingRequestHelper.DEFAULT_MAX_PAGES_PER_POLL)
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
                        .defaultValue(HttpPollingRequestHelper.DEFAULT_PAGE_START_VALUE)
                        .condition("paginationMode", "PageNumber")
                        .description("First page number sent under pageParam (e.g. 0 for a zero-indexed API)."))

                .property("supportsWildcardInTopicInbound", ConnectorPropertyBuilder.optionalBoolean()
                        .order(14)
                        .readonly(true)
                        .defaultValue(false))

                .property("supportsWildcardInTopicOutbound", ConnectorPropertyBuilder.optionalBoolean()
                        .order(15)
                        .readonly(true)
                        .hidden(true)
                        .defaultValue(false))

                .build();
    }
}
