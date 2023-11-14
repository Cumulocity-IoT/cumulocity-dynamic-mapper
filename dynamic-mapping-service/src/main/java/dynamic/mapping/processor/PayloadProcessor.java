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
import dynamic.mapping.core.C8YAgent;
import dynamic.mapping.processor.extension.ExtensibleProcessorInbound;
import dynamic.mapping.processor.model.MappingType;
import dynamic.mapping.processor.processor.fixed.StaticProtobufProcessor;

import java.util.Map;


public class PayloadProcessor {
    private C8YAgent c8YAgent;
    private ObjectMapper mapper;
    private String tenant;
    private AConnectorClient connectorClient;
    @Getter
    private Map<MappingType, BasePayloadProcessorInbound<?>> payloadProcessorsInbound = null;
    @Getter
    private Map<MappingType, BasePayloadProcessorOutbound<?>> payloadProcessorsOutbound = null;

    public PayloadProcessor(ObjectMapper mapper, C8YAgent c8YAgent, String tenant, AConnectorClient connectorClient) {
       this.payloadProcessorsInbound = payloadProcessorsInbound(mapper, c8YAgent, tenant);
       this.payloadProcessorsOutbound = payloadProcessorsOutbound(mapper, connectorClient, c8YAgent, tenant);
    }

    public Map<MappingType, BasePayloadProcessorInbound<?>> payloadProcessorsInbound(ObjectMapper objectMapper,
                                                                              C8YAgent c8yAgent, String tenant) {
        return Map.of(
                MappingType.JSON, new JSONProcessorInbound(objectMapper, c8yAgent, tenant),
                MappingType.FLAT_FILE, new FlatFileProcessorInbound(objectMapper, c8yAgent, tenant),
                MappingType.GENERIC_BINARY, new GenericBinaryProcessorInbound(objectMapper, c8yAgent, tenant),
                MappingType.PROTOBUF_STATIC, new StaticProtobufProcessor(objectMapper, c8yAgent, tenant),
                MappingType.PROCESSOR_EXTENSION, new ExtensibleProcessorInbound(objectMapper, c8yAgent, tenant));
    }

    public Map<MappingType, BasePayloadProcessorOutbound<?>> payloadProcessorsOutbound(ObjectMapper objectMapper, AConnectorClient connectorClient,
                                                                                       C8YAgent c8yAgent, String tenant) {
        return Map.of(
                MappingType.JSON, new JSONProcessorOutbound(objectMapper, connectorClient, c8yAgent, tenant));
    }
}
