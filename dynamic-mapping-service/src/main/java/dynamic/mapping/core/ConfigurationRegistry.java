package dynamic.mapping.core;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapping.configuration.ServiceConfiguration;
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
    @Autowired
    private ObjectMapper objectMapper;

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

    @Autowired
    public void setObjectMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Map<MappingType, BasePayloadProcessorInbound<?>> createPayloadProcessorsInbound(String tenant) {
        ExtensibleProcessorInbound extensibleProcessor = getExtensibleProcessors().get(tenant);
        log.info("Tenant {} - payloadProcessorsInbound {}", tenant, extensibleProcessor);
        return Map.of(
                MappingType.JSON, new JSONProcessorInbound(this, tenant),
                MappingType.FLAT_FILE, new FlatFileProcessorInbound(this, tenant),
                MappingType.GENERIC_BINARY, new GenericBinaryProcessorInbound(this, tenant),
                MappingType.PROTOBUF_STATIC, new StaticProtobufProcessor(this, tenant),
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
