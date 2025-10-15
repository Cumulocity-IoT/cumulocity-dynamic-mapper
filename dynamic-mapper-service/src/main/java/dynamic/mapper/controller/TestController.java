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

import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.connector.core.registry.ConnectorRegistryException;
import dynamic.mapper.connector.test.TestClient;
import dynamic.mapper.core.*;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.TestContext;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.ProcessingResult;
import dynamic.mapper.service.ConnectorConfigurationService;
import dynamic.mapper.service.MappingService;
import dynamic.mapper.service.ServiceConfigurationService;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.cumulocity.microservice.context.ContextService;
import com.cumulocity.microservice.context.credentials.UserCredentials;
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
    private boolean externalExtensionsEnabled;

    @RequestMapping(value = "/test/payload", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<? extends ProcessingContext<?>>> testPayload(@RequestParam boolean send,
            @RequestParam String topic, @RequestParam String connectorIdentifier,
            @Valid @RequestBody Map<String, Object> payload) {
        List<? extends ProcessingContext<?>> result = null;
        String tenant = contextService.getContext().getTenant();
        log.info("{} - Test payload: {}, {}, {}", tenant, topic, send,
                payload);
        try {
            try {
                AConnectorClient connectorClient = connectorRegistry
                        .getClientForTenant(tenant, connectorIdentifier);
                String payloadMessage = new ObjectMapper().writeValueAsString(payload);
                ConnectorMessage testMessage = createTestMessage(tenant, connectorClient, topic, send, payloadMessage);
                ProcessingResult<?> processingResult = connectorClient.getDispatcher().onMessage(testMessage);
                if (processingResult.getProcessingResult() != null) {
                    // Wait for the future to complete and get the result
                    result = (List<? extends ProcessingContext<?>>) processingResult.getProcessingResult().get();
                }
            } catch (ConnectorRegistryException e) {
                throw new RuntimeException(e);
            }
            return new ResponseEntity<>(result, HttpStatus.OK);
        } catch (Exception ex) {
            log.error("{} - Error transforming payload: {}", tenant, ex);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getLocalizedMessage());
        }
    }

    @RequestMapping(value = "/test/mapping", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<? extends ProcessingContext<?>>> testMapping(@RequestParam boolean send,
            @RequestBody TestContext context) {
        List<? extends ProcessingContext<?>> result = null;
        Mapping mapping = context.getMapping();
        String payload = context.getPayload();
        String tenant = contextService.getContext().getTenant();
        log.info("{} - Test mapping: {}, {}, {}", tenant, mapping, send,
                payload);
        try {
            try {
                AConnectorClient connectorClient = connectorRegistry
                        .getClientForTenant(tenant, TestClient.TEST_CONNECTOR_IDENTIFIER);
                // String payloadMessage = new ObjectMapper().writeValueAsString(payload);
                ConnectorMessage testMessage = createTestMessage(tenant, connectorClient,
                        mapping.getMappingTopicSample(), send, payload);
                ProcessingResult<?> processingResult = connectorClient.getDispatcher().onTestMessage(testMessage,
                        mapping);
                if (processingResult.getProcessingResult() != null) {
                    // Wait for the future to complete and get the result
                    result = (List<? extends ProcessingContext<?>>) processingResult.getProcessingResult().get();
                    result.forEach(r -> {
                        // Reset clientId for test results
                        r.setFlowContext(null);
                        r.setGraalContext(null);
                        // Clear processing cache for test results
                        r.getProcessingCache().clear();
                        // r.setMapping(null);
                        r.getMapping().setCode(null);
                        r.getMapping().setSnoopedTemplates(null);
                        r.setServiceConfiguration(null);
                    });
                }
            } catch (ConnectorRegistryException e) {
                throw new RuntimeException(e);
            }
            return new ResponseEntity<>(result, HttpStatus.OK);
        } catch (Exception ex) {
            log.error("{} - Error transforming payload: {}", tenant, ex);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getLocalizedMessage());
        }
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

    private ConnectorMessage createTestMessage(String tenant, AConnectorClient connectorClient, String topic,
            boolean sendPayload,
            String payloadMessage) {
        return ConnectorMessage.builder()
                .tenant(tenant)
                .supportsMessageContext(connectorClient.getSupportsMessageContext())
                .topic(topic)
                .sendPayload(sendPayload)
                .connectorIdentifier(connectorClient.getConnectorIdentifier())
                .payload(payloadMessage.getBytes())
                .build();
    }
}