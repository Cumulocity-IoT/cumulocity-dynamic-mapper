package dynamic.mapper.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import com.cumulocity.model.ID;
import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;
import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.core.ServiceRegistry;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.model.DeviceMessage;
import dynamic.mapper.processor.model.ExternalId;
import dynamic.mapper.processor.runtime.ProcessingContext;
import dynamic.mapper.processor.util.ProcessingResultHelper;

/**
 * Outbound live test: the identity is mocked but the message is published to the real broker, so the
 * mock device must be registered under a prefixed external ID — otherwise the topic built from it
 * could address a real device.
 */
class CommonProcessorLiveTestIdTest {

    private final C8YAgent c8yAgent = mock(C8YAgent.class);
    private final CommonProcessor processor = new CommonProcessor() { };

    private DeviceMessage deviceMessage(String externalId) {
        DeviceMessage message = new DeviceMessage();
        List<ExternalId> sources = new ArrayList<>();
        sources.add(new ExternalId(externalId, "c8y_Serial"));
        message.setExternalSource(sources);
        return message;
    }

    private ProcessingContext<Object> context(String prefix) {
        return ProcessingContext.<Object>builder().tenant("t1").mapping(Mapping.builder().build())
                .testing(true).testRun(true).testExternalIdPrefix(prefix).build();
    }

    private void run(DeviceMessage message, ProcessingContext<Object> context, String registeredAs,
            ArgumentCaptor<ID> created) throws Exception {
        ServiceRegistry serviceRegistry = mock(ServiceRegistry.class);
        when(serviceRegistry.getObjectMapper()).thenReturn(new ObjectMapper());
        ReflectionTestUtils.setField(processor, "c8yAgent", c8yAgent);
        ReflectionTestUtils.setField(processor, "serviceRegistry", serviceRegistry);
        context.setSourceId("909090");
        ExternalIDRepresentation resolved = new ExternalIDRepresentation();
        resolved.setExternalId(registeredAs);
        when(c8yAgent.resolveGlobalId2ExternalId(eq("t1"), any(GId.class), any(), eq(true))).thenReturn(resolved);

        try (MockedStatic<ProcessingResultHelper> helper = mockStatic(ProcessingResultHelper.class)) {
            String routingId = processor.resolveGlobalId2ExternalId(message, context, "t1");
            helper.verify(() -> ProcessingResultHelper.createImplicitDevice(created.capture(), any(), any(), any(),
                    any()));
            assertEquals(registeredAs, routingId);
        }
    }

    @Test
    void liveTest_registersTheMockDeviceUnderThePrefixedExternalId() throws Exception {
        ArgumentCaptor<ID> created = ArgumentCaptor.forClass(ID.class);
        ProcessingContext<Object> context = context(ProcessingContext.TEST_EXTERNAL_ID_PREFIX);

        DeviceMessage message = deviceMessage("real-device-1");
        run(message, context, ProcessingContext.TEST_EXTERNAL_ID_PREFIX + "real-device-1", created);

        assertEquals(ProcessingContext.TEST_EXTERNAL_ID_PREFIX + "real-device-1", created.getValue().getValue());
        assertEquals(ProcessingContext.TEST_EXTERNAL_ID_PREFIX + "real-device-1", context.getExternalId());
        // computed per call, never written back: a second call must not prefix twice
        assertEquals("real-device-1", ((List<ExternalId>) message.getExternalSource()).get(0).getExternalId());
    }

    @Test
    void dryRun_keepsTheExternalIdUnchanged() throws Exception {
        ArgumentCaptor<ID> created = ArgumentCaptor.forClass(ID.class);
        ProcessingContext<Object> context = context(null);

        run(deviceMessage("real-device-1"), context, "real-device-1", created);

        assertEquals("real-device-1", created.getValue().getValue());
        assertEquals("real-device-1", context.getExternalId());
    }
}
