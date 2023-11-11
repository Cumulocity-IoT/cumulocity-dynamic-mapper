package mqtt.mapping.processor;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import mqtt.mapping.connector.mqtt.AConnectorClient;
import mqtt.mapping.core.C8YAgent;
import mqtt.mapping.processor.extension.ExtensibleProcessorInbound;
import mqtt.mapping.processor.inbound.BasePayloadProcessor;
import mqtt.mapping.processor.inbound.FlatFileProcessor;
import mqtt.mapping.processor.inbound.GenericBinaryProcessor;
import mqtt.mapping.processor.inbound.JSONProcessor;
import mqtt.mapping.processor.model.MappingType;
import mqtt.mapping.processor.outbound.BasePayloadProcessorOutbound;
import mqtt.mapping.processor.outbound.JSONProcessorOutbound;
import mqtt.mapping.processor.processor.fixed.StaticProtobufProcessor;

import java.util.Map;


public class PayloadProcessor {
    private C8YAgent c8YAgent;
    private ObjectMapper mapper;
    private String tenant;
    private AConnectorClient connectorClient;
    @Getter
    private Map<MappingType, BasePayloadProcessor<?>> payloadProcessorsInbound = null;
    @Getter
    private Map<MappingType, BasePayloadProcessorOutbound<?>> payloadProcessorsOutbound = null;

    public PayloadProcessor(ObjectMapper mapper, C8YAgent c8YAgent, String tenant, AConnectorClient connectorClient) {
       this.payloadProcessorsInbound = payloadProcessorsInbound(mapper, c8YAgent, tenant);
       this.payloadProcessorsOutbound = payloadProcessorsOutbound(mapper, connectorClient, c8YAgent, tenant);
    }

    public Map<MappingType, BasePayloadProcessor<?>> payloadProcessorsInbound(ObjectMapper objectMapper,
                                                                              C8YAgent c8yAgent, String tenant) {
        return Map.of(
                MappingType.JSON, new JSONProcessor(objectMapper, c8yAgent, tenant),
                MappingType.FLAT_FILE, new FlatFileProcessor(objectMapper, c8yAgent, tenant),
                MappingType.GENERIC_BINARY, new GenericBinaryProcessor(objectMapper, c8yAgent, tenant),
                MappingType.PROTOBUF_STATIC, new StaticProtobufProcessor(objectMapper, c8yAgent, tenant),
                MappingType.PROCESSOR_EXTENSION, new ExtensibleProcessorInbound(objectMapper, c8yAgent, tenant));
    }

    public Map<MappingType, BasePayloadProcessorOutbound<?>> payloadProcessorsOutbound(ObjectMapper objectMapper, AConnectorClient connectorClient,
                                                                                       C8YAgent c8yAgent, String tenant) {
        return Map.of(
                MappingType.JSON, new JSONProcessorOutbound(objectMapper, connectorClient, c8yAgent, tenant));
    }
}
