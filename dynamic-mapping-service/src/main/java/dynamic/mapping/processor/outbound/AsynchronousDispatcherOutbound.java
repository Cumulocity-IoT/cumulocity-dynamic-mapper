/*
 * Copyright (c) 2022 Software AG, Darmstadt, Germany and/or Software AG USA Inc., Reston, VA, USA,
 * and/or its subsidiaries and/or its affiliates and/or their licensors.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @authors Christof Strack, Stefan Witschel
 */

package dynamic.mapping.processor.outbound;

import com.cumulocity.model.JSONBase;
import com.cumulocity.model.operation.OperationStatus;
import com.cumulocity.rest.representation.operation.OperationRepresentation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dynamic.mapping.connector.core.client.AConnectorClient;
import dynamic.mapping.model.Mapping;
import dynamic.mapping.model.MappingStatus;
import dynamic.mapping.notification.websocket.NotificationCallback;
import dynamic.mapping.processor.PayloadProcessor;
import dynamic.mapping.processor.model.C8YRequest;
import dynamic.mapping.processor.model.MappingType;
import dynamic.mapping.processor.model.ProcessingContext;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import dynamic.mapping.core.C8YAgent;
import dynamic.mapping.core.MappingComponent;
import dynamic.mapping.model.API;
import dynamic.mapping.model.SnoopStatus;
import dynamic.mapping.notification.websocket.Notification;
import dynamic.mapping.processor.C8YMessage;
import org.apache.commons.codec.binary.Hex;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

@Slf4j
// @Service
// Not a service anymore, manually instantiated by the C8YSubscriber
public class AsynchronousDispatcherOutbound implements NotificationCallback {

    @Override
    public void onOpen(URI serverUri) {
        log.info("Connected to Cumulocity notification service over WebSocket " + serverUri);
        c8yAgent.getNotificationSubscriber().setDeviceConnectionStatus(connectorClient.getTenant(), 200);
    }

    @Override
    public void onNotification(Notification notification) {
        // We don't care about UPDATES nor DELETES
        if ("CREATE".equals(notification.getNotificationHeaders().get(1))) {
            String tenant = getTenantFromNotificationHeaders(notification.getNotificationHeaders());
            log.info("Tenant {} - Notification received: <{}>", tenant, notification.getMessage());
            log.info("Tenant {} - Notification headers: <{}>", tenant, notification.getNotificationHeaders());
            C8YMessage message = new C8YMessage();
            message.setPayload(notification.getMessage());
            message.setApi(notification.getApi());
            processMessage(tenant, message, true);
        }
    }

    @Override
    public void onError(Throwable t) {
        log.error("We got an exception: " + t);
    }

    @Override
    public void onClose(int statusCode, String reason) {
        log.info("Connection was closed.");
        if (reason.contains("401"))
            c8yAgent.getNotificationSubscriber().setDeviceConnectionStatus(connectorClient.getTenant(), 401);
        else
            c8yAgent.getNotificationSubscriber().setDeviceConnectionStatus(connectorClient.getTenant(), 0);
    }

    public String getTenantFromNotificationHeaders(List<String> notificationHeaders) {
        return notificationHeaders.get(0).split("/")[1];
    }

    public static class MappingProcessorOutbound<T> implements Callable<List<ProcessingContext<?>>> {

        List<Mapping> resolvedMappings;
        String topic;
        Map<MappingType, BasePayloadProcessorOutbound<T>> payloadProcessorsOutbound;
        boolean sendPayload;
        C8YMessage c8yMessage;
        MappingComponent mappingStatusComponent;
        C8YAgent c8yAgent;
        ObjectMapper objectMapper;
        String tenant;

        public MappingProcessorOutbound(List<Mapping> mappings, MappingComponent mappingStatusComponent, C8YAgent c8yAgent,
                Map<MappingType, BasePayloadProcessorOutbound<T>> payloadProcessorsOutbound, boolean sendPayload,
                C8YMessage c8yMessage, ObjectMapper objectMapper, String tenant) {
            this.resolvedMappings = mappings;
            this.mappingStatusComponent = mappingStatusComponent;
            this.c8yAgent = c8yAgent;
            this.payloadProcessorsOutbound = payloadProcessorsOutbound;
            this.sendPayload = sendPayload;
            this.c8yMessage = c8yMessage;
            this.objectMapper = objectMapper;
            this.tenant = tenant;
        }

