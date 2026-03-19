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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import jakarta.servlet.http.HttpServletRequest;
import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.connector.core.registry.ConnectorRegistryException;
import dynamic.mapper.connector.test.TestClient;
import dynamic.mapper.core.BootstrapService;
import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.model.API;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.TestContext;
import dynamic.mapper.model.TestResult;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.ProcessingResultWrapper;
import dynamic.mapper.processor.util.APITopicUtil;
import dynamic.mapper.service.ConnectorConfigurationService;
import dynamic.mapper.service.MappingService;
import dynamic.mapper.service.ServiceConfigurationService;
import dynamic.mapper.notification.websocket.Notification;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.cumulocity.microservice.context.ContextService;
import com.cumulocity.microservice.context.credentials.UserCredentials;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
public class TestController {

    @Autowired
    ConnectorRegistry connectorRegistry;

    @Autowired
    MappingService mappingService;

    @Autowired
    ConnectorConfigurationService connectorConfigurationService;

    @Autowired
    ServiceConfigurationService serviceConfigurationService;

    @Autowired
    BootstrapService bootstrapService;

    @Autowired
    C8YAgent c8YAgent;

    @Autowired
    private ContextService<UserCredentials> contextService;

    @Value("${APP.externalExtensionsEnabled}")
    private Boolean externalExtensionsEnabled;


    @RequestMapping(value = "/test/mapping", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TestResult> testMapping(
            @RequestBody TestContext context) {
        TestResult result = new TestResult();
        Mapping mapping = context.getMapping();
        String payload = context.getPayload();
        Boolean send = context.getSend();
        String tenant = contextService.getContext().getTenant();
        log.info("{} - Test mapping: {}, {}, {}, {}", tenant, mapping.getName(), mapping.getId(), send,
                payload);
        try {
            // Create test device in C8Y inventory before dispatching (INBOUND + send only)
            if (Boolean.TRUE.equals(send) && Boolean.TRUE.equals(context.getCreateTestDevice())
                    && mapping.getDirection().equals(Direction.INBOUND)) {
                String externalId = extractIdentityFromSourceTemplate(mapping.getSourceTemplate());
                if (externalId == null) {
                    externalId = deriveExternalIdFromTopic(mapping.getMappingTopicSample());
                }
                String externalIdType = (mapping.getExternalIdType() != null && !mapping.getExternalIdType().isEmpty())
                        ? mapping.getExternalIdType()
                        : "c8y_Serial";
                String deviceName = externalId;
                String testDeviceId = c8YAgent.createTestDevice(tenant, deviceName, externalId, externalIdType);
                if (testDeviceId != null) {
                    result.setTestDeviceId(testDeviceId);
                    log.info("{} - Created test device: id={}, externalId={}, type={}", tenant, testDeviceId,
                            externalId, externalIdType);
                }
            }

            AConnectorClient connectorClient = connectorRegistry
                    .getClientForTenant(tenant, TestClient.TEST_CONNECTOR_IDENTIFIER);

            ProcessingResultWrapper<?> processingResultWrapper = null;
            if (mapping.getDirection().equals(Direction.INBOUND)) {
                ConnectorMessage testMessage = createTestMessage(tenant, connectorClient,
                        mapping.getMappingTopicSample(), send, payload);
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
                var processingResult = (List<? extends ProcessingContext<?>>) processingResultWrapper
                        .getProcessingResult().get();

                if (processingResult != null && processingResult.size() > 1) {
                    log.warn("{} - Test mapping produced {} result(s), only returning the first result", tenant,
                            processingResult.size());
                } else if (processingResult != null && processingResult.size() == 1) {
                    var firstResult = processingResult.get(0);
                    result.setRequests(firstResult.getRequests());
                    result.setWarnings(firstResult.getWarnings());
                    result.setLogs(firstResult.getLogs());
                    result.setSuccess(firstResult.getErrors().isEmpty());
                    if (firstResult.getErrors() != null && !firstResult.getErrors().isEmpty()) {
                        firstResult.getErrors().forEach(e -> {
                            result.getErrors().add(e.getMessage() != null ? e.getMessage() : "No message");
                        });
                    }
                }
            }
            return new ResponseEntity<>(result, HttpStatus.OK);

        } catch (ConnectorRegistryException e) {
            log.error("{} - Connector not found for tenant: {}", tenant, e.getMessage());
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Connector not found: " + e.getMessage());
        } catch (InterruptedException e) {
            log.error("{} - Test interrupted: {}", tenant, e.getMessage());
            Thread.currentThread().interrupt(); // Restore interrupt status
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Test execution interrupted");
        } catch (ExecutionException e) {
            log.error("{} - Error executing test: {}", tenant, e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Test execution failed: " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
        } catch (Exception ex) {
            log.error("{} - Error transforming payload: {}", tenant, ex.getMessage(), ex);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getLocalizedMessage());
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

    @PostMapping("/webhook/echo/**")
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
        log.info("Received body: {}", input);

        return input;
    }

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

    /**
     * Derives an externalId candidate from a mapping topic sample by taking the last non-empty segment.
     * E.g. "testSmartInbound/sensor-berlin-01" → "sensor-berlin-01"
     */
    private String deriveExternalIdFromTopic(String topicSample) {
        if (topicSample == null || topicSample.isEmpty()) {
            return "test-device";
        }
        String[] segments = topicSample.split("/");
        for (int i = segments.length - 1; i >= 0; i--) {
            if (!segments[i].isEmpty() && !segments[i].equals("#") && !segments[i].equals("+")) {
                return segments[i];
            }
        }
        return "test-device";
    }

    private ConnectorMessage createTestMessage(String tenant, AConnectorClient connectorClient, String topic,
            boolean sendPayload,
            String payloadMessage) {
        return ConnectorMessage.builder()
                .tenant(tenant)
                .topic(topic)
                .sendPayload(sendPayload)
                .connectorIdentifier(connectorClient.getConnectorIdentifier())
                .payload(payloadMessage.getBytes())
                .build();
    }

}