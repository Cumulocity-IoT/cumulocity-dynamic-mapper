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
package dynamic.mapper.processor.outbound;

import static com.dashjoin.jsonata.Jsonata.jsonata;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.support.DefaultExchange;

import com.dashjoin.jsonata.json.Json;
import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.model.API;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.model.Qos;
import dynamic.mapper.notification.NotificationSubscriber;
import dynamic.mapper.notification.websocket.Notification;
import dynamic.mapper.notification.websocket.NotificationCallback;
import dynamic.mapper.processor.model.C8YMessage;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.ProcessingResultWrapper;
import dynamic.mapper.service.MappingService;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CamelDispatcherOutbound implements NotificationCallback {

    @Getter
    private AConnectorClient connectorClient;
    private ExecutorService virtualThreadPool;
    private NotificationSubscriber notificationSubscriber;
    private MappingService mappingService;
    private ConfigurationRegistry configurationRegistry;
    private ProducerTemplate producerTemplate;
    private CamelContext camelContext;

    /**
     * Constructor matching DispatcherInbound signature
     */
    public CamelDispatcherOutbound(ConfigurationRegistry configurationRegistry,
            AConnectorClient connectorClient) {
        this.mappingService = configurationRegistry.getMappingService();
        this.virtualThreadPool = configurationRegistry.getVirtualThreadPool();
        this.connectorClient = connectorClient;
        this.configurationRegistry = configurationRegistry;
        this.notificationSubscriber = configurationRegistry.getNotificationSubscriber();

        // Initialize Camel components
        this.camelContext = configurationRegistry.getCamelContext();
        this.producerTemplate = camelContext.createProducerTemplate();
    }

    @Override
    public void onOpen(URI serverUri) {
        log.info("{} - Phase IV: Notification 2.0 connected over WebSocket, linked to connector: {}",
                connectorClient.getTenant(), connectorClient.getConnectorName());
        notificationSubscriber.setDeviceConnectionStatus(connectorClient.getTenant(), 200);
    }

    @Override
    public ProcessingResultWrapper<?> onNotification(Notification notification) {
        return processNotification(notification, null);
    }

    @Override
    public void onError(Throwable t) {
        log.error("{} - We got an exception: ", connectorClient.getTenant(), t);
    }

    @Override
    public void onClose(int statusCode, String reason) {
        log.info("{} - WebSocket connection closed", connectorClient.getTenant());
        if (reason.contains("401"))
            notificationSubscriber.setDeviceConnectionStatus(connectorClient.getTenant(), 401);
        else
            notificationSubscriber.setDeviceConnectionStatus(connectorClient.getTenant(), null);
    }

    @Override
    public ProcessingResultWrapper<?> onTestNotification(Notification notification, Mapping mapping) {
        return processNotification(notification, mapping);
    }

    /**
     * Process notification with optional test mapping
     */
    private ProcessingResultWrapper<?> processNotification(Notification notification, Mapping testMapping) {
        boolean testing = testMapping != null;
        
        Qos consolidatedQos = Qos.AT_LEAST_ONCE;
        ProcessingResultWrapper<?> result = ProcessingResultWrapper.builder()
                .consolidatedQos(consolidatedQos)
                .build();

        // Extract tenant from notification
        String tenant = getTenantFromNotificationHeaders(notification.getNotificationHeaders());

        // Check connector connection status (skip for testing)
        if (!testing && !connectorClient.isConnected()) {
            log.warn("{} - Notification message received but connector {} is not connected. Ignoring message..",
                    tenant, connectorClient.getConnectorName());
            return result;
        }

        // Filter operations - only process CREATE and UPDATE
        if (!("CREATE".equals(notification.getOperation()) || "UPDATE".equals(notification.getOperation()))) {
            log.debug("{} - Ignoring notification with operation: {}", tenant, notification.getOperation());
            return result;
        }

        // Skip UPDATE operations for OPERATION API (unless testing)
        if (!testing && "UPDATE".equals(notification.getOperation()) && notification.getApi().equals(API.OPERATION)) {
            log.info("{} - Update Operation message for connector: {} is received, ignoring it",
                    tenant, connectorClient.getConnectorName());
            return result;
        }

        // Convert Notification to C8YMessage
        C8YMessage c8yMessage = convertNotificationToC8YMessage(notification, tenant, !testing);
        
        // Process the message
        return processMessage(c8yMessage, testMapping);
    }

    /**
     * Convert Notification to C8YMessage
     */
    private C8YMessage convertNotificationToC8YMessage(Notification notification, String tenant, boolean sendPayload) {
        C8YMessage c8yMessage = new C8YMessage();
        
        // Parse payload
        Map parsedPayload = (Map) Json.parseJson(notification.getMessage());
        c8yMessage.setParsedPayload(parsedPayload);
        
        // Set API and operation
        c8yMessage.setApi(notification.getApi());
        c8yMessage.setOperation(notification.getOperation());
        
        // Extract message ID
        String messageId = String.valueOf(parsedPayload.get("id"));
        c8yMessage.setMessageId(messageId);
        
        // Extract source ID
        try {
            var expression = jsonata(notification.getApi().identifier);
            Object sourceIdResult = expression.evaluate(parsedPayload);
            String sourceId = (sourceIdResult instanceof String) ? (String) sourceIdResult : null;
            c8yMessage.setSourceId(sourceId);
        } catch (Exception e) {
            log.debug("{} - Could not extract source.id: {}", tenant, e.getMessage());
        }
        
        // Set payload and tenant
        c8yMessage.setPayload(notification.getMessage());
        c8yMessage.setTenant(tenant);
        c8yMessage.setSendPayload(sendPayload);
        
        return c8yMessage;
    }

    /**
     * Process C8Y message using Camel routes
     */
    private ProcessingResultWrapper<?> processMessage(C8YMessage c8yMessage, Mapping testMapping) {
        boolean testing = testMapping != null;
        String tenant = c8yMessage.getTenant();
        ServiceConfiguration serviceConfiguration = configurationRegistry.getServiceConfiguration(tenant);

        // Log incoming message if configured
        if (serviceConfiguration.getLogPayload()) {
            log.info("{} - PROCESSING: C8Y message, API: {}, device: {}, connector: {}, message id: {}",
                    tenant,
                    c8yMessage.getApi(), 
                    c8yMessage.getSourceId(),
                    connectorClient.getConnectorName(),
                    c8yMessage.getMessageId());
        }

        Qos consolidatedQos = Qos.AT_LEAST_ONCE;
        ProcessingResultWrapper<?> result = ProcessingResultWrapper.builder()
                .consolidatedQos(consolidatedQos)
                .build();

        // Validate payload
        if (c8yMessage.getPayload() == null) {
            log.warn("{} - C8Y message has null payload, skipping processing", tenant);
            return result;
        }

        // Declare final variables for use in lambda
        List<Mapping> resolvedMappings;
        int maxCPUTime;

        try {
            // Resolve mappings
            if (testMapping != null) {
                resolvedMappings = new ArrayList<>();
                resolvedMappings.add(testMapping);
            } else {
                resolvedMappings = mappingService.resolveMappingOutbound(tenant, c8yMessage, serviceConfiguration);
            }

            // Determine consolidated QoS
            consolidatedQos = connectorClient.determineMaxQosOutbound(resolvedMappings);
            result.setConsolidatedQos(consolidatedQos);

            // Set max CPU time if code-based mappings exist
            int tempMaxCPUTime = 0;
            for (Mapping mapping : resolvedMappings) {
                if (mapping.isTransformationAsCode()) {
                    tempMaxCPUTime = serviceConfiguration.getMaxCPUTimeMS();
                    break;
                }
            }
            maxCPUTime = tempMaxCPUTime; // Now final
            result.setMaxCPUTimeMS(maxCPUTime);

        } catch (Exception e) {
            log.warn("{} - Error resolving appropriate mapping for C8Y message. Could NOT be parsed. Ignoring this message!",
                    tenant);
            log.debug(e.getMessage(), e);
            
            // Update unspecified mapping status
            MappingStatus mappingStatusUnspecified = mappingService.getMappingStatus(tenant, Mapping.UNSPECIFIED_MAPPING);
            if (mappingStatusUnspecified != null) {
                mappingStatusUnspecified.errors++;
            }
            
            return result;
        }

        // Process using Camel routes asynchronously
        Future<List<ProcessingContext<Object>>> futureProcessingResult = virtualThreadPool.submit(() -> {
            try {
                Exchange exchange = createExchange(c8yMessage, resolvedMappings, testing);
                Exchange resultExchange = producerTemplate.send("direct:processOutboundMessage", exchange);

                @SuppressWarnings("unchecked")
                List<ProcessingContext<Object>> contexts = resultExchange.getIn().getHeader("processedContexts",
                        List.class);
                
                return contexts != null ? contexts : new ArrayList<>();

            } catch (Exception e) {
                log.error("{} - Error processing outbound message through Camel routes: {}", tenant, e.getMessage(), e);
                throw new RuntimeException("Camel processing failed", e);
            }
        });

        result.setProcessingResult((Future) futureProcessingResult);
        return result;
    }

    /**
     * Create Camel Exchange from C8YMessage and resolved mappings
     */
    private Exchange createExchange(C8YMessage message, List<Mapping> resolvedMappings, boolean testing) {
        Exchange exchange = new DefaultExchange(camelContext);
        Message camelMessage = exchange.getIn();

        // Set the C8YMessage as the body
        camelMessage.setBody(message);

        // Set headers for processing
        camelMessage.setHeader("connectorIdentifier", connectorClient.getConnectorIdentifier());
        camelMessage.setHeader("tenant", message.getTenant());
        camelMessage.setHeader("source", message.getSourceId());
        camelMessage.setHeader("testing", testing);
        camelMessage.setHeader("mappings", resolvedMappings);
        camelMessage.setHeader("c8yMessage", message);
        camelMessage.setHeader("serviceConfiguration",
                configurationRegistry.getServiceConfiguration(message.getTenant()));

        // Set payload information
        camelMessage.setHeader("payloadBytes", message.getPayload());
        if (message.getPayload() != null) {
            camelMessage.setHeader("payloadString", new String(message.getPayload()));
        }

        return exchange;
    }

    /**
     * Extract tenant from notification headers
     */
    public String getTenantFromNotificationHeaders(List<String> notificationHeaders) {
        if (notificationHeaders != null && !notificationHeaders.isEmpty()) {
            String firstHeader = notificationHeaders.get(0);
            String[] parts = firstHeader.split("/");
            if (parts.length > 1) {
                return parts[1];
            }
        }
        return connectorClient.getTenant(); // Fallback to connector's tenant
    }
}