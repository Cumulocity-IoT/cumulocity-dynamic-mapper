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

package dynamic.mapping.processor.outbound;

import com.cumulocity.model.JSONBase;
import com.cumulocity.model.operation.OperationStatus;
import com.cumulocity.rest.representation.operation.OperationRepresentation;
import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapping.configuration.ServiceConfiguration;
import dynamic.mapping.connector.core.client.AConnectorClient;
import dynamic.mapping.model.Mapping;
import dynamic.mapping.model.MappingStatus;
import dynamic.mapping.model.SnoopStatus;
import dynamic.mapping.notification.websocket.NotificationCallback;
import dynamic.mapping.processor.model.C8YRequest;
import dynamic.mapping.processor.model.MappingType;
import dynamic.mapping.processor.model.ProcessingContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import dynamic.mapping.core.C8YAgent;
import dynamic.mapping.core.ConfigurationRegistry;
import dynamic.mapping.core.MappingComponent;
import dynamic.mapping.model.API;
import dynamic.mapping.notification.C8YNotificationSubscriber;
import dynamic.mapping.notification.websocket.Notification;
import dynamic.mapping.processor.C8YMessage;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * AsynchronousDispatcherOutbound
 * 
 * This class implements the <code>NotificationCallback</code> which is then
 * registered as a listener in the <code>C8YNotificationSubscriber </code> when
 * new messages arrive.
 * It processes OUTBOUND messages and works asynchronously.
 * A task <code>AsynchronousDispatcherOutbound.MappingOutboundTask</code> is
 * added the ExecutorService, to not block new arriving messages.
 * The call method in
 * <code>AsynchronousDispatcherOutbound.MappingOutboundTask</code> is the core
 * of the message processing.
 * For all resolved mappings the following steps are performed for new
 * messages:
 * ** deserialize the payload
 * ** extract the content from the payload based on the defined substitution in
 * the mapping and add these to a post processing cache
 * ** substitute in the defined target template of the mapping the extracted
 * content from the cache
 * ** send the resulting target payload to connectorClient, e.g. MQTT broker
 */
@Slf4j
public class DispatcherOutbound implements NotificationCallback {

    @Getter
    protected AConnectorClient connectorClient;

    protected C8YNotificationSubscriber notificationSubscriber;

    protected C8YAgent c8yAgent;

    protected ObjectMapper objectMapper;

    protected ExecutorService virtThreadPool;

    protected MappingComponent mappingComponent;

    protected ConfigurationRegistry configurationRegistry;

    protected Map<MappingType, BaseProcessorOutbound<?>> payloadProcessorsOutbound;

    // The Outbound Dispatcher is hardly connected to the Connector otherwise it is
    // not possible to correlate messages received bei Notification API to the
    // correct Connector
    public DispatcherOutbound(ConfigurationRegistry configurationRegistry,
            AConnectorClient connectorClient) {
        this.objectMapper = configurationRegistry.getObjectMapper();
        this.c8yAgent = configurationRegistry.getC8yAgent();
        this.mappingComponent = configurationRegistry.getMappingComponent();
        this.virtThreadPool = configurationRegistry.getVirtThreadPool();
        this.connectorClient = connectorClient;
        // log.info("Tenant {} - HIER I {} {}", connectorClient.getTenant(),
        // configurationRegistry.getPayloadProcessorsOutbound());
        // log.info("Tenant {} - HIER II {} {}", connectorClient.getTenant(),
        // configurationRegistry.getPayloadProcessorsOutbound().get(connectorClient.getTenant()));
        this.payloadProcessorsOutbound = configurationRegistry.getPayloadProcessorsOutbound()
                .get(connectorClient.getTenant())
                .get(connectorClient.getConnectorIdentifier());
        this.configurationRegistry = configurationRegistry;
        this.notificationSubscriber = configurationRegistry.getNotificationSubscriber();

    }

    @Override
    public void onOpen(URI serverUri) {
        log.info("Tenant {} - Connector {} connected to Cumulocity notification service over Web Socket",
                connectorClient.getTenant(), connectorClient.getConnectorName());
        notificationSubscriber.setDeviceConnectionStatus(connectorClient.getTenant(), 200);
    }

    @Override
    public void onNotification(Notification notification) {
        // We don't care about UPDATES nor DELETES and ignore notifications if connector
        // is not connected
        String tenant = getTenantFromNotificationHeaders(notification.getNotificationHeaders());
        if (!connectorClient.isConnected())
            log.warn("Tenant {} - Notification message received but connector {} is not connected. Ignoring message..",
                    tenant, connectorClient.getConnectorName());
        if ("CREATE".equals(notification.getNotificationHeaders().get(1)) && connectorClient.isConnected()) {
            // log.info("Tenant {} - Notification received: <{}>, <{}>, <{}>, <{}>", tenant,
            // notification.getMessage(),
            // notification.getNotificationHeaders(),
            // connectorClient.connectorConfiguration.name,
            // connectorClient.isConnected());
            C8YMessage c8yMessage = new C8YMessage();
            c8yMessage.setPayload(notification.getMessage());
            c8yMessage.setApi(notification.getApi());
            c8yMessage.setTenant(tenant);
            c8yMessage.setSendPayload(true);
            virtThreadPool.submit(() -> {
                processMessage(c8yMessage);
            });
        }
    }

