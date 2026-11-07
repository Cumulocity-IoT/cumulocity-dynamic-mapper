package dynamic.mapper.processor.inbound.deserializer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dynamic.mapper.connector.core.callback.ConnectorMessage;

class JSONPayloadDeserializerTest {

    private final JSONPayloadDeserializer deserializer = new JSONPayloadDeserializer();

    private static ConnectorMessage message(byte[] payload) {
        return ConnectorMessage.builder().payload(payload).build();
    }

    @Test
    void parsesJson() throws IOException {
        Object o = deserializer.deserializePayload(null, message("{\"a\":1}".getBytes(StandardCharsets.UTF_8)));
        assertEquals(1, ((Map<?, ?>) o).get("a"));
    }

    @Test
    void toleratesUtf8Bom() throws IOException {
        byte[] json = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[json.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(json, 0, withBom, 3, json.length);
        assertEquals(1, ((Map<?, ?>) deserializer.deserializePayload(null, message(withBom))).get("a"));
    }

    @Test
    void acceptsControlCharactersThatAreValidInsideAJsonString() throws IOException {
        // U+0085 and U+007F are valid unescaped characters in a JSON string; only the Explorer's
        // printable-text heuristic rejects them, and that must not apply to JSON parsing.
        String json = "{\"a\":\"x\u0085y\u007fz\"}";
        Object o = deserializer.deserializePayload(null, message(json.getBytes(StandardCharsets.UTF_8)));
        assertEquals("x\u0085y\u007fz", ((Map<?, ?>) o).get("a"));
    }

    @Test
    void binaryPayloadGetsAnActionableError() {
        IOException e = assertThrows(IOException.class,
                () -> deserializer.deserializePayload(null, message(new byte[] { 0x1f, (byte) 0x8b, 0x08, 0x00 })));
        assertTrue(e.getMessage().contains("GZIP"), e.getMessage());
        assertTrue(e.getMessage().contains("Any Payload"), e.getMessage());
    }

    @Test
    void emptyPayloadIsRejected() {
        assertThrows(IOException.class, () -> deserializer.deserializePayload(null, message(new byte[0])));
    }
}
