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

package mqtt.mapping.processor.inbound;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import mqtt.mapping.connector.core.callback.ConnectorMessage;
import mqtt.mapping.connector.core.callback.GenericMessageCallback;
import mqtt.mapping.connector.core.client.AConnectorClient;
import mqtt.mapping.core.C8YAgent;
import mqtt.mapping.core.MappingComponent;
import mqtt.mapping.model.Mapping;
import mqtt.mapping.model.MappingStatus;
import mqtt.mapping.model.SnoopStatus;
import mqtt.mapping.processor.PayloadProcessor;
import mqtt.mapping.processor.model.C8YRequest;
import mqtt.mapping.processor.model.MappingType;
import mqtt.mapping.processor.model.ProcessingContext;
import org.apache.commons.codec.binary.Hex;
import org.joda.time.DateTime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

@Slf4j
public class AsynchronousDispatcherInbound implements GenericMessageCallback {
    public static class MappingProcessor<T> implements Callable<List<ProcessingContext<?>>> {

        List<Mapping> resolvedMappings;
        String topic;
        String tenant;
        Map<MappingType, BasePayloadProcessorInbound<T>> payloadProcessorsInbound;
        boolean sendPayload;
        ConnectorMessage message;
        MappingComponent mappingStatusComponent;
        C8YAgent c8yAgent;
        ObjectMapper objectMapper;

