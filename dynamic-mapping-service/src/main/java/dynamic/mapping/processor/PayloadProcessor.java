package dynamic.mapping.processor;

import dynamic.mapping.connector.core.client.AConnectorClient;
import dynamic.mapping.processor.inbound.BasePayloadProcessorInbound;
import dynamic.mapping.processor.inbound.FlatFileProcessorInbound;
import dynamic.mapping.processor.inbound.GenericBinaryProcessorInbound;
import dynamic.mapping.processor.inbound.JSONProcessorInbound;
import dynamic.mapping.processor.outbound.BasePayloadProcessorOutbound;
import dynamic.mapping.processor.outbound.JSONProcessorOutbound;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import dynamic.mapping.core.ConfigurationRegistry;
import dynamic.mapping.processor.extension.ExtensibleProcessorInbound;
import dynamic.mapping.processor.model.MappingType;
import dynamic.mapping.processor.processor.fixed.StaticProtobufProcessor;

import java.util.Map;

@Slf4j
public class PayloadProcessor {
    @Setter
    private AConnectorClient connectorClient;
    @Getter
    private Map<MappingType, BasePayloadProcessorInbound<?>> payloadProcessorsInbound = null;
    @Getter
    private Map<MappingType, BasePayloadProcessorOutbound<?>> payloadProcessorsOutbound = null;

    public PayloadProcessor(ConfigurationRegistry configurationRegistry, AConnectorClient connectorClient,
            String tenant) {
        this.payloadProcessorsInbound = payloadProcessorsInbound(configurationRegistry, tenant);
        this.payloadProcessorsOutbound = payloadProcessorsOutbound(configurationRegistry, connectorClient, tenant);
    }

    public Map<MappingType, BasePayloadProcessorInbound<?>> payloadProcessorsInbound(
            ConfigurationRegistry configurationRegistry, String tenant) {
        ExtensibleProcessorInbound extensibleProcessor = configurationRegistry.getExtensibleProcessors().get(tenant);
        log.info("Tenant {} - payloadProcessorsInbound {}", tenant, extensibleProcessor);
        return Map.of(
                MappingType.JSON, new JSONProcessorInbound(configurationRegistry, tenant),
                MappingType.FLAT_FILE, new FlatFileProcessorInbound(configurationRegistry, tenant),
                MappingType.GENERIC_BINARY, new GenericBinaryProcessorInbound(configurationRegistry, tenant),
                MappingType.PROTOBUF_STATIC, new StaticProtobufProcessor(configurationRegistry, tenant),
                MappingType.PROCESSOR_EXTENSION, extensibleProcessor);
    }

    public Map<MappingType, BasePayloadProcessorOutbound<?>> payloadProcessorsOutbound(
            ConfigurationRegistry configurationRegistry, AConnectorClient connectorClient, String tenant) {
        return Map.of(
                MappingType.JSON, new JSONProcessorOutbound(configurationRegistry, connectorClient, tenant));
    }
}