    @Override
    public void onError(Throwable t) {
        log.error("Tenant {} - We got an exception: ", connectorClient.getTenant(), t);
    }

    @Override
    public void onClose(int statusCode, String reason) {
        log.info("Tenant {} - Web Socket connection closed.", connectorClient.getTenant());
        if (reason.contains("401"))
            notificationSubscriber.setDeviceConnectionStatus(connectorClient.getTenant(), 401);
        else
            notificationSubscriber.setDeviceConnectionStatus(connectorClient.getTenant(), null);
    }

    public String getTenantFromNotificationHeaders(List<String> notificationHeaders) {
        return notificationHeaders.get(0).split("/")[1];
    }

    public static class MappingOutboundTask<T> implements Callable<List<ProcessingContext<?>>> {
        List<Mapping> resolvedMappings;
        Map<MappingType, BaseProcessorOutbound<T>> payloadProcessorsOutbound;
        C8YMessage c8yMessage;
        MappingComponent mappingComponent;
        C8YAgent c8yAgent;
        ObjectMapper objectMapper;
        ServiceConfiguration serviceConfiguration;
        AConnectorClient connectorClient;
        Timer outboundProcessingTimer;
        Counter outboundProcessingCounter;

        public MappingOutboundTask(ConfigurationRegistry configurationRegistry, List<Mapping> resolvedMappings,
                MappingComponent mappingComponent,
                Map<MappingType, BaseProcessorOutbound<T>> payloadProcessorsOutbound,
                C8YMessage c8yMessage, AConnectorClient connectorClient) {
            this.connectorClient = connectorClient;
            this.resolvedMappings = resolvedMappings;
            this.mappingComponent = mappingComponent;
            this.c8yAgent = configurationRegistry.getC8yAgent();
            this.outboundProcessingTimer = Timer.builder("dynmapper_outbound_processing_time")
                    .tag("tenant", connectorClient.getTenant())
                    .tag("connector", connectorClient.getConnectorIdentifier())
                    .description("Processing time of outbound messages").register(Metrics.globalRegistry);
            this.outboundProcessingCounter = Counter.builder("dynmapper_outbound_message_total")
                    .tag("tenant", connectorClient.getTenant()).description("Total number of outbound messages")
                    .tag("connector", connectorClient.getConnectorIdentifier()).register(Metrics.globalRegistry);
            this.c8yMessage = c8yMessage;
            this.objectMapper = configurationRegistry.getObjectMapper();
            this.serviceConfiguration = configurationRegistry.getServiceConfigurations().get(c8yMessage.getTenant());
            this.payloadProcessorsOutbound = payloadProcessorsOutbound;

        }

