package dynamic.mapper.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.cumulocity.sdk.client.SDKException;

class C8YAgentSendFailureTextTest {

    @Test
    void theReasonComesBeforeThePayloadSoItSurvivesTruncation() {
        String text = C8YAgent.sendFailureText("MEASUREMENT", "{\"c8y_TemperatureMeasurement\":{}}",
                new SDKException(422, "Unprocessable Entity"));

        assertEquals("Could not send payload to c8y (HTTP 422 - Unprocessable Entity): "
                + "MEASUREMENT/{\"c8y_TemperatureMeasurement\":{}}", text);
        assertTrue(text.indexOf("HTTP 422") < text.indexOf("{"));
    }
}
