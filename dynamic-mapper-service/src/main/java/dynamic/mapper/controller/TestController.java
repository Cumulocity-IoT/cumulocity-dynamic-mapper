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

package dynamic.mapper.controller;

import com.cumulocity.model.ID;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import jakarta.servlet.http.HttpServletRequest;
import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.connector.core.registry.ConnectorRegistryException;
import dynamic.mapper.connector.test.TestClient;
import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.model.API;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.TestContext;
import dynamic.mapper.model.TestResult;
import dynamic.mapper.processor.runtime.ProcessingContext;
import dynamic.mapper.processor.runtime.ProcessingResultWrapper;
import dynamic.mapper.processor.util.APITopicUtil;
import dynamic.mapper.notification.websocket.Notification;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.cumulocity.microservice.context.ContextService;
import com.cumulocity.microservice.context.credentials.UserCredentials;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
@RestController
@Tag(name = "Test Controller", description = "Endpoints for testing mappings and webhook echo utilities")
public class TestController {

    private final ConnectorRegistry connectorRegistry;
    private final C8YAgent c8YAgent;
    private final ContextService<UserCredentials> contextService;

    @Value("${APP.externalExtensionsEnabled}")
    private Boolean externalExtensionsEnabled;


    @Operation(summary = "Test a mapping", description = "Executes a mapping transformation against a provided payload and returns the generated requests and any warnings or errors. Optionally sends the result to Cumulocity IoT.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Test executed successfully",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = TestResult.class))),
            @ApiResponse(responseCode = "400", description = "Invalid mapping or payload"),
            @ApiResponse(responseCode = "404", description = "Test connector not found"),
            @ApiResponse(responseCode = "500", description = "Test execution failed")
    })
    // Same roles as creating or updating a mapping: the caller supplies a complete Mapping in the
    // request body, and with send=true the result is dispatched to Cumulocity for real (for inbound
    // mappings against a test device, which is created if missing) using the microservice's own elevated
    // credentials. Without this, any authenticated tenant user could write measurements, events,
    // alarms and devices without holding any Dynamic Mapper role.
    @PreAuthorize("hasAnyRole('ROLE_DYNAMIC_MAPPER_ADMIN', 'ROLE_DYNAMIC_MAPPER_CREATE')")
    @RequestMapping(value = "/test/mapping", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TestResult> testMapping(
            @RequestBody TestContext context) {
        TestResult result = new TestResult();
        Mapping mapping = context.getMapping();
        String payload = context.getPayload();
        // @Valid is not enforced on the body, so check what the rest of the method dereferences: a missing
        // field used to surface as a NullPointerException (a 500, or a 400 without a message).
        if (mapping == null || mapping.getDirection() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A test needs a mapping with a direction.");
        }
        if (payload == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A test needs a payload.");
        }
        Boolean send = Boolean.TRUE.equals(context.getSend()); // absent means Dry Run
        String tenant = contextService.getContext().getTenant();
        log.info("{} - Test mapping: {}, {}, {}, {}", tenant, mapping.getName(), mapping.getId(), send,
                payload);

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
                ConnectorMessage testMessage = createTestMessage(tenant, connectorClient,
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
                processingResultWrapper = connectorRegistry.getDispatcher(tenant, TestClient.TEST_CONNECTOR_IDENTIFIER)
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
                boolean staleTestDevice = false;
                if (liveTestIdentity != null && containsDeviceNotFound(processingResult)) {
                    log.warn("{} - Live test hit a deleted test device ({}), evicting the cached ID and retrying once",
                            tenant, liveTestIdentity.getValue());
                    c8YAgent.evictExternalIdFromCaches(tenant, liveTestIdentity);
                    C8YAgent.TestDevice recreated = c8YAgent.ensureTestDevice(tenant, liveTestIdentity.getValue(),
                            liveTestIdentity.getValue(), liveTestIdentity.getType());
                    if (recreated != null) {
                        result.setTestDeviceId(recreated.id());
                        result.setTestDeviceCreated(recreated.created());
                        ConnectorMessage retryMessage = createTestMessage(tenant, connectorClient,
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
                    if (processingResult.size() > 1) {
                        log.warn("{} - Test mapping produced {} result(s), only returning the first result", tenant,
                                processingResult.size());
                    }
                    var firstResult = processingResult.get(0);
                    result.setRequests(firstResult.getRequests());
                    result.setWarnings(firstResult.getWarnings());
                    result.setLogs(firstResult.getLogs());
                    result.setKey(firstResult.getKey());
                    // Errors of every result, not just the first: only the first result's requests
                    // are returned, but a failure in a later one must not make the test look green.
                    for (var processingContext : processingResult) {
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
                    if (staleTestDevice) {
                        result.getErrors().add("The test device '" + liveTestIdentity.getValue() + "' no longer "
                                + "exists but its ID is still cached, and re-creating it did not help. Clear the "
                                + "Inbound ID cache (Monitoring > Cache statistic) and run the Live Test again.");
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
            return new ResponseEntity<>(result, HttpStatus.OK);

        } catch (ConnectorRegistryException e) {
            log.error("{} - Connector not found for tenant: {}", tenant, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Connector not found: " + e.getMessage());
        } catch (TimeoutException e) {
            log.error("{} - Test timed out: {}", tenant, e.getMessage());
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,
                    "The test did not finish in time and was cancelled. Check the connectivity to Cumulocity "
                            + "and the mapping for long-running code.");
        } catch (InterruptedException e) {
            log.error("{} - Test interrupted: {}", tenant, e.getMessage(), e);
            Thread.currentThread().interrupt(); // Restore interrupt status
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Test execution interrupted");
        } catch (ExecutionException e) {
            log.error("{} - Error executing test: {}", tenant, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Test execution failed: " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
        } catch (Exception ex) {
            log.error("{} - Error transforming payload: {}", tenant, ex.getMessage(), ex);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getLocalizedMessage());
        } finally {
            // Leave nothing behind, whatever the outcome: devices created in the mock by this run
            // must not be found by the next one, and the mock should not hold memory between runs.
            clearMockCaches(tenant);
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
    private ResponseEntity<TestResult> liveTestFailure(TestResult result, String tenant, String message) {
        log.warn("{} - {}", tenant, message);
        result.setSuccess(false);
        result.getErrors().add(message);
        return new ResponseEntity<>(result, HttpStatus.OK);
    }

    /** True if any context failed because a managed object (a cached, since deleted device) is gone. */
    static boolean containsDeviceNotFound(List<? extends ProcessingContext<?>> contexts) {
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

    @Operation(summary = "Echo webhook input", description = "Accepts any POST request and returns the body unchanged. Useful for testing outbound webhook mappings.")
    @ApiResponse(responseCode = "200", description = "Input echoed back", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE))
    @PostMapping(value = "/webhook/echo/**", produces = MediaType.TEXT_PLAIN_VALUE)
    public String echoInput(HttpServletRequest request, @RequestBody String input) {
        // Get the full URL path
        String fullPath = request.getRequestURI();

        // Get query parameters if any
        String queryString = request.getQueryString();

        // Log path and input
        if (queryString != null) {
            log.info("Received request at path: {} with query parameters: {}", fullPath, queryString);
        } else {
            log.info("Received request at path: {}", fullPath);
        }
        // debug, not info: this endpoint echoes whatever is posted to it, so the body is arbitrary
        // third-party content that should not land in the tenant's log by default.
        log.debug("Received body: {}", input);

        return input;
    }

    @Operation(summary = "Webhook health check", description = "Returns 200 OK to confirm the webhook echo endpoint is reachable.")
    @ApiResponse(responseCode = "200", description = "Endpoint is healthy")
    @GetMapping("/webhook")
    public ResponseEntity<String> echoHealth(HttpServletRequest request) {
        // Get the full URL
        String url = request.getRequestURL().toString();

        // Get query string
        String queryString = request.getQueryString();

        // Log the full path with query parameters
        if (queryString != null) {
            log.info("Received request: {} with query parameters: {}", url, queryString);
        } else {
            log.info("Received request: {}", url);
        }

        // Return 200 OK with empty body
        return ResponseEntity.ok().build();
    }

    /**
     * Runs the mapping in dry-run mode (testing=true, sendPayload=false) and returns
     * the externalId that the substitution actually produces at runtime. Returns null
     * if the dry-run fails or produces no externalId.
     */
    private String resolveExternalIdViaDryRun(String tenant, AConnectorClient connectorClient,
            Mapping mapping, String payload) {
        try {
            ConnectorMessage dryRunMessage = createTestMessage(tenant, connectorClient,
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("{} - Dry-run for externalId resolution interrupted", tenant);
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

    private ConnectorMessage createTestMessage(String tenant, AConnectorClient connectorClient, String topic,
            boolean sendPayload,
            String payloadMessage) {
        return ConnectorMessage.builder()
                .tenant(tenant)
                .topic(topic)
                .sendPayload(sendPayload)
                .testRun(true)
                .connectorIdentifier(connectorClient.getConnectorIdentifier())
                .payload(payloadMessage.getBytes())
                .build();
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
