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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

import org.graalvm.polyglot.Engine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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

import dynamic.mapping.connector.http.HttpClient;
import dynamic.mapping.connector.kafka.KafkaClient;
import dynamic.mapping.connector.mqtt.MQTT3Client;
import dynamic.mapping.connector.mqtt.MQTT5Client;
import dynamic.mapping.connector.mqtt.MQTTServiceClient;
import dynamic.mapping.connector.webhook.WebHook;
import dynamic.mapping.model.MappingServiceRepresentation;
import dynamic.mapping.notification.C8YNotificationSubscriber;
import dynamic.mapping.processor.extension.ExtensibleProcessorInbound;
import dynamic.mapping.processor.inbound.BaseProcessorInbound;
import dynamic.mapping.processor.inbound.CodeBasedProcessorInbound;
import dynamic.mapping.processor.inbound.FlatFileProcessorInbound;
import dynamic.mapping.processor.inbound.HexProcessorInbound;
import dynamic.mapping.processor.inbound.JSONProcessorInbound;
import dynamic.mapping.processor.model.MappingType;
import dynamic.mapping.processor.outbound.BaseProcessorOutbound;
import dynamic.mapping.processor.outbound.CodeBasedProcessorOutbound;
import dynamic.mapping.processor.outbound.JSONProcessorOutbound;
import dynamic.mapping.processor.processor.fixed.InternalProtobufProcessor;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class ConfigurationRegistry {

    private Map<String, Engine> graalsEngines = new ConcurrentHashMap<>();

    private Map<String, MicroserviceCredentials> microserviceCredentials = new ConcurrentHashMap<>();

    // Structure: < Tenant, < MappingType, < MappingServiceRepresentation > >
    private Map<String, MappingServiceRepresentation> mappingServiceRepresentations = new ConcurrentHashMap<>();

    // Structure: < Tenant, < MappingType, ProcessorInbound>>
    private Map<String, Map<MappingType, BaseProcessorInbound<?>>> payloadProcessorsInbound = new ConcurrentHashMap<>();

    // Structure: < Tenant, < ConnectorIdentifier, < MappingType, ProcessorOutbound
    // > >>
    private Map<String, Map<String, Map<MappingType, BaseProcessorOutbound<?>>>> payloadProcessorsOutbound = new ConcurrentHashMap<>();

    // Structure: < Tenant, < ServiceConfiguration > >
    private Map<String, ServiceConfiguration> serviceConfigurations = new ConcurrentHashMap<>();

    // Structure: < Tenant, < ExtensibleProcessorSource > >
    private Map<String, ExtensibleProcessorInbound> extensibleProcessors = new ConcurrentHashMap<>();

    @Getter
    private C8YAgent c8yAgent;

    @Value("${APP.mqttServiceUrl}")
    @Getter
    String mqttServiceUrl;

    @Autowired
    public void setC8yAgent(C8YAgent c8yAgent) {
        this.c8yAgent = c8yAgent;
    }

    @Getter
    private C8YNotificationSubscriber notificationSubscriber;

    @Autowired
    public void setNotificationSubscriber(C8YNotificationSubscriber notificationSubscriber) {
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
    private ExecutorService virtualThreadPool;

    public Map<MappingType, BaseProcessorInbound<?>> createPayloadProcessorsInbound(String tenant) {
        ExtensibleProcessorInbound extensibleProcessor = extensibleProcessors.get(tenant);
        return Map.of(
                MappingType.JSON, new JSONProcessorInbound(this),
                MappingType.CODE_BASED, new CodeBasedProcessorInbound(this),
                MappingType.FLAT_FILE, new FlatFileProcessorInbound(this),
                MappingType.HEX, new HexProcessorInbound(this),
                MappingType.PROTOBUF_INTERNAL, new InternalProtobufProcessor(this),
                MappingType.EXTENSION_SOURCE, extensibleProcessor,
                MappingType.EXTENSION_SOURCE_TARGET, extensibleProcessor);
    }

    public AConnectorClient createConnectorClient(ConnectorConfiguration connectorConfiguration,
            String additionalSubscriptionIdTest, String tenant) throws ConnectorException {
        AConnectorClient connectorClient = null;

        // Convert if statement to switch statement
        switch (connectorConfiguration.getConnectorType()) {
            case MQTT:
                // if version is not set, default to 3.1.1, as this property was introduced
                // later. This will not break existing configuration
                String version = ((String) connectorConfiguration.getProperties().getOrDefault("version",
                        AConnectorClient.MQTT_VERSION_3_1_1));
                if (AConnectorClient.MQTT_VERSION_3_1_1.equals(version)) {
                    connectorClient = new MQTT3Client(this, connectorConfiguration,
                            null,
                            additionalSubscriptionIdTest, tenant);
                } else {
                    connectorClient = new MQTT5Client(this, connectorConfiguration,
                            null,
                            additionalSubscriptionIdTest, tenant);
                }
                log.info("Tenant {} - Initializing MQTT {} Connector, identifier {}", tenant, version,
                        connectorConfiguration.getIdentifier());
                break;

            case CUMULOCITY_MQTT_SERVICE:
                connectorClient = new MQTTServiceClient(this, connectorConfiguration,
                        null,
                        additionalSubscriptionIdTest, tenant);
                log.info("Tenant {} - Initializing MQTTService Connector, identifier {}", tenant,
                        connectorConfiguration.getIdentifier());
                break;

            case KAFKA:
                connectorClient = new KafkaClient(this, connectorConfiguration,
                        null,
                        additionalSubscriptionIdTest, tenant);
                log.info("Tenant {} - Initializing Kafka Connector, identifier {}", tenant,
                        connectorConfiguration.getIdentifier());
                break;

            case HTTP:
                connectorClient = new HttpClient(this, connectorConfiguration,
                        null,
                        additionalSubscriptionIdTest, tenant);
                log.info("Tenant {} - Initializing HTTP Connector, identifier {}", tenant,
                        connectorConfiguration.getIdentifier());
                break;

            case WEB_HOOK:
                connectorClient = new WebHook(this, connectorConfiguration,
                        null,
                        additionalSubscriptionIdTest, tenant);
                log.info("Tenant {} - Initializing WebHook Connector, identifier {}", tenant,
                        connectorConfiguration.getIdentifier());
                break;

            default:
                log.warn("Tenant {} - Unknown connector type: {}", tenant, connectorConfiguration.getConnectorType());
                break;
        }

        return connectorClient;
    }

    public Map<MappingType, BaseProcessorOutbound<?>> createPayloadProcessorsOutbound(
            AConnectorClient connectorClient) {
        return Map.of(
                MappingType.JSON, new JSONProcessorOutbound(this, connectorClient),
                MappingType.CODE_BASED, new CodeBasedProcessorOutbound(this, connectorClient));
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
            processorPerTenant = new ConcurrentHashMap<>();
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

    public void createGraalsEngine(String tenant) {
        Engine eng = Engine.newBuilder()
                .option("engine.WarnInterpreterOnly", "false")
                .build();
        ;
        graalsEngines.put(tenant, eng);
    }

    public Engine getGraalsEngine(String tenant) {
        return graalsEngines.get(tenant);
    }

    public void removeGraalsEngine(String tenant) {
        graalsEngines.remove(tenant);
    }

    public ServiceConfiguration getServiceConfiguration(String tenant) {
        return serviceConfigurations.get(tenant);
    }

    public void removeServiceConfiguration(String tenant) {
        serviceConfigurations.remove(tenant);
    }

    public void addServiceConfiguration(String tenant, ServiceConfiguration configuration) {
        serviceConfigurations.put(tenant, configuration);
    }

    public void addMappingServiceRepresentation(String tenant,
            MappingServiceRepresentation mappingServiceRepresentation) {
        mappingServiceRepresentations.put(tenant, mappingServiceRepresentation);
    }

    public MappingServiceRepresentation getMappingServiceRepresentation(String tenant) {
        return mappingServiceRepresentations.get(tenant);
    }

    public void removeMappingServiceRepresentation(String tenant) {
        mappingServiceRepresentations.remove(tenant);
    }

    public ExtensibleProcessorInbound getExtensibleProcessor(String tenant) {
        return extensibleProcessors.get(tenant);
    }

    public void addExtensibleProcessor(String tenant, ExtensibleProcessorInbound extensibleProcessor) {
        extensibleProcessors.put(tenant, extensibleProcessor);
    }

    public void removeExtensibleProcessor(String tenant) {
        extensibleProcessors.remove(tenant);
    }

    public void removeMicroserviceCredentials(String tenant) {
        microserviceCredentials.remove(tenant);
    }

    public void addMicroserviceCredentials(String tenant, MicroserviceCredentials credentials) {
        microserviceCredentials.put(tenant, credentials);
    }

    public void addPayloadProcessorInbound(String tenant, MappingType mappingType,
            BaseProcessorInbound<?> payloadProcessorInbound) {
        payloadProcessorsInbound.get(tenant).put(mappingType, payloadProcessorInbound);
    }

    public Map<MappingType, BaseProcessorInbound<?>> getPayloadProcessorsInbound(String tenant) {
        return payloadProcessorsInbound.get(tenant);
    }

    public Map<MappingType, BaseProcessorOutbound<?>> getPayloadProcessorsOutbound(String tenant,
            String connectorIdentifier) {
        return payloadProcessorsOutbound.get(tenant).get(connectorIdentifier);
    }

    public void addPayloadProcessorOutbound(String tenant, String connectorIdentifier, MappingType mappingType,
            BaseProcessorOutbound<?> payloadProcessorOutbound) {
        payloadProcessorsOutbound.get(tenant).get(connectorIdentifier).put(mappingType, payloadProcessorOutbound);
    }

    public void removePayloadProcessorOutbound(String tenant, String connectorIdentifier, MappingType mappingType) {
        payloadProcessorsOutbound.get(tenant).get(connectorIdentifier).remove(mappingType);
    }

    public void removePayloadProcessorOutbound(String tenant, String connectorIdentifier) {
        payloadProcessorsOutbound.get(tenant).remove(connectorIdentifier);
    }

    public void removePayloadProcessorsOutbound(String tenant) {
        payloadProcessorsOutbound.remove(tenant);
    }

    public void removePayloadProcessorsInbound(String tenant) {
        payloadProcessorsInbound.remove(tenant);
    }

}