        @Override
        public List<ProcessingContext<?>> call() throws Exception {

            Timer.Sample timer = Timer.start(Metrics.globalRegistry);
            String tenant = c8yMessage.getTenant();
            boolean sendPayload = c8yMessage.isSendPayload();

            List<ProcessingContext<?>> processingResult = new ArrayList<>();
            MappingStatus mappingStatusUnspecified = mappingComponent
                    .getMappingStatus(tenant, Mapping.UNSPECIFIED_MAPPING);
            resolvedMappings.forEach(mapping -> {
                // only process active mappings
                if (mapping.getActive()
                        && connectorClient.getMappingsDeployedOutbound().containsKey(mapping.identifier)) {
                    MappingStatus mappingStatus = mappingComponent.getMappingStatus(tenant, mapping);
                    // identify the correct processor based on the mapping type
                    BaseProcessorOutbound processor = payloadProcessorsOutbound.get(mapping.mappingType);
                    try {
                        if (processor != null) {
                            Object payload = processor.deserializePayload(mapping, c8yMessage);
                            ProcessingContext<?> context = ProcessingContext.builder().payload(payload)
                                    .topic(mapping.publishTopic)
                                    .mappingType(mapping.mappingType).mapping(mapping).sendPayload(sendPayload)
                                    .tenant(tenant).supportsMessageContext(mapping.supportsMessageContext)
                                    .qos(mapping.qos).serviceConfiguration(serviceConfiguration)
                                    .build();
                            if (serviceConfiguration.logPayload || mapping.debug) {
                                log.info(
                                        "Tenant {} - New message for topic: {}, for connector: {}, wrapped message: {}",
                                        tenant,
                                        context.getTopic(),
                                        connectorClient.getConnectorIdentifier(),
                                        context.getPayload().toString());
                            } else {
                                log.info("Tenant {} - New message for topic: {}, for connector: {}, sendPayload: {}",
                                        tenant,
                                        context.getTopic(), connectorClient.getConnectorIdentifier(), sendPayload);
                            }
                            mappingStatus.messagesReceived++;
                            if (mapping.snoopStatus == SnoopStatus.ENABLED
                                    || mapping.snoopStatus == SnoopStatus.STARTED) {
                                String serializedPayload = objectMapper.writeValueAsString(context.getPayload());
                                if (serializedPayload != null) {
                                    mapping.addSnoopedTemplate(serializedPayload);
                                    mappingStatus.snoopedTemplatesTotal = mapping.snoopedTemplates.size();
                                    mappingStatus.snoopedTemplatesActive++;

                                    log.debug("Tenant {} - Adding snoopedTemplate to map: {},{},{}", tenant,
                                            mapping.mappingTopic,
                                            mapping.snoopedTemplates.size(),
                                            mapping.snoopStatus);
                                    mappingComponent.addDirtyMapping(tenant, mapping);

                                } else {
                                    log.warn(
                                            "Tenant {} - Message could NOT be parsed, ignoring this message, as class is not valid: {} {}",
                                            tenant,
                                            context.getPayload().getClass());
                                }
                            } else {
                                processor.enrichPayload(context);
                                processor.extractFromSource(context);
                                processor.substituteInTargetAndSend(context);
                                Counter.builder("dynmapper_outbound_message_total")
                                        .tag("tenant", c8yMessage.getTenant())
                                        .description("Total number of outbound messages")
                                        .tag("connector", processor.connectorClient.getConnectorIdentifier())
                                        .register(Metrics.globalRegistry).increment();
                                timer.stop(Timer.builder("dynmapper_outbound_processing_time")
                                        .tag("tenant", c8yMessage.getTenant())
                                        .tag("connector", processor.connectorClient.getConnectorIdentifier())
                                        .description("Processing time of outbound messages")
                                        .register(Metrics.globalRegistry));

                                List<C8YRequest> resultRequests = context.getRequests();
                                if (context.hasError() || resultRequests.stream().anyMatch(r -> r.hasError())) {
                                    mappingStatus.errors++;
                                }
                            }
                            processingResult.add(context);
                        } else {
                            mappingStatusUnspecified.errors++;
                            log.error("Tenant {} - No process for MessageType: {} registered, ignoring this message!",
                                    tenant, mapping.mappingType);
                        }
                    } catch (Exception e) {
                        log.warn("Tenant {} - Message could NOT be parsed, ignoring this message: {}", tenant,
                                e.getMessage());
                        log.error("Tenant {} - Message Stacktrace: ", tenant, e);
                        mappingStatus.errors++;
                    }
                }
            });
            timer.stop(outboundProcessingTimer);
            return processingResult;
        }

    }

    public Future<List<ProcessingContext<?>>> processMessage(C8YMessage c8yMessage) {
        String tenant = c8yMessage.getTenant();
        MappingStatus mappingStatusUnspecified = mappingComponent.getMappingStatus(tenant, Mapping.UNSPECIFIED_MAPPING);
        Future<List<ProcessingContext<?>>> futureProcessingResult = null;
        List<Mapping> resolvedMappings = new ArrayList<>();

        // Handle C8Y Operation Status
        // TODO Add OperationAutoAck Status to activate/deactive
        OperationRepresentation op = null;
        //
        if (c8yMessage.getApi().equals(API.OPERATION)) {
            op = JSONBase.getJSONParser().parse(OperationRepresentation.class, c8yMessage.getPayload());
        }
        if (c8yMessage.getPayload() != null) {
            try {
                resolvedMappings = mappingComponent.resolveMappingOutbound(tenant, c8yMessage.getPayload(),
                        c8yMessage.getApi());
                if (resolvedMappings.size() > 0 && op != null)
                    c8yAgent.updateOperationStatus(tenant, op, OperationStatus.EXECUTING, null);
            } catch (Exception e) {
                log.warn("Tenant {} - Error resolving appropriate map. Could NOT be parsed. Ignoring this message!",
                        tenant);
                log.debug(e.getMessage(), tenant);
                // if (op != null)
                // c8yAgent.updateOperationStatus(tenant, op, OperationStatus.FAILED,
                // e.getLocalizedMessage());
                mappingStatusUnspecified.errors++;
            }
        } else {
            return futureProcessingResult;
        }

        futureProcessingResult = virtThreadPool.submit(
                new MappingOutboundTask(configurationRegistry, resolvedMappings, mappingComponent,
                        payloadProcessorsOutbound, c8yMessage, connectorClient));

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
                    // c8yAgent.updateOperationStatus(tenant, op, OperationStatus.FAILED,
                    // "No Mapping found for operation " + op.toJSON());
                }
            } catch (InterruptedException e) {
                // c8yAgent.updateOperationStatus(tenant, op, OperationStatus.FAILED,
                // e.getLocalizedMessage());
                log.error("Tenant {} - Error waiting for result of Processing context", tenant, e);
            } catch (ExecutionException e) {
                // c8yAgent.updateOperationStatus(tenant, op, OperationStatus.FAILED,
                // e.getLocalizedMessage());
                log.error("Tenant {} - Error waiting for result of Processing context", tenant, e);
            }
        }
        return futureProcessingResult;
    }
}