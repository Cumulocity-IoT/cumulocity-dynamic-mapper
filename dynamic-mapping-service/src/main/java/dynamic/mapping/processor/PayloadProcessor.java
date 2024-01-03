package dynamic.mapping.processor;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import dynamic.mapping.core.C8YAgent;
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

    public PayloadProcessor(ObjectMapper objectMapper, C8YAgent c8YAgent, String tenant, AConnectorClient connectorClient) {
        ExtensibleProcessorInbound extensibleProcessor = c8YAgent.getExtensibleProcessors().get(tenant);
        this.payloadProcessorsInbound = payloadProcessorsInbound(tenant, objectMapper, c8YAgent, extensibleProcessor);
        this.payloadProcessorsOutbound = payloadProcessorsOutbound(tenant, objectMapper, c8YAgent, connectorClient);
    }

    public Map<MappingType, BasePayloadProcessorInbound<?>> payloadProcessorsInbound(String tenant,
            ObjectMapper objectMapper,
            C8YAgent c8yAgent, ExtensibleProcessorInbound extensibleProcessor) {
        log.debug("Tenant {} - payloadProcessorsInbound {}", tenant, extensibleProcessor);
        return Map.of(
                MappingType.JSON, new JSONProcessorInbound(objectMapper, c8yAgent, tenant),
                MappingType.FLAT_FILE, new FlatFileProcessorInbound(objectMapper, c8yAgent, tenant),
                MappingType.GENERIC_BINARY, new GenericBinaryProcessorInbound(objectMapper, c8yAgent, tenant),
                MappingType.PROTOBUF_STATIC, new StaticProtobufProcessor(objectMapper, c8yAgent, tenant),
                MappingType.PROCESSOR_EXTENSION, extensibleProcessor);
    }

    public Map<MappingType, BasePayloadProcessorOutbound<?>> payloadProcessorsOutbound(String tenant,
            ObjectMapper objectMapper,
            C8YAgent c8yAgent, AConnectorClient connectorClient) {
        return Map.of(
                MappingType.JSON, new JSONProcessorOutbound(objectMapper, connectorClient, c8yAgent, tenant));
    }
}