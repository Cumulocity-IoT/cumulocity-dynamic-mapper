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

package dynamic.mapper.mapping;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.cumulocity.microservice.subscription.model.MicroserviceSubscriptionRemovedEvent;
import com.cumulocity.model.ID;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.connector.core.registry.ConnectorRegistryException;
import dynamic.mapper.connector.test.TestClient;
import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.model.API;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.DynamicMapperRequest;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingType;
import dynamic.mapper.model.TestContext;
import dynamic.mapper.model.TestResult;
import dynamic.mapper.notification.OutboundDispatcherRegistry;
import dynamic.mapper.notification.websocket.Notification;
import dynamic.mapper.processor.outbound.serializer.SparkPlugBSerializer;
import dynamic.mapper.processor.runtime.ProcessingContext;
import dynamic.mapper.processor.runtime.ProcessingResultWrapper;
import dynamic.mapper.processor.util.APITopicUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs a mapping against a test payload through the test connector, and reports what it would send.
 * <p>
 * A dry run only transforms. A live run ({@code send}) of an inbound mapping also creates the requests in
 * Cumulocity, always against a test device so that no real device is touched. The mock identity and
 * inventory stores are shared by every test of a tenant, so the runs of one tenant are serialized.
 */
@Slf4j
@Service
public class MappingTestService {

    /** Why a test could not be run at all. */
    public enum Kind {
        BAD_REQUEST, NOT_FOUND, CONFLICT, GATEWAY_TIMEOUT, FAILED
    }

    /** A test that could not be run; the REST layer maps the kind to an HTTP status. */
    public static class MappingTestException extends RuntimeException {
        private final Kind kind;

        public MappingTestException(Kind kind, String message) {
            super(message);
            this.kind = kind;
        }

        public Kind getKind() {
            return kind;
        }
    }

    private final ConnectorRegistry connectorRegistry;
    private final OutboundDispatcherRegistry dispatcherRegistry;
    private final C8YAgent c8YAgent;
    private final SparkPlugBSerializer sparkPlugBSerializer;

    public MappingTestService(ConnectorRegistry connectorRegistry, OutboundDispatcherRegistry dispatcherRegistry,
            C8YAgent c8YAgent, SparkPlugBSerializer sparkPlugBSerializer) {
        this.connectorRegistry = connectorRegistry;
        this.dispatcherRegistry = dispatcherRegistry;
        this.c8YAgent = c8YAgent;
        this.sparkPlugBSerializer = sparkPlugBSerializer;
    }

