package dynamic.mapping.core;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapping.configuration.ConnectorConfigurationComponent;
import dynamic.mapping.configuration.ServiceConfiguration;
import dynamic.mapping.configuration.ServiceConfigurationComponent;
import dynamic.mapping.connector.core.client.AConnectorClient;
import dynamic.mapping.model.MappingServiceRepresentation;
import dynamic.mapping.notification.C8YNotificationSubscriber;
import dynamic.mapping.processor.extension.ExtensibleProcessorInbound;
import dynamic.mapping.processor.inbound.BasePayloadProcessorInbound;
import dynamic.mapping.processor.inbound.FlatFileProcessorInbound;
import dynamic.mapping.processor.inbound.GenericBinaryProcessorInbound;
import dynamic.mapping.processor.inbound.JSONProcessorInbound;
import dynamic.mapping.processor.model.MappingType;
import dynamic.mapping.processor.outbound.BasePayloadProcessorOutbound;
import dynamic.mapping.processor.outbound.JSONProcessorOutbound;
import dynamic.mapping.processor.processor.fixed.StaticProtobufProcessor;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class ConfigurationRegistry {

    // structure: <tenant, <mappingType, mappingServiceRepresentation>>
    @Getter
    private Map<String, MappingServiceRepresentation> mappingServiceRepresentations = new HashMap<>();

    // structure: <tenant, <mappingType, extensibleProcessorInbound>>
    @Getter
    private Map<String, Map<MappingType, BasePayloadProcessorInbound<?>>> payloadProcessorsInbound = new HashMap<>();

    // structure: <tenant, <connectorIdent, <mappingType,
    // extensibleProcessorOutbound>>>
    @Getter
    private Map<String, Map<String, Map<MappingType, BasePayloadProcessorOutbound<?>>>> payloadProcessorsOutbound = new HashMap<>();

    @Getter
    private Map<String, ServiceConfiguration> serviceConfigurations = new HashMap<>();

    // structure: <tenant, <extensibleProcessorInbound>>
    @Getter
    private Map<String, ExtensibleProcessorInbound> extensibleProcessors = new HashMap<>();

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
    public void setMappingComponent(@Lazy ConnectorConfigurationComponent connectorConfigurationComponent) {
        this.connectorConfigurationComponent = connectorConfigurationComponent;
    }

    @Getter
    public ServiceConfigurationComponent serviceConfigurationComponent;

    @Autowired
    public void setMappingComponent(@Lazy ServiceConfigurationComponent serviceConfigurationComponent) {
        this.serviceConfigurationComponent = serviceConfigurationComponent;
    }

    @Getter
    @Setter
    @Autowired
    private ExecutorService cachedThreadPool;

    public Map<MappingType, BasePayloadProcessorInbound<?>> createPayloadProcessorsInbound(String tenant) {
        ExtensibleProcessorInbound extensibleProcessor = getExtensibleProcessors().get(tenant);
        log.info("Tenant {} - payloadProcessorsInbound {}", tenant, extensibleProcessor);
        return Map.of(
                MappingType.JSON, new JSONProcessorInbound(this),
                MappingType.FLAT_FILE, new FlatFileProcessorInbound(this),
                MappingType.GENERIC_BINARY, new GenericBinaryProcessorInbound(this),
                MappingType.PROTOBUF_STATIC, new StaticProtobufProcessor(this),
                MappingType.PROCESSOR_EXTENSION, extensibleProcessor);
    }

    public Map<MappingType, BasePayloadProcessorOutbound<?>> createPayloadProcessorsOutbound(
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
        Map<String, Map<MappingType, BasePayloadProcessorOutbound<?>>> processorPerTenant = payloadProcessorsOutbound
                .get(connectorClient.getTenant());
        if (processorPerTenant == null) {
            // log.info("Tenant {} - HIER III {} {}", connectorClient.getTenant(),
            // processorPerTenant);
            processorPerTenant = new HashMap<>();
            payloadProcessorsOutbound.put(connectorClient.getTenant(), processorPerTenant);
        }
        if (!processorPerTenant.containsKey(connectorClient.getConnectorIdent())) {
            // log.info("Tenant {} - HIER VI {} {}", connectorClient.getTenant(),
            // processorPerTenant);
            processorPerTenant.put(connectorClient.getConnectorIdent(),
                    createPayloadProcessorsOutbound(connectorClient));
        }
    }
}
