package dynamic.mapper.processor.outbound.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMethod;

import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.mapping.MappingService;
import dynamic.mapper.model.API;
import dynamic.mapper.model.DynamicMapperRequest;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.model.CumulocityType;
import dynamic.mapper.processor.model.DeviceMessage;
import dynamic.mapper.processor.model.MappingAction;
import dynamic.mapper.processor.runtime.OutputCollector;
import dynamic.mapper.processor.runtime.ProcessingContext;
import dynamic.mapper.processor.runtime.RoutingContext;

class ExtensibleResultOutboundProcessorTest {

    private final ExtensibleResultOutboundProcessor processor = new ExtensibleResultOutboundProcessor(
            mock(MappingService.class), mock(C8YAgent.class), new ObjectMapper());

    private List<DynamicMapperRequest> run(DeviceMessage message) throws Exception {
        Mapping mapping = Mapping.builder().identifier("m1").name("ext").externalIdType("c8y_Serial")
                .publishTopic("device/out").build();
        ProcessingContext<Object> context = ProcessingContext.<Object>builder()
                .tenant("t").mapping(mapping).sourceId("42").externalId("berlin_01")
                .extensionResult(new DeviceMessage[] { message }).build();
        OutputCollector output = new OutputCollector();
        processor.processExtensionResults(mock(RoutingContext.class), output, context);
        return output.getRequests();
    }

    @Test
    void theRequestHasAMethodSoTheWebhookConnectorCannotFailOnNull() throws Exception {
        DynamicMapperRequest r = run(DeviceMessage.forTopic("device/out").payload("{}").build()).get(0);

        assertEquals(RequestMethod.POST, r.getMethod());
        assertEquals("device/out", r.getPublishTopic());
        assertEquals("c8y_Serial", r.getExternalIdType());
        assertEquals("berlin_01", r.getExternalId());
        assertNull(r.getApi());
    }

    @Test
    void actionAndCumulocityTypeAreHonored() throws Exception {
        DeviceMessage message = DeviceMessage.forTopic("device/out").payload("{\"id\":\"7001\",\"text\":\"x\"}").build();
        message.setAction(MappingAction.UPDATE);
        message.setCumulocityType(CumulocityType.EVENT);

        DynamicMapperRequest r = run(message).get(0);

        assertEquals(RequestMethod.PUT, r.getMethod());
        assertEquals(API.EVENT, r.getApi());
        // the event's own id, not the source device (42)
        assertEquals(API.EVENT.path + "/7001", r.getPathCumulocity());
    }
}
