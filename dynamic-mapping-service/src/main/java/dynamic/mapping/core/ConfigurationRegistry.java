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

package dynamic.mapping.core;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import com.cumulocity.microservice.context.credentials.MicroserviceCredentials;
import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapping.configuration.ConnectorConfiguration;
import dynamic.mapping.configuration.ConnectorConfigurationComponent;
import dynamic.mapping.configuration.ServiceConfiguration;
import dynamic.mapping.configuration.ServiceConfigurationComponent;
import dynamic.mapping.connector.core.client.AConnectorClient;
import dynamic.mapping.connector.core.client.ConnectorException;
import dynamic.mapping.connector.core.client.ConnectorType;
import dynamic.mapping.connector.http.HttpClient;
import dynamic.mapping.connector.kafka.KafkaClient;
import dynamic.mapping.connector.mqtt.MQTTClient;
import dynamic.mapping.connector.mqtt.MQTTServiceClient;
import dynamic.mapping.connector.webhook.WebHook;
import dynamic.mapping.model.MappingServiceRepresentation;
import dynamic.mapping.notification.C8YNotificationSubscriber;
import dynamic.mapping.processor.extension.ExtensibleProcessor;
import dynamic.mapping.processor.inbound.BaseProcessorInbound;
import dynamic.mapping.processor.inbound.FlatFileProcessorInbound;
import dynamic.mapping.processor.inbound.BinaryProcessorInbound;
import dynamic.mapping.processor.inbound.JSONProcessorInbound;
import dynamic.mapping.processor.model.MappingType;
import dynamic.mapping.processor.outbound.BaseProcessorOutbound;
import dynamic.mapping.processor.outbound.JSONProcessorOutbound;
import dynamic.mapping.processor.processor.fixed.InternalProtobufProcessor;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class ConfigurationRegistry {

    @Getter
    private Map<String, MicroserviceCredentials> microserviceCredentials = new HashMap<>();

    // structure: <tenant, <mappingType, mappingServiceRepresentation>>
    @Getter
    private Map<String, MappingServiceRepresentation> mappingServiceRepresentations = new HashMap<>();

    // structure: <tenant, <mappingType, extensibleProcessorInbound>>
    @Getter
    private Map<String, Map<MappingType, BaseProcessorInbound<?>>> payloadProcessorsInbound = new HashMap<>();

    // structure: <tenant, <connectorIdentifier, <mappingType,
    // extensibleProcessorOutbound>>>
    @Getter
    private Map<String, Map<String, Map<MappingType, BaseProcessorOutbound<?>>>> payloadProcessorsOutbound = new HashMap<>();

    @Getter
    private Map<String, ServiceConfiguration> serviceConfigurations = new HashMap<>();

    // structure: <tenant, <extensibleProcessorSource>>
    @Getter
    private Map<String, ExtensibleProcessor> extensibleProcessors = new HashMap<>();

    @Getter
    private C8YAgent c8yAgent;

    @Autowired
    public void setC8yAgent(C8YAgent c8yAgent) {
        this.c8yAgent = c8yAgent;
    }

    @Getter
    private C8YNotificationSubscriber notificationSubscriber;

    @Autowired
    public void setC8yAgent(C8YNotificationSubscriber notificationSubscriber) {
        this.notificationSubscriber = notificationSubscriber;
    }

    @Getter
    private ObjectMapper objectMapper;

    @Autowired
    public void setObjectMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Getter
    private MappingComponent mappingComponent;

    @Autowired
    public void setMappingComponent(@Lazy MappingComponent mappingComponent) {
        this.mappingComponent = mappingComponent;
    }

    @Getter
    private ConnectorConfigurationComponent connectorConfigurationComponent;

    @Autowired
    public void setConnectorConfigurationComponent(
            @Lazy ConnectorConfigurationComponent connectorConfigurationComponent) {
        this.connectorConfigurationComponent = connectorConfigurationComponent;
    }

    @Getter
    public ServiceConfigurationComponent serviceConfigurationComponent;

    @Autowired
    public void setServiceConfigurationComponent(@Lazy ServiceConfigurationComponent serviceConfigurationComponent) {
        this.serviceConfigurationComponent = serviceConfigurationComponent;
    }

    @Getter
    @Setter
    @Autowired
    private ExecutorService virtThreadPool;

    public Map<MappingType, BaseProcessorInbound<?>> createPayloadProcessorsInbound(String tenant) {
        ExtensibleProcessor extensibleProcessor = getExtensibleProcessors().get(tenant);
        return Map.of(
                MappingType.JSON, new JSONProcessorInbound(this),
                MappingType.FLAT_FILE, new FlatFileProcessorInbound(this),
                MappingType.BINARY, new BinaryProcessorInbound(this),
                MappingType.PROTOBUF_INTERNAL, new InternalProtobufProcessor(this),
                MappingType.EXTENSION_SOURCE, extensibleProcessor,
                MappingType.EXTENSION_SOURCE_TARGET, extensibleProcessor);
    }

    public AConnectorClient createConnectorClient(ConnectorConfiguration connectorConfiguration,
            String additionalSubscriptionIdTest, String tenant) throws ConnectorException {
        AConnectorClient connectorClient = null;
        if (ConnectorType.MQTT.equals(connectorConfiguration.getConnectorType())) {
            connectorClient = new MQTTClient(this, connectorConfiguration,
                    null,
                    additionalSubscriptionIdTest, tenant);
            log.info("Tenant {} - Initializing MQTT Connector with identifier {}", tenant,
                    connectorConfiguration.getIdentifier());
        } else if (ConnectorType.CUMULOCITY_MQTT_SERVICE.equals(connectorConfiguration.getConnectorType())) {
            connectorClient = new MQTTServiceClient(this, connectorConfiguration,
                    null,
                    additionalSubscriptionIdTest, tenant);
            log.info("Tenant {} - Initializing MQTTService Connector with identifier {}", tenant,
                    connectorConfiguration.getIdentifier());
        } else if (ConnectorType.KAFKA.equals(connectorConfiguration.getConnectorType())) {
            connectorClient = new KafkaClient(this, connectorConfiguration,
                    null,
                    additionalSubscriptionIdTest, tenant);
            log.info("Tenant {} - Initializing Kafka Connector with identifier {}", tenant,
                    connectorConfiguration.getIdentifier());
        } else if (ConnectorType.HTTP.equals(connectorConfiguration.getConnectorType())) {
            connectorClient = new HttpClient(this, connectorConfiguration,
                    null,
                    additionalSubscriptionIdTest, tenant);
            log.info("Tenant {} - Initializing HTTP Connector with identifier {}", tenant,
                    connectorConfiguration.getIdentifier());
        } else if (ConnectorType.WEB_HOOK.equals(connectorConfiguration.getConnectorType())) {
            connectorClient = new WebHook(this, connectorConfiguration,
                    null,
                    additionalSubscriptionIdTest, tenant);
            log.info("Tenant {} - Initializing WebHook Connector with identifier {}", tenant,
                    connectorConfiguration.getIdentifier());
        }
        return connectorClient;
    }

    public Map<MappingType, BaseProcessorOutbound<?>> createPayloadProcessorsOutbound(
            AConnectorClient connectorClient) {
        return Map.of(
                MappingType.JSON, new JSONProcessorOutbound(this, connectorClient));
    }

    public void initializePayloadProcessorsInbound(String tenant) {
        if (!payloadProcessorsInbound.containsKey(tenant)) {
            payloadProcessorsInbound.put(tenant, createPayloadProcessorsInbound(tenant));
        }
    }

    public void initializePayloadProcessorsOutbound(AConnectorClient connectorClient) {
        Map<String, Map<MappingType, BaseProcessorOutbound<?>>> processorPerTenant = payloadProcessorsOutbound
                .get(connectorClient.getTenant());
        if (processorPerTenant == null) {
            // log.info("Tenant {} - HIER III {} {}", connectorClient.getTenant(),
            // processorPerTenant);
            processorPerTenant = new HashMap<>();
            payloadProcessorsOutbound.put(connectorClient.getTenant(), processorPerTenant);
        }
        // if (!processorPerTenant.containsKey(connectorClient.getConnectorIdent())) {
        // log.info("Tenant {} - HIER VI {} {}", connectorClient.getTenant(),
        // processorPerTenant);
        processorPerTenant.put(connectorClient.getConnectorIdentifier(),
                createPayloadProcessorsOutbound(connectorClient));
        // }
    }

    public MicroserviceCredentials getMicroserviceCredential(String tenant) {
        MicroserviceCredentials ms = microserviceCredentials.get(tenant);
        return ms;
    }

}