        public MappingProcessor(List<Mapping> mappings, MappingComponent mappingStatusComponent, C8YAgent c8yAgent,
                String topic, String tenant,
                Map<MappingType, BasePayloadProcessorInbound<T>> payloadProcessorsInbound, boolean sendPayload,
                ConnectorMessage message, ObjectMapper objectMapper) {
            this.resolvedMappings = mappings;
            this.mappingStatusComponent = mappingStatusComponent;
            this.c8yAgent = c8yAgent;
            this.topic = topic;
            this.tenant = tenant;
            this.payloadProcessorsInbound = payloadProcessorsInbound;
            this.sendPayload = sendPayload;
            this.message = message;
            this.objectMapper = objectMapper;
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
                    context.setTopic(topic);
                    context.setMappingType(mapping.mappingType);
                    context.setMapping(mapping);
                    context.setSendPayload(sendPayload);
                    // identify the corect processor based on the mapping type
                    MappingType mappingType = context.getMappingType();
                    BasePayloadProcessorInbound processor = payloadProcessorsInbound.get(mappingType);

                    if (processor != null) {
                        try {
                            processor.deserializePayload(context, message);
                            if (c8yAgent.getServiceConfiguration().logPayload) {
                                log.info("Tenant {} - New message on topic: '{}', wrapped message: {}", tenant, context.getTopic(),
                                        context.getPayload().toString());
                            } else {
                                log.info("Tenant {} - New message on topic: '{}'", tenant, context.getTopic());
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

                                    log.debug("Tenant {} - Adding snoopedTemplate to map: {},{},{}", tenant, mapping.subscriptionTopic,
                                            mapping.snoopedTemplates.size(),
                                            mapping.snoopStatus);
                                    mappingStatusComponent.addDirtyMapping(tenant, mapping);

                                } else {
                                    log.warn(
                                            "Tenant {} - Message could NOT be parsed, ignoring this message, as class is not valid: {}", tenant,
                                            context.getPayload().getClass());
                                }
                            } else {
                                processor.extractFromSource(context);
                                processor.substituteInTargetAndSend(context);
                                // processor.substituteInTargetAndSend(context);
                                List<C8YRequest> resultRequests = context.getRequests();
                                if (context.hasError() || resultRequests.stream().anyMatch(r -> r.hasError())) {
                                    mappingStatus.errors++;
                                }
                            }
                        } catch (Exception e) {
                            log.warn("Tenant {} - Message could NOT be parsed, ignoring this message: {}", tenant, e.getMessage());
                            log.info("Tenant {} - Message Stacktrace: {}",tenant, e);
                            mappingStatus.errors++;
                        }
                    } else {
                        mappingStatusUnspecified.errors++;
                        log.error("Tenant {} - No process for MessageType: {} registered, ignoring this message!", tenant, mappingType);
                    }
                    processingResult.add(context);
                }

            });
            return processingResult;
        }

    }

    private static final Object TOPIC_PERFORMANCE_METRIC = "__TOPIC_PERFORMANCE_METRIC";

    private C8YAgent c8yAgent;

    //@Autowired
    //public void setC8yAgent(@Lazy C8YAgent c8yAgent) {
    //    this.c8yAgent = c8yAgent;
    //}

    private AConnectorClient connectorClient;

    private ObjectMapper objectMapper;

    //@Autowired
    //public void setObjectMapper(@Lazy ObjectMapper objectMapper) {
    //    this.objectMapper = objectMapper;
    //}

    //@Autowired
    //Map<MappingType, BasePayloadProcessor<?>> payloadProcessorsInbound;

    //@Autowired
    //@Qualifier("cachedThreadPool")
    private ExecutorService cachedThreadPool;

    //@Autowired
    MappingComponent mappingComponent;

    //@Autowired
    //ServiceConfigurationComponent serviceConfigurationComponent;

    public AsynchronousDispatcherInbound(AConnectorClient connectorClient, C8YAgent c8YAgent, ObjectMapper objectMapper, ExecutorService cachedThreadPool, MappingComponent mappingComponent)  {
        this.connectorClient = connectorClient;
        this.c8yAgent = c8YAgent;
        this.objectMapper = objectMapper;
        this.cachedThreadPool = cachedThreadPool;
        this.mappingComponent = mappingComponent;
    }

    public Future<List<ProcessingContext<?>>> processMessage(String tenant, String connectorIdent, String topic, ConnectorMessage message,
            boolean sendPayload) throws Exception {
        MappingStatus mappingStatusUnspecified = mappingComponent.getMappingStatus(tenant, Mapping.UNSPECIFIED_MAPPING);
        Future<List<ProcessingContext<?>>> futureProcessingResult = null;
        List<Mapping> resolvedMappings = new ArrayList<>();

        if (topic != null && !topic.startsWith("$SYS")) {
            if (message.getPayload() != null) {
                try {
                    resolvedMappings = mappingComponent.resolveMappingInbound(tenant, topic);
                } catch (Exception e) {
                    log.warn("Error resolving appropriate map for topic \"" + topic
                            + "\". Could NOT be parsed. Ignoring this message!");
                    log.debug(e.getMessage(), e);
                    mappingStatusUnspecified.errors++;
                }
            } else {
                return futureProcessingResult;
            }
        } else {
            return futureProcessingResult;
        }
        PayloadProcessor payloadProcessor = new PayloadProcessor(objectMapper, c8yAgent, tenant, connectorClient);

        futureProcessingResult = cachedThreadPool.submit(
                new MappingProcessor(resolvedMappings, mappingComponent, c8yAgent, topic, tenant,
                        payloadProcessor.getPayloadProcessorsInbound(),
                        sendPayload, message, objectMapper));

        return futureProcessingResult;

    }

    @Override
    public void onClose( String closeMessage, Throwable closeException) {
        String tenant = connectorClient.getTenant();
        String connectorIdent = connectorClient.getConnectorIdent();
        if (closeException != null)
            log.error("Tenant {} - Connection Lost to broker {}: {}", tenant, connectorIdent, closeException.getMessage());
        closeException.printStackTrace();
        if(closeMessage != null)
            log.info("Tenant {} - Connection Lost to MQTT broker: {}", tenant, closeMessage);

        c8yAgent.createEvent("Connection lost to MQTT broker", AConnectorClient.STATUS_MAPPING_EVENT_TYPE, DateTime.now(), null, tenant);
        connectorClient.connect();
    }

    @Override
    public void onMessage(String topic, ConnectorMessage message) throws Exception {
        String tenant = connectorClient.getTenant();
        String connectorIdent = connectorClient.getConnectorIdent();
        if ((TOPIC_PERFORMANCE_METRIC.equals(topic))) {
            // REPORT MAINTENANCE METRIC
        } else {
            processMessage(tenant, connectorIdent, topic, message, true);
        }
    }

    @Override
    public void onError( Throwable errorException) {

    }

}