    /**
     * Runs a mapping test for the tenant: a dry run, or with {@code send} a live run that dispatches the
     * result to Cumulocity.
     *
     * @return the requests, warnings, and errors of the run; a live test that cannot start is reported as a
     *         failed result, not thrown
     * @throws MappingTestException if the test cannot be run at all
     */
    public TestResult runTest(String tenant, TestContext context) {
        TestResult result = new TestResult();
        Mapping mapping = context.getMapping();
        String payload = context.getPayload();
        // @Valid is not enforced on the body, so check what the rest of the method dereferences: a missing
        // field used to surface as a NullPointerException (a 500, or a 400 without a message).
        if (mapping == null || mapping.getDirection() == null) {
            throw new MappingTestException(Kind.BAD_REQUEST, "A test needs a mapping with a direction.");
        }
        if (payload == null) {
            throw new MappingTestException(Kind.BAD_REQUEST, "A test needs a payload.");
        }
        Boolean send = Boolean.TRUE.equals(context.getSend()); // absent means Dry Run
        log.info("{} - Test mapping: {}, {}, send={}", tenant, mapping.getName(), mapping.getId(), send);
        // debug, not info: the payload is arbitrary user content that should not land in the tenant's log by default.
        log.debug("{} - Test payload: {}", tenant, payload);

        // The mock identity and inventory stores are shared by every test of a tenant, and a run clears
        // them before and after itself. Two runs at once would wipe each other's devices mid-run and fail
        // with errors that have nothing to do with the mapping, so runs of one tenant are serialized.
        ReentrantLock tenantLock = acquireTenantTestLock(tenant);

        // Reset mock caches for this tenant so state from previous test runs does not leak
        clearMockCaches(tenant);

        try {
            AConnectorClient connectorClient = connectorRegistry
                    .getClientForTenant(tenant, TestClient.TEST_CONNECTOR_IDENTIFIER);
            // Set for a live test of an inbound mapping: used to recover from a stale cached device ID.
            ID liveTestIdentity = null;

            // A live test (send=true) of an inbound mapping only ever runs against a test device:
            // created if the external ID is unknown, otherwise the existing device is used. It must
            // not create devices through the mapping's createNonExistingDevice setting, which would
            // leave an untagged real device behind.
            // Run a dry-run first so we can extract the actual externalId produced by the
            // mapping's substitution, rather than guessing from the static source template.
            if (Boolean.TRUE.equals(send) && mapping.getDirection().equals(Direction.INBOUND)) {
                String externalId = resolveExternalIdViaDryRun(tenant, connectorClient, mapping, payload);
                if (externalId == null) {
                    externalId = extractIdentityFromSourceTemplate(mapping.getSourceTemplate());
                }
                String externalIdType = (mapping.getExternalIdType() != null && !mapping.getExternalIdType().isEmpty())
                        ? mapping.getExternalIdType()
                        : "c8y_Serial";
                if (externalId == null) {
                    return liveTestFailure(result, tenant,
                            "The mapping does not identify its device by an external ID, so a Live Test cannot "
                                    + "provide a test device for it and was not run. Identify the device by external "
                                    + "ID, or use a Dry Run.");
                }
                // Never register the test device under the mapping's own external ID: once the real
                // device sends its first message, that message would be written to the test device.
                String testExternalId = ProcessingContext.TEST_EXTERNAL_ID_PREFIX + externalId;
                liveTestIdentity = new ID(externalIdType, testExternalId);
                C8YAgent.TestDevice testDevice = c8YAgent.ensureTestDevice(tenant, testExternalId, testExternalId,
                        externalIdType);
                if (testDevice != null) {
                    result.setTestDeviceId(testDevice.id());
                    result.setTestDeviceCreated(testDevice.created());
                    result.setTestDeviceExternalId(testExternalId);
                    log.info("{} - {} test device: id={}, externalId={}, type={}", tenant,
                            testDevice.created() ? "Created" : "Reusing existing", testDevice.id(), testExternalId,
                            externalIdType);
                } else {
                    return liveTestFailure(result, tenant,
                            "The test device for external ID '" + testExternalId + "' could not be created or found, "
                                    + "so the live test was not run.");
                }
            }

            ProcessingResultWrapper<?> processingResultWrapper = null;
            if (mapping.getDirection().equals(Direction.INBOUND)) {
                ConnectorMessage testMessage = createTestMessage(tenant, connectorClient, mapping,
                        mapping.getMappingTopicSample(), send, payload);
                if (Boolean.TRUE.equals(send)) {
                    testMessage.setTestExternalIdPrefix(ProcessingContext.TEST_EXTERNAL_ID_PREFIX);
                }
                processingResultWrapper = connectorClient.getDispatcher().onTestMessage(
                        testMessage,
                        mapping);
            } else if (mapping.getDirection().equals(Direction.OUTBOUND)) {
                Notification testNotification = createTestNotification(tenant, connectorClient, mapping.getTargetAPI(),
                        send, payload);
                processingResultWrapper = dispatcherRegistry.get(tenant, TestClient.TEST_CONNECTOR_IDENTIFIER)
                        .onTestNotification(
                                testNotification,
                                mapping,
                                Boolean.TRUE.equals(send));
            }

            if (processingResultWrapper != null && processingResultWrapper.getProcessingResult() != null) {
                // Wait for the future to complete and get the result
                List<? extends ProcessingContext<?>> processingResult = awaitContexts(processingResultWrapper);

                // The test device was deleted but its ID is still cached somewhere: the send fails with
                // "No managedObject for id". Evict, re-ensure the device and retry once.
                // Siblings of the failed request may already have been created (the pipeline continues after
                // a failure), so a retry would duplicate them: only retry if nothing was sent successfully.
                boolean staleTestDevice = false;
                boolean retrySkipped = false;
                boolean deviceNotFound = liveTestIdentity != null && containsDeviceNotFound(processingResult);
                if (deviceNotFound && anyRequestSucceeded(processingResult)) {
                    log.warn("{} - Live test hit a deleted test device ({}), but other requests already succeeded: "
                            + "not retrying to avoid duplicates", tenant, liveTestIdentity.getValue());
                    c8YAgent.evictExternalIdFromCaches(tenant, liveTestIdentity);
                    retrySkipped = true;
                    staleTestDevice = true;
                } else if (deviceNotFound) {
                    log.warn("{} - Live test hit a deleted test device ({}), evicting the cached ID and retrying once",
                            tenant, liveTestIdentity.getValue());
                    c8YAgent.evictExternalIdFromCaches(tenant, liveTestIdentity);
                    C8YAgent.TestDevice recreated = c8YAgent.ensureTestDevice(tenant, liveTestIdentity.getValue(),
                            liveTestIdentity.getValue(), liveTestIdentity.getType());
                    if (recreated != null) {
                        result.setTestDeviceId(recreated.id());
                        result.setTestDeviceCreated(recreated.created());
                        ConnectorMessage retryMessage = createTestMessage(tenant, connectorClient, mapping,
                                mapping.getMappingTopicSample(), send, payload);
                        retryMessage.setTestExternalIdPrefix(ProcessingContext.TEST_EXTERNAL_ID_PREFIX);
                        ProcessingResultWrapper<?> retryWrapper = connectorClient.getDispatcher()
                                .onTestMessage(retryMessage, mapping);
                        if (retryWrapper != null && retryWrapper.getProcessingResult() != null) {
                            processingResult = awaitContexts(retryWrapper);
                        }
                    }
                    staleTestDevice = containsDeviceNotFound(processingResult);
                }

                if (processingResult != null && !processingResult.isEmpty()) {
                    // Every context's requests, warnings and logs: a payload that fans out into several
                    // contexts (e.g. an array) used to show only the first one, although a Live Test had
                    // already sent all of them.
                    for (var processingContext : processingResult) {
                        if (processingContext.getRequests() != null) {
                            result.getRequests().addAll(processingContext.getRequests());
                        }
                        if (processingContext.getWarnings() != null) {
                            for (String warning : processingContext.getWarnings()) {
                                if (!result.getWarnings().contains(warning)) {
                                    result.getWarnings().add(warning);
                                }
                            }
                        }
                        if (processingContext.getLogs() != null) {
                            result.getLogs().addAll(processingContext.getLogs());
                        }
                        if (processingContext.getPublishedConnectors() != null) {
                            for (String connectorName : processingContext.getPublishedConnectors()) {
                                if (!result.getPublishedConnectors().contains(connectorName)) {
                                    result.getPublishedConnectors().add(connectorName);
                                }
                            }
                        }
                        if (result.getKey() == null) {
                            result.setKey(processingContext.getKey());
                        }
                        if (processingContext.getErrors() == null) {
                            continue;
                        }
                        for (Exception e : processingContext.getErrors()) {
                            String msg = describeError(e);
                            if (!result.getErrors().contains(msg)) {
                                result.getErrors().add(msg);
                            }
                        }
                    }
                    addRequestErrors(result);
                    if (staleTestDevice) {
                        result.getErrors().add("The test device '" + liveTestIdentity.getValue() + "' no longer "
                                + "exists but its ID is still cached, and "
                                + (retrySkipped
                                        ? "some requests already succeeded, so the test was not retried automatically (the cached ID was evicted). "
                                        : "re-creating it did not help. ")
                                + (retrySkipped ? "Run the Live Test again."
                                        : "Clear the Inbound ID cache (Monitoring > Cache statistic) and run the Live Test again."));
                    }
                    result.setSuccess(result.getErrors().isEmpty());
                    // If filter is configured and no requests were produced, add filter warning.
                    // Warnings from ProcessingContext may not survive the Camel split+stop pipeline,
                    // so we reconstruct the warning here as a reliable fallback.
                    if (result.getWarnings().isEmpty() && result.getRequests().isEmpty()) {
                        String filterExpression = mapping.getFilterMapping();
                        if (filterExpression != null && !filterExpression.isEmpty()) {
                            result.getWarnings().add("Payload will be ignored due to filter: " + filterExpression);
                        }
                    }
                }
            }
            if (!Boolean.TRUE.equals(result.getSuccess()) && result.getErrors().isEmpty()) {
                // No result came back at all (the pipeline stopped before a context was produced,
                // or the dispatcher returned nothing). Returning success=false with an empty
                // error list left the UI with nothing to show; say what is known.
                result.getErrors().add("The test produced no result. The mapping was not executed; "
                        + "check that it is valid and that its topic or filter matches the test payload. "
                        + "The service log has more detail.");
            }
            return result;

        } catch (ConnectorRegistryException e) {
            log.error("{} - Connector not found for tenant: {}", tenant, e.getMessage(), e);
            throw new MappingTestException(Kind.NOT_FOUND,
                    "Connector not found: " + e.getMessage());
        } catch (TimeoutException e) {
            log.error("{} - Test timed out: {}", tenant, e.getMessage());
            throw new MappingTestException(Kind.GATEWAY_TIMEOUT,
                    "The test did not finish in time and was cancelled. Check the connectivity to Cumulocity "
                            + "and the mapping for long-running code.");
        } catch (InterruptedException e) {
            log.error("{} - Test interrupted: {}", tenant, e.getMessage(), e);
            Thread.currentThread().interrupt(); // Restore interrupt status
            throw new MappingTestException(Kind.FAILED,
                    "Test execution interrupted");
        } catch (ExecutionException e) {
            log.error("{} - Error executing test: {}", tenant, e.getMessage(), e);
            throw new MappingTestException(Kind.FAILED,
                    "Test execution failed: " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
        } catch (Exception ex) {
            log.error("{} - Error transforming payload: {}", tenant, ex.getMessage(), ex);
            // describeError never returns null: an exception without a message (a NullPointerException,
            // say) used to reach the UI as an empty message and show only "Could not be tested!".
            throw new MappingTestException(Kind.BAD_REQUEST, "The test could not be run: " + describeError(ex));
        } finally {
            // Leave nothing behind, whatever the outcome: devices created in the mock by this run
            // must not be found by the next one, and the mock should not hold memory between runs.
            try {
                clearMockCaches(tenant);
            } finally {
                tenantLock.unlock();
            }
        }
    }

    /** How long a test waits for another test of the same tenant to finish before giving up. */
    private long testLockWaitMs = 30_000;

    private final ConcurrentMap<String, ReentrantLock> tenantTestLocks = new ConcurrentHashMap<>();

    /** Drops the per-tenant test lock once the tenant unsubscribed. */
    @EventListener
    public void onTenantUnsubscribed(
            MicroserviceSubscriptionRemovedEvent event) {
        tenantTestLocks.remove(event.getTenant());
    }

    private ReentrantLock acquireTenantTestLock(String tenant) {
        ReentrantLock lock = tenantTestLocks.computeIfAbsent(tenant, t -> new ReentrantLock());
        try {
            if (!lock.tryLock(testLockWaitMs, TimeUnit.MILLISECONDS)) {
                log.warn("{} - Test rejected: another test of this tenant did not finish within {} ms", tenant,
                        testLockWaitMs);
                throw new MappingTestException(Kind.CONFLICT,
                        "Another test of this tenant is still running. Wait for it to finish and run this test again.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MappingTestException(Kind.FAILED, "Test execution interrupted");
        }
        return lock;
    }

    /**
     * Reports the requests that failed without leaving an error on a context. A connector that has no
     * topic to publish to only marks the request, so a Live Test used to come back successful although
     * nothing was sent. An error already reported through a context (its text contains the request's)
     * is not repeated, so this runs after the contexts' own errors were collected.
     */
    private static void addRequestErrors(TestResult result) {
        List<DynamicMapperRequest> requests = result.getRequests();
        for (int i = 0; i < requests.size(); i++) {
            DynamicMapperRequest request = requests.get(i);
            if (request == null || !request.hasError()) {
                continue;
            }
            String text = describeError(request.getError());
            if (result.getErrors().stream().noneMatch(known -> known.contains(text))) {
                result.getErrors().add("Request " + (i + 1) + " failed: " + text);
            }
        }
    }

    /** What a test waits for the pipeline when the dispatcher did not report its own timeout. */
    private static final long DEFAULT_TEST_TIMEOUT_MS = 60_000;

    /** Grace period on top of the pipeline timeout, so the pipeline's own timeout handling wins the race. */
    private long testTimeoutMarginMs = 5_000;

    /**
     * Waits for the pipeline, but not forever: a starved Cumulocity semaphore or a hanging REST call
     * would otherwise hold the HTTP request open indefinitely. On timeout the work is cancelled.
     */
    @SuppressWarnings("unchecked")
    private List<? extends ProcessingContext<?>> awaitContexts(ProcessingResultWrapper<?> wrapper)
            throws InterruptedException, ExecutionException, TimeoutException {
        long timeoutMs = (wrapper.getPipelineTimeoutMS() > 0 ? wrapper.getPipelineTimeoutMS() : DEFAULT_TEST_TIMEOUT_MS)
                + testTimeoutMarginMs;
        try {
            return (List<? extends ProcessingContext<?>>) wrapper.getProcessingResult().get(timeoutMs,
                    TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            wrapper.getProcessingResult().cancel(true);
            wrapper.getCancellationRequested().set(true);
            throw e;
        }
    }

    /** A live test must not fall through to the mapping creating devices itself: report and stop. */
    private TestResult liveTestFailure(TestResult result, String tenant, String message) {
        log.warn("{} - {}", tenant, message);
        result.setSuccess(false);
        result.getErrors().add(message);
        return result;
    }

    /** True if any request was sent without error, i.e. re-dispatching the payload would send it twice. */
    public static boolean anyRequestSucceeded(List<? extends ProcessingContext<?>> contexts) {
        if (contexts == null) {
            return false;
        }
        for (ProcessingContext<?> processingContext : contexts) {
            if (processingContext == null || processingContext.getRequests() == null) {
                continue;
            }
            for (var request : processingContext.getRequests()) {
                if (request != null && !request.hasError()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** True if any context failed because a managed object (a cached, since deleted device) is gone. */
    public static boolean containsDeviceNotFound(List<? extends ProcessingContext<?>> contexts) {
        if (contexts == null) {
            return false;
        }
        for (ProcessingContext<?> processingContext : contexts) {
            if (processingContext == null || processingContext.getErrors() == null) {
                continue;
            }
            for (Exception error : processingContext.getErrors()) {
                Throwable cause = error;
                for (int depth = 0; cause != null && depth < 10; depth++, cause = cause.getCause()) {
                    // Only "the managed object is gone". Any other 404 (a group, an attachment, ...) is a
                    // different problem, and retrying would resend requests that already succeeded.
                    if (cause.getMessage() != null && cause.getMessage().contains("No managedObject for id")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void clearMockCaches(String tenant) {
        try {
            c8YAgent.clearMockIdentityCache(tenant);
            c8YAgent.clearMockInventoryCache(tenant);
        } catch (Exception e) {
            // Never let cleanup mask the test result (or turn a result into an error).
            log.warn("{} - Failed to clear mock caches: {}", tenant, e.getMessage());
        }
    }

    /**
     * Create a test notification for outbound mapping testing.
     * 
     * @param tenant          The tenant identifier
     * @param connectorClient The connector client (for metadata)
     * @param api             The API type for the notification
     * @param send            Whether to actually send the payload to C8Y
     * @param payload         The JSON payload as a string
     * @return A test Notification object
     */
    private Notification createTestNotification(String tenant, AConnectorClient connectorClient,
            API api, Boolean send, String payload) {

        // Create notification headers
        List<String> notificationHeaders = new ArrayList<>();

        // Header format: /{tenant}/{api}/{operation}
        // Example: /t12345/alarms/CREATE

        // First header: tenant and API path
        String apiPath = APITopicUtil.convertAPIToResource(api);
        String tenantApiHeader = String.format("/%s/%s", tenant, apiPath);
        notificationHeaders.add(tenantApiHeader);

        // Second header: operation (defaulting to CREATE for testing)
        String operation = "CREATE";
        notificationHeaders.add(operation);

        // Optional: Add additional headers if needed for testing
        // For example, subscription ID or message ID
        notificationHeaders.add("subscription-test-" + System.currentTimeMillis());

        // Create ack header (format: /{tenant}/{subscription-id}/{message-id})
        String ackHeader = String.format("/%s/test-subscription/%d",
                tenant,
                System.currentTimeMillis());

        log.debug("{} - Creating test notification: api={}, operation={}, send={}",
                tenant, api, operation, send);

        // Return new Notification using the private constructor via reflection-like
        // approach
        // Since Notification has a private constructor, we need to use the parse method
        // or create a similar structure

        // Build the raw notification message format that parse() expects
        StringBuilder rawNotification = new StringBuilder();
        rawNotification.append(ackHeader).append('\n');
        for (String header : notificationHeaders) {
            rawNotification.append(header).append('\n');
        }
        rawNotification.append('\n'); // Empty line separates headers from body
        rawNotification.append(payload);

        // Parse to create proper Notification object
        Notification notification = Notification.parse(rawNotification.toString());

        log.trace("{} - Test notification created with {} headers",
                tenant, notificationHeaders.size());

        return notification;
    }


    /**
     * Runs the mapping in dry-run mode (testing=true, sendPayload=false) and returns
     * the externalId that the substitution actually produces at runtime. Returns null
     * if the dry-run fails or produces no externalId. A timeout or interruption is propagated
     * so the caller reports it instead of falling back and performing the real send.
     */
    private String resolveExternalIdViaDryRun(String tenant, AConnectorClient connectorClient,
            Mapping mapping, String payload) throws InterruptedException, TimeoutException {
        try {
            ConnectorMessage dryRunMessage = createTestMessage(tenant, connectorClient, mapping,
                    mapping.getMappingTopicSample(), false, payload);
            ProcessingResultWrapper<?> dryRunWrapper = connectorClient.getDispatcher()
                    .onTestMessage(dryRunMessage, mapping);
            if (dryRunWrapper == null || dryRunWrapper.getProcessingResult() == null) {
                return null;
            }
            var dryRunContexts = awaitContexts(dryRunWrapper);
            if (dryRunContexts != null && !dryRunContexts.isEmpty()) {
                return dryRunContexts.get(0).getExternalId();
            }
        } catch (InterruptedException | TimeoutException e) {
            throw e;
        } catch (Exception e) {
            log.warn("{} - Dry-run for externalId resolution failed, falling back to static extraction: {}",
                    tenant, e.getMessage());
        }
        return null;
    }

    /**
     * Extracts the externalId from the mapping's source template at path _IDENTITY_.externalId.
     * This is the value the identity resolver will look up in C8Y when processing the test message.
     * Returns null if absent or not parseable.
     */
    private String extractIdentityFromSourceTemplate(String sourceTemplate) {
        if (sourceTemplate == null || sourceTemplate.isEmpty()) {
            return null;
        }
        try {
            JsonNode root = new ObjectMapper().readTree(sourceTemplate);
            JsonNode extId = root.path("_IDENTITY_").path("externalId");
            if (!extId.isMissingNode() && !extId.isNull() && extId.isTextual()) {
                return extId.asText();
            }
        } catch (Exception e) {
            log.warn("Could not extract identity from source template: {}", e.getMessage());
        }
        return null;
    }

    private ConnectorMessage createTestMessage(String tenant, AConnectorClient connectorClient, Mapping mapping,
            String topic, boolean sendPayload, String payloadMessage) {
        return ConnectorMessage.builder()
                .tenant(tenant)
                .topic(topic)
                .sendPayload(sendPayload)
                .testRun(true)
                .connectorIdentifier(connectorClient.getConnectorIdentifier())
                .payload(testPayloadBytes(mapping, payloadMessage))
                .build();
    }

    /**
     * The bytes a test message carries. A SparkPlug B mapping receives protobuf from the broker, which
     * cannot be typed into the test tab, so a payload written as a JSON object is encoded as SparkPlug B
     * protobuf first (the same encoder the outbound NCMD/DCMD path uses). Anything else — including text
     * that is not a JSON object — is passed through unchanged, so a payload that is already binary or
     * invalid still reaches the decoder and fails there with its own message.
     */
    private byte[] testPayloadBytes(Mapping mapping, String payloadMessage) {
        if (MappingType.SPARKPLUGB.equals(mapping.getMappingType()) && payloadMessage != null
                && payloadMessage.stripLeading().startsWith("{")) {
            try {
                return sparkPlugBSerializer.serializeJson(payloadMessage);
            } catch (IllegalArgumentException e) {
                log.warn("Test payload for SparkPlug B mapping '{}' is not valid SparkPlug B JSON, sending as is: {}",
                        mapping.getName(), e.getMessage());
            }
        }
        if (MappingType.ANY_PAYLOAD.equals(mapping.getMappingType()) && payloadMessage != null) {
            // The test tab holds binary as base64 text; the live flow base64-encodes the broker bytes
            // for the Smart Function, so decode here or the function would see it encoded twice.
            // Text that is not valid base64 is passed through unchanged.
            try {
                return Base64.getDecoder().decode(payloadMessage.strip());
            } catch (IllegalArgumentException e) {
                log.debug("Test payload for ANY_PAYLOAD mapping '{}' is not base64, sending as is", mapping.getName());
            }
        }
        return payloadMessage.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }


    /**
     * One readable line per error: its message, plus the messages of its causes that add
     * information (a wrapper often repeats its cause, so identical text is not repeated).
     * Stops at the root cause. Only the first differing cause used to be appended.
     */
    public static String describeError(Throwable error) {
        StringBuilder msg = new StringBuilder(error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName());
        Throwable cause = error.getCause();
        int depth = 0;
        while (cause != null && cause != cause.getCause() && depth++ < 5) {
            String causeMessage = cause.getMessage();
            if (causeMessage != null && !msg.toString().contains(causeMessage)) {
                msg.append(" (caused by: ").append(causeMessage).append(")");
            }
            cause = cause.getCause();
        }
        return msg.toString();
    }
}
