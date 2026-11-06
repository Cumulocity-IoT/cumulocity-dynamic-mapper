package dynamic.mapper.processor.inbound.deserializer;

import java.io.IOException;

import com.dashjoin.jsonata.json.Json;

import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.util.PayloadClassifier;

public class JSONPayloadDeserializer implements PayloadDeserializer<Object> {

    @Override
    public Object deserializePayload(Mapping mapping, ConnectorMessage message) throws IOException {
        byte[] bytes = message.getPayload();
        if (bytes == null || bytes.length == 0) {
            throw new IOException("Payload is null or empty");
        }

        // Strict decode (also strips a UTF-8 BOM): a lenient decode would turn binary or Latin-1
        // bytes into U+FFFD and report a misleading JSON syntax error further down.
        String text = PayloadClassifier.decodeStrictUtf8(bytes);
        if (text == null) {
            String format = PayloadClassifier.detectBinaryFormat(bytes);
            throw new IOException("Payload is not UTF-8 text (" + bytes.length + " bytes"
                    + (format != null ? ", " + format : "")
                    + "), a JSON mapping cannot read it - use an Any Payload mapping for binary data");
        }
        try {
            return Json.parseJson(text);
        } catch (Exception e) {
            throw new IOException("Failed to deserialize JSON payload: " + e.getMessage(), e);
        }
    }
}
