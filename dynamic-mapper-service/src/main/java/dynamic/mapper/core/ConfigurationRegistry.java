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

package dynamic.mapper.core;

import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Source;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import com.cumulocity.microservice.context.credentials.MicroserviceCredentials;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.configuration.ConnectorConfiguration;
import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.connector.core.client.ConnectorException;

import dynamic.mapper.connector.http.HttpClient;
import dynamic.mapper.connector.kafka.KafkaClient;
import dynamic.mapper.connector.mqtt.MQTT3Client;
import dynamic.mapper.connector.mqtt.MQTT5Client;
import dynamic.mapper.connector.mqtt.MQTTServiceClient;
import dynamic.mapper.connector.pulsar.MQTTServicePulsarClient;
import dynamic.mapper.connector.pulsar.PulsarConnectorClient;
import dynamic.mapper.connector.webhook.WebHook;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MapperServiceRepresentation;
import dynamic.mapper.notification.NotificationSubscriber;
import dynamic.mapper.processor.extension.ExtensibleProcessorInbound;
import dynamic.mapper.processor.inbound.BaseProcessorInbound;
import dynamic.mapper.processor.inbound.CodeBasedProcessorInbound;
import dynamic.mapper.processor.inbound.FlatFileProcessorInbound;
import dynamic.mapper.processor.inbound.HexProcessorInbound;
import dynamic.mapper.processor.inbound.JSONProcessorInbound;
import dynamic.mapper.processor.model.MappingType;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.outbound.BaseProcessorOutbound;
import dynamic.mapper.processor.outbound.CodeBasedProcessorOutbound;
import dynamic.mapper.processor.outbound.DispatcherOutbound;
import dynamic.mapper.processor.outbound.JSONProcessorOutbound;
import dynamic.mapper.processor.processor.fixed.InternalProtobufProcessor;
import dynamic.mapper.service.ConnectorConfigurationService;
import dynamic.mapper.service.MappingService;
import dynamic.mapper.service.ServiceConfigurationService;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class ConfigurationRegistry {
    // TODO GRAAL_PERFORMANCE create cache for code graalCode

    private HostAccess hostAccess;

    private Map<String, Engine> graalEngines = new ConcurrentHashMap<>();

    // // Structure: < Tenant, Source>>
    // private Map<String, Source> graalSourceShared = new ConcurrentHashMap<>();
    //
    // // Structure: < Tenant, Source>>
    // private Map<String, Source> graalSourceSystem = new ConcurrentHashMap<>();
    //
    // // Structure: < Tenant, < MappingIdentifier, < Source > >
    // private Map<String, Map<String, Source>> graalSourceMapping = new
    // ConcurrentHashMap<>();

    private Map<String, MicroserviceCredentials> microserviceCredentials = new ConcurrentHashMap<>();

    // Structure: < Tenant, < MappingType, < MapperServiceRepresentation > >
    private Map<String, MapperServiceRepresentation> mapperServiceRepresentations = new ConcurrentHashMap<>();

    // Structure: < Tenant, < MappingType, ProcessorInbound>>
    private Map<String, Map<MappingType, BaseProcessorInbound<?>>> payloadProcessorsInbound = new ConcurrentHashMap<>();

    // Structure: < Tenant, < ConnectorIdentifier, < MappingType, ProcessorOutbound
    // > >>
    private Map<String, Map<String, Map<MappingType, BaseProcessorOutbound<?>>>> payloadProcessorsOutbound = new ConcurrentHashMap<>();

    // Structure: < Tenant, < ServiceConfiguration > >
    private Map<String, ServiceConfiguration> serviceConfigurations = new ConcurrentHashMap<>();

    // Structure: < Tenant, < ExtensibleProcessorSource > >
    private Map<String, ExtensibleProcessorInbound> extensibleProcessors = new ConcurrentHashMap<>();

    // TODO persist cache as DeviceToClientRepresentation
    // Structure: < Tenant, < Device, Client > >
    private Map<String, Map<String, String>> deviceToClientPerTenant = new ConcurrentHashMap<>();

    @Getter
    private C8YAgent c8yAgent;

    @Value("${APP.mqttServiceUrl}")
    @Getter
    String mqttServiceUrl;

    @Value("${APP.mqttServicePulsarUrl}")
    @Getter
    String mqttServicePulsarUrl;

    @Autowired
    public void setC8yAgent(C8YAgent c8yAgent) {
        this.c8yAgent = c8yAgent;
    }

    @Getter
    private NotificationSubscriber notificationSubscriber;

    @Autowired
    public void setNotificationSubscriber(NotificationSubscriber notificationSubscriber) {
        this.notificationSubscriber = notificationSubscriber;
    }

    @Getter
    private ObjectMapper objectMapper;

    @Autowired
    public void setObjectMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Getter
    private MappingService mappingService;

    @Autowired
    public void setMappingComponent(@Lazy MappingService mappingService) {
        this.mappingService = mappingService;
    }

    @Getter
    private ConnectorConfigurationService connectorConfigurationService;

    @Autowired
    public void setConnectorConfigurationService(
            @Lazy ConnectorConfigurationService connectorConfigurationService) {
        this.connectorConfigurationService = connectorConfigurationService;
    }

    @Getter
    public ServiceConfigurationService serviceConfigurationService;

    @Autowired
    public void setServiceConfigurationService(@Lazy ServiceConfigurationService serviceConfigurationService) {
        this.serviceConfigurationService = serviceConfigurationService;
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

    public static Source decodeCode(String code, String sourceCodeFileName, boolean replaceIdentifier,
            String mappingIdentifier) {
        byte[] decodedCodeBytes = Base64.getDecoder().decode(code);
        String decodedCode = new String(decodedCodeBytes);
        if (replaceIdentifier) {
            // replace the identifier in the code with the source file name
            String identifier = Mapping.EXTRACT_FROM_SOURCE + "_" + mappingIdentifier;
            decodedCode = decodedCode.replaceFirst(
                    Mapping.EXTRACT_FROM_SOURCE,
                    identifier);
        }
        Source source = Source.newBuilder("js", decodedCode, sourceCodeFileName)
                .buildLiteral();
        return source;
    }

    public AConnectorClient createConnectorClient(ConnectorConfiguration connectorConfiguration,
            String additionalSubscriptionIdTest, String tenant) throws ConnectorException {
        AConnectorClient connectorClient = null;

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
                log.info("{} - MQTT Connector {} created, identifier: {}", tenant, version,
                        connectorConfiguration.getIdentifier());
                break;

            case CUMULOCITY_MQTT_SERVICE:
                connectorClient = new MQTTServiceClient(this, connectorConfiguration,
                        null,
                        additionalSubscriptionIdTest, tenant);
                log.info("{} - MQTTService Connector created, identifier: {}", tenant,
                        connectorConfiguration.getIdentifier());
                break;

            case KAFKA:
                connectorClient = new KafkaClient(this, connectorConfiguration,
                        null,
                        additionalSubscriptionIdTest, tenant);
                log.info("{} - Kafka Connector created, identifier: {}", tenant,
                        connectorConfiguration.getIdentifier());
                break;

            case HTTP:
                connectorClient = new HttpClient(this, connectorConfiguration,
                        null,
                        additionalSubscriptionIdTest, tenant);
                log.info("{} - HTTP Connector created, identifier: {}", tenant,
                        connectorConfiguration.getIdentifier());
                break;

            case WEB_HOOK:
                connectorClient = new WebHook(this, connectorConfiguration,
                        null,
                        additionalSubscriptionIdTest, tenant);
                log.info("{} - WebHook Connector created, identifier: {}", tenant,
                        connectorConfiguration.getIdentifier());
                break;

            case PULSAR:
                connectorClient = new PulsarConnectorClient(this, connectorConfiguration,
                        null,
                        additionalSubscriptionIdTest, tenant);
                log.info("{} - Pulsar Connector created, identifier: {}", tenant,
                        connectorConfiguration.getIdentifier());
                break;

            case CUMULOCITY_MQTT_SERVICE_PULSAR:
                connectorClient = new MQTTServicePulsarClient(this, connectorConfiguration,
                        null,
                        additionalSubscriptionIdTest, tenant);
                log.info("{} - MQTTService Pulsar Connector created, identifier: {}", tenant,
                        connectorConfiguration.getIdentifier());
                break;
            default:
                log.warn("{} - Unknown connector type: {}", tenant, connectorConfiguration.getConnectorType());
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

    public void initializeResources(String tenant) {
        payloadProcessorsInbound.put(tenant, createPayloadProcessorsInbound(tenant));
        payloadProcessorsOutbound.put(tenant, new ConcurrentHashMap<>());
    }

    public void initializePayloadProcessorsOutbound(AConnectorClient connectorClient) {
        Map<String, Map<MappingType, BaseProcessorOutbound<?>>> processorPerTenant = payloadProcessorsOutbound
                .get(connectorClient.getTenant());
        processorPerTenant.put(connectorClient.getConnectorIdentifier(),
                createPayloadProcessorsOutbound(connectorClient));
    }

    public MapperServiceRepresentation initializeMapperServiceRepresentation(String tenant) {
        ManagedObjectRepresentation mapperServiceMOR = c8yAgent
                .initializeMapperServiceObject(tenant);
        MapperServiceRepresentation mapperServiceRepresentation = objectMapper
                .convertValue(mapperServiceMOR, MapperServiceRepresentation.class);
        addMapperServiceRepresentation(tenant, mapperServiceRepresentation);
        return mapperServiceRepresentation;
    }

    public MicroserviceCredentials getMicroserviceCredential(String tenant) {
        return microserviceCredentials.get(tenant);
    }

    public void createGraalsResources(String tenant, ServiceConfiguration serviceConfiguration) {
        Engine eng = Engine.newBuilder()
                .option("engine.WarnInterpreterOnly", "false")
                .build();

        graalEngines.put(tenant, eng);
        // graalSourceShared.put(tenant,
        // decodeCode(serviceConfiguration.getCodeTemplates()
        // .get(TemplateType.SHARED.name()).getCode(), "sharedCode.js", false, null));
        // graalSourceSystem.put(tenant,
        // decodeCode(serviceConfiguration.getCodeTemplates()
        // .get(TemplateType.SYSTEM.name()).getCode(), "systemCode.js", false, null));
        // graalSourceMapping.put(tenant, new ConcurrentHashMap<>());
    }

    public Engine getGraalEngine(String tenant) {
        return graalEngines.get(tenant);
    }

    // public void updateGraalsSourceShared(String tenant, String code) {
    // graalSourceShared.put(tenant, decodeCode(code, "sharedCode.js", false,
    // null));
    // }
    //
    // public Source getGraalsSourceShared(String tenant) {
    // return graalSourceShared.get(tenant);
    // }
    //
    // public void updateGraalsSourceSystem(String tenant, String code) {
    // graalSourceSystem.put(tenant, decodeCode(code, "systemCode.js", false,
    // null));
    // }
    //
    // public Source getGraalsSourceSystem(String tenant) {
    // return graalSourceSystem.get(tenant);
    // }
    //
    // public void updateGraalsSourceMapping(String tenant, String mappingId, String
    // code) {
    // graalSourceMapping.get(tenant).put(mappingId, decodeCode(code, mappingId +
    // ".js", true, mappingId));
    // }
    //
    // public Source getGraalsSourceMapping(String tenant, String mappingId) {
    // return graalSourceMapping.get(tenant).get(mappingId);
    // }
    //
    // public void removeGraalsSourceMapping(String tenant, String mappingId) {
    // graalSourceMapping.get(tenant).remove(mappingId);
    // }

    public void removeGraalsResources(String tenant) {
        graalEngines.remove(tenant);
        // graalSourceShared.remove(tenant);
        // graalSourceSystem.remove(tenant);
        // graalSourceMapping.remove(tenant);
    }

    public ServiceConfiguration getServiceConfiguration(String tenant) {
        return serviceConfigurations.get(tenant);
    }

    public void addServiceConfiguration(String tenant, ServiceConfiguration configuration) {
        serviceConfigurations.put(tenant, configuration);
    }

    public void removeServiceConfiguration(String tenant) {
        serviceConfigurations.remove(tenant);
    }

    public void addMapperServiceRepresentation(String tenant,
            MapperServiceRepresentation mapperServiceRepresentation) {
        mapperServiceRepresentations.put(tenant, mapperServiceRepresentation);
    }

    public MapperServiceRepresentation getMapperServiceRepresentation(String tenant) {
        return mapperServiceRepresentations.get(tenant);
    }

    public void removeMapperServiceRepresentation(String tenant) {
        mapperServiceRepresentations.remove(tenant);
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

    public void addMicroserviceCredentials(String tenant, MicroserviceCredentials credentials) {
        microserviceCredentials.put(tenant, credentials);
    }

    public void removeMicroserviceCredentials(String tenant) {
        microserviceCredentials.remove(tenant);
    }

    public void addPayloadProcessorInbound(String tenant, MappingType mappingType,
            BaseProcessorInbound<?> payloadProcessorInbound) {
        payloadProcessorsInbound.get(tenant).put(mappingType, payloadProcessorInbound);
    }

    public Map<MappingType, BaseProcessorInbound<?>> getPayloadProcessorsInbound(String tenant) {
        return payloadProcessorsInbound.get(tenant);
    }

    public void removePayloadProcessorsInbound(String tenant) {
        payloadProcessorsInbound.remove(tenant);
    }

    public Map<MappingType, BaseProcessorOutbound<?>> getPayloadProcessorsOutbound(String tenant,
            String connectorIdentifier) {
        return payloadProcessorsOutbound.get(tenant).get(connectorIdentifier);
    }

    public void addPayloadProcessorOutbound(String tenant, String connectorIdentifier, MappingType mappingType,
            BaseProcessorOutbound<?> payloadProcessorOutbound) {
        payloadProcessorsOutbound.get(tenant).get(connectorIdentifier).put(mappingType, payloadProcessorOutbound);
    }

    public void removePayloadProcessorsOutbound(String tenant) {
        payloadProcessorsOutbound.remove(tenant);
    }

    public void initializeOutboundMapping(String tenant, ServiceConfiguration serviceConfiguration,
            AConnectorClient connectorClient) {
        if (serviceConfiguration.isOutboundMappingEnabled()
                && connectorClient.supportedDirections().contains(Direction.OUTBOUND)) {
            // initialize AsynchronousDispatcherOutbound
            initializePayloadProcessorsOutbound(connectorClient);
            DispatcherOutbound dispatcherOutbound = new DispatcherOutbound(
                    this, connectorClient);
            // Only initialize Connectors which are enabled
            if (connectorClient.getConnectorConfiguration().isEnabled())
                getNotificationSubscriber().addConnector(tenant,
                        connectorClient.getConnectorIdentifier(),
                        dispatcherOutbound);
            // Subscriber must be new initialized for the new added connector
            // configurationRegistry.getNotificationSubscriber().notificationSubscriberReconnect(tenant);
        }
    }

    public HostAccess getHostAccess() {
        if (hostAccess == null) {
            // Create a custom HostAccess configuration
            // SubstitutionContext public methods and basic collection operations
            // Create a HostAccess instance with the desired configuration
            // Allow access to public members of accessible classes
            // Allow array access for basic functionality
            // Allow List operations
            // Allow Map operations
            hostAccess = HostAccess.newBuilder()
                    // Allow access to public members of accessible classes
                    .allowPublicAccess(true)
                    // Allow array access for basic functionality
                    .allowArrayAccess(true)
                    // Allow List operations
                    .allowListAccess(true)
                    // Allow Map operations
                    .allowMapAccess(true)
                    .build();
            // log.info("HostAccess created with public access, array access, list access,
            // and map access enabled.");

        }
        return hostAccess;
    }

    public void addClient(String tenant, String deviceId, String clientId) {
        deviceToClientPerTenant.get(tenant).put(deviceId, clientId);
    }

    public void removeClient(String tenant,String clientId) {
        deviceToClientPerTenant.get(tenant).values().removeIf(value -> value.equals(clientId));
    }

    public void clearCacheDeviceToClient(String tenant) {
        deviceToClientPerTenant.put(tenant, new ConcurrentHashMap<>());
    }
    
    public String resolveDeviceToClient(String tenant, String deviceId) {
        // TODO IMPLEMENTATION: Adjust if needed to map device to client ID
        if (deviceToClientPerTenant.get(tenant).containsKey(deviceId)) {
            return deviceToClientPerTenant.get(tenant).get(deviceId);
        } else {
            // TODO implement error handling
            return deviceId;
        }
    }

}