        @Override
        public List<ProcessingContext<?>> call() throws Exception {
            List<ProcessingContext<?>> processingResult = new ArrayList<>();
            MappingStatus mappingStatusUnspecified = mappingStatusComponent
                    .getMappingStatus(tenant, Mapping.UNSPECIFIED_MAPPING);
            resolvedMappings.forEach(mapping -> {
                // only process active mappings
                if (mapping.isActive()) {
                    MappingStatus mappingStatus = mappingStatusComponent.getMappingStatus(tenant, mapping);

                    ProcessingContext<?> context;
                    if (mapping.mappingType.payloadType.equals(String.class)) {
                        context = new ProcessingContext<String>();
                    } else {
                        context = new ProcessingContext<byte[]>();
                    }
                    context.setTopic(mapping.publishTopic);
                    context.setMappingType(mapping.mappingType);
                    context.setMapping(mapping);
                    context.setSendPayload(sendPayload);
                    // identify the corect processor based on the mapping type
                    MappingType mappingType = context.getMappingType();
                    BasePayloadProcessorOutbound processor = payloadProcessorsOutbound.get(mappingType);

                    if (processor != null) {
                        try {
                            processor.deserializePayload(context, c8yMessage);
                            if (c8yAgent.getServiceConfigurations().get(tenant).logPayload) {
                                log.info("New message on topic: '{}', wrapped message: {}", context.getTopic(),
                                        context.getPayload().toString());
                            } else {
                                log.info("New message on topic: '{}'", context.getTopic());
                            }
                            mappingStatus.messagesReceived++;
                            if (mapping.snoopStatus == SnoopStatus.ENABLED
                                    || mapping.snoopStatus == SnoopStatus.STARTED) {
                                String serializedPayload = null;
                                if (context.getPayload() instanceof JsonNode) {
                                    serializedPayload = objectMapper
                                            .writeValueAsString((JsonNode) context.getPayload());
                                } else if (context.getPayload() instanceof String) {
                                    serializedPayload = (String) context.getPayload();
                                }
                                if (context.getPayload() instanceof byte[]) {
                                    serializedPayload = Hex.encodeHexString((byte[]) context.getPayload());
                                }

                                if (serializedPayload != null) {
                                    mapping.addSnoopedTemplate(serializedPayload);
                                    mappingStatus.snoopedTemplatesTotal = mapping.snoopedTemplates.size();
                                    mappingStatus.snoopedTemplatesActive++;

                                    log.debug("Adding snoopedTemplate to map: {},{},{}", mapping.subscriptionTopic,
                                            mapping.snoopedTemplates.size(),
                                            mapping.snoopStatus);
                                    mappingStatusComponent.addDirtyMapping(tenant, mapping);

                                } else {
                                    log.warn(
                                            "Message could NOT be parsed, ignoring this message, as class is not valid: {}",
                                            context.getPayload().getClass());
                                }
                            } else {
                                processor.extractFromSource(context);
                                processor.substituteInTargetAndSend(context);
                                List<C8YRequest> resultRequests = context.getRequests();
                                if (context.hasError() || resultRequests.stream().anyMatch(r -> r.hasError())) {
                                    mappingStatus.errors++;
                                }
                            }
                        } catch (Exception e) {
                            log.warn("Message could NOT be parsed, ignoring this message: {}", e.getMessage());
                            log.debug("Message Stacktrace:", e);
                            mappingStatus.errors++;
                        }
                    } else {
                        mappingStatusUnspecified.errors++;
                        log.error("No process for MessageType: {} registered, ignoring this message!", mappingType);
                    }
                    processingResult.add(context);
                }
            });
            return processingResult;
        }

    }

    @Getter
    protected AConnectorClient connectorClient;

    private PayloadProcessor payloadProcessor;

