package dynamic.mapper.processor.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMethod;

import dynamic.mapper.model.API;
import dynamic.mapper.model.DynamicMapperRequest;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.model.CumulocityType;
import dynamic.mapper.processor.model.DeviceMessage;
import dynamic.mapper.processor.runtime.ProcessingContext;

class OutboundRequestFinalizerTest {

    private static DynamicMapperRequest request(API api, RequestMethod method, String body) {
        return DynamicMapperRequest.builder().api(api).method(method).request(body).build();
    }

    @Test
    void putAddressesTheDeviceInThePathAndRemovesTheIdFromTheBody() {
        DynamicMapperRequest r = request(API.EVENT, RequestMethod.PUT, "{\"text\":\"x\"}");

        OutboundRequestFinalizer.finalizeRequest(r, "42", r.getRequest(), "t");

        assertEquals(API.EVENT.path + "/42", r.getPathCumulocity());
        assertFalse(r.getRequestCumulocity().contains("\"42\""));
        assertTrue(r.getRequestCumulocity().contains("\"text\""));
    }

    @Test
    void postUsesTheBasePathAndPutsTheSourceIdInTheBody() {
        DynamicMapperRequest r = request(API.EVENT, RequestMethod.POST, "{\"text\":\"x\"}");

        OutboundRequestFinalizer.finalizeRequest(r, "42", r.getRequest(), "t");

        assertEquals(API.EVENT.path, r.getPathCumulocity());
        assertTrue(r.getRequestCumulocity().contains("\"id\":\"42\""));
    }

    @Test
    void measurementsCannotBeUpdatedSoPutBecomesPost() {
        DynamicMapperRequest r = request(API.MEASUREMENT, RequestMethod.PUT, "{}");

        OutboundRequestFinalizer.finalizeRequest(r, "42", r.getRequest(), "t");

        assertEquals(RequestMethod.POST, r.getMethod());
        assertEquals(API.MEASUREMENT.path, r.getPathCumulocity());
    }

    @Test
    void withoutAnApiNothingIsChangedBeyondTheSourceId() {
        DynamicMapperRequest r = request(null, RequestMethod.POST, "{}");

        OutboundRequestFinalizer.finalizeRequest(r, "42", r.getRequest(), "t");

        assertEquals("42", r.getSourceId());
        assertNull(r.getPathCumulocity());
        assertNull(r.getRequestCumulocity());
    }

    @Test
    void apiComesFromTheCumulocityTypeBeforeTheTopic() {
        assertEquals(API.EVENT, OutboundRequestFinalizer.deriveApi(CumulocityType.EVENT, "measurements/x"));
        assertEquals(API.MEASUREMENT, OutboundRequestFinalizer.deriveApi(null, "measurements/x"));
        assertNull(OutboundRequestFinalizer.deriveApi(null, "device/out"));
        assertNull(OutboundRequestFinalizer.deriveApi(null, null));
    }

    @Test
    void transportFieldsSetTheKeyOnlyWhenPresentAndAlwaysTheRetainFlag() {
        ProcessingContext<Object> context = ProcessingContext.<Object>builder().tenant("t").build();
        context.setKey("kept");
        DeviceMessage message = DeviceMessage.forTopic("a").payload("{}").retain().build();
        message.setTransportFields(Map.of("other", "v", Mapping.CONTEXT_DATA_KEY_NAME,
                Mapping.CONTEXT_DATA_KEY_PLACEHOLDER));

        OutboundRequestFinalizer.applyTransportFields(context, message);

        assertEquals("kept", context.getKey());
        assertTrue(context.isRetain());

        message.setTransportFields(Map.of(Mapping.CONTEXT_DATA_KEY_NAME, "k1"));
        message.setRetain(null);
        OutboundRequestFinalizer.applyTransportFields(context, message);

        assertEquals("k1", context.getKey());
        assertFalse(context.isRetain());
    }
}