    // The Outbound Dispatcher is hardly connected to the Connector otherwise it is
    // not possible to correlate messages received bei Notification API to the
    // correct Connector
    public AsynchronousDispatcherOutbound(ObjectMapper objectMapper, C8YAgent c8YAgent,
            MappingComponent mappingComponent, ExecutorService cachedThreadPool, AConnectorClient connectorClient,
            PayloadProcessor payloadProcessor) {
        this.objectMapper = objectMapper;
        this.c8yAgent = c8YAgent;
        this.mappingComponent = mappingComponent;
        this.cachedThreadPool = cachedThreadPool;
        this.connectorClient = connectorClient;
        this.payloadProcessor = payloadProcessor;
    }

    // @Autowired
    @Setter
    protected C8YAgent c8yAgent;

    // @Autowired
    // @Setter
    // protected ConnectorRegistry connectorRegistry;

    @Setter
    protected ObjectMapper objectMapper;

    // @Autowired
    // Map<MappingType, BasePayloadProcessorOutbound<?>> payloadProcessorsOutbound;

    // @Autowired
    // @Qualifier("cachedThreadPool")
    @Setter
    private ExecutorService cachedThreadPool;

    // @Autowired
    @Setter
    MappingComponent mappingComponent;

    public Future<List<ProcessingContext<?>>> processMessage(String tenant, C8YMessage c8yMessage,
            boolean sendPayload) {
        MappingStatus mappingStatusUnspecified = mappingComponent.getMappingStatus(tenant, Mapping.UNSPECIFIED_MAPPING);
        Future<List<ProcessingContext<?>>> futureProcessingResult = null;
        List<Mapping> resolvedMappings = new ArrayList<>();

        // Handle C8Y Operation Status
        // TODO Add OperationAutoAck Status to activate/deactive
        OperationRepresentation op = null;
        //
        if (c8yMessage.getApi().equals(API.OPERATION)) {
            op = JSONBase.getJSONParser().parse(OperationRepresentation.class, c8yMessage.getPayload());
            c8yAgent.updateOperationStatus(tenant, op, OperationStatus.EXECUTING, null);
        }
        if (c8yMessage.getPayload() != null) {
            try {
                JsonNode message = objectMapper.readTree(c8yMessage.getPayload());
                resolvedMappings = mappingComponent.resolveMappingOutbound(tenant, message, c8yMessage.getApi());
            } catch (Exception e) {
                log.warn("Error resolving appropriate map. Could NOT be parsed. Ignoring this message!");
                log.debug(e.getMessage(), e);
                if (op != null)
                    c8yAgent.updateOperationStatus(tenant, op, OperationStatus.FAILED, e.getLocalizedMessage());
                mappingStatusUnspecified.errors++;
            }
        } else {
            return futureProcessingResult;
        }

        futureProcessingResult = cachedThreadPool.submit(
                new MappingProcessorOutbound(resolvedMappings, mappingComponent, c8yAgent,
                        payloadProcessor.getPayloadProcessorsOutbound(),
                        sendPayload, c8yMessage, objectMapper, tenant));

        if (op != null) {
            // Blocking for Operations to receive the processing result to update operation
            // status
            try {
                List<ProcessingContext<?>> results = futureProcessingResult.get();
                if (results.size() > 0) {
                    if (results.get(0).hasError()) {
                        c8yAgent.updateOperationStatus(tenant, op, OperationStatus.FAILED,
                                results.get(0).getErrors().toString());
                    } else {
                        c8yAgent.updateOperationStatus(tenant, op, OperationStatus.SUCCESSFUL, null);
                    }
                } else {
                    // No Mapping found
                    c8yAgent.updateOperationStatus(tenant, op, OperationStatus.FAILED,
                            "No Mapping found for operation " + op.toJSON());
                }
            } catch (InterruptedException e) {
                c8yAgent.updateOperationStatus(tenant, op, OperationStatus.FAILED, e.getLocalizedMessage());
            } catch (ExecutionException e) {
                c8yAgent.updateOperationStatus(tenant, op, OperationStatus.FAILED, e.getLocalizedMessage());
            }
        }
        return futureProcessingResult;
    }
}