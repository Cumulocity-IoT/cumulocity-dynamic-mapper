package dynamic.mapper.processor.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import dynamic.mapper.processor.util.PayloadClassifier.Result;
import dynamic.mapper.processor.util.PayloadClassifier.Type;

class PayloadClassifierTest {

    private static Result classify(String s) {
        return PayloadClassifier.classify(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void decodeUtf8ValidatesEncodingOnlyWhileStrictDecodeAlsoRequiresPrintableText() {
        byte[] withC1 = "a\u0085b".getBytes(StandardCharsets.UTF_8);
        assertEquals("a\u0085b", PayloadClassifier.decodeUtf8(withC1));
        assertNull(PayloadClassifier.decodeStrictUtf8(withC1));
        assertNull(PayloadClassifier.decodeUtf8(new byte[] { (byte) 0xC3, 0x28 }));
    }

    @Test
    void emptyAndNull() {
        assertEquals(Type.EMPTY, PayloadClassifier.classify(null).type());
        assertEquals(Type.EMPTY, PayloadClassifier.classify(new byte[0]).type());
    }

    @Test
    void json() {
        assertEquals(Type.JSON, classify("{\"a\":1}").type());
        assertEquals(Type.JSON, classify("  [1,2,3]\n").type());
    }

    @Test
    void invalidJsonAndScalarsAreText() {
        assertEquals(Type.TEXT, classify("{not json").type());
        assertEquals(Type.TEXT, classify("42").type());
        assertEquals(Type.TEXT, classify("hello").type());
    }

    @Test
    void xml() {
        assertEquals(Type.XML, classify("<a><b/></a>").type());
    }

    @Test
    void utf8BomIsStripped() {
        byte[] json = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[json.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(json, 0, withBom, 3, json.length);
        Result r = PayloadClassifier.classify(withBom);
        assertEquals(Type.JSON, r.type());
        assertEquals("{\"a\":1}", r.text());
    }

    @Test
    void invalidUtf8IsBinary() {
        // Latin-1 "é" is a lone 0xE9: a lenient decode would turn it into U+FFFD and call it text
        Result r = PayloadClassifier.classify(new byte[] { 'c', 'a', 'f', (byte) 0xE9 });
        assertTrue(r.isBinary());
        assertNull(r.text());
    }

    @Test
    void controlCharactersAreBinary() {
        assertTrue(PayloadClassifier.classify(new byte[] { 'a', 0x00, 'b' }).isBinary());
        assertTrue(PayloadClassifier.classify(new byte[] { 'a', 0x7f }).isBinary());
        assertFalse(classify("a\tb\r\nc").isBinary());
    }

    @Test
    void multiByteUtf8IsText() {
        assertEquals(Type.TEXT, classify("Grüße 温度").type());
    }

    @Test
    void binaryFormatFromMagicBytes() {
        assertEquals("GZIP", PayloadClassifier.classify(new byte[] { 0x1f, (byte) 0x8b, 0x08, 0x00 }).binaryFormat());
        assertEquals("ZIP", PayloadClassifier.classify(new byte[] { 0x50, 0x4b, 0x03, 0x04, 0x00 }).binaryFormat());
        assertEquals("AVRO", PayloadClassifier.classify(new byte[] { 'O', 'b', 'j', 0x01, 0x00 }).binaryFormat());
        assertNull(PayloadClassifier.classify(new byte[] { 0x0a, 0x00, (byte) 0xff }).binaryFormat());
    }

    @Test
    void describeForLogShowsTextAndSummarizesBinary() {
        assertEquals("{\"a\":1}", PayloadClassifier.describeForLog("{\"a\":1}".getBytes(StandardCharsets.UTF_8)));
        assertEquals("<binary 4 bytes, GZIP>",
                PayloadClassifier.describeForLog(new byte[] { 0x1f, (byte) 0x8b, 0x08, 0x00 }));
        assertEquals("<binary 3 bytes>", PayloadClassifier.describeForLog(new byte[] { 0x0a, 0x00, (byte) 0xff }));
        assertEquals("<null>", PayloadClassifier.describeForLog(null));
        String longText = "x".repeat(PayloadClassifier.LOG_PREVIEW_CHARS + 50);
        assertTrue(PayloadClassifier.describeForLog(longText.getBytes(StandardCharsets.UTF_8)).endsWith("bytes)"));
    }

    @Test
    void stripBomRemovesOnlyALeadingBom() {
        assertEquals("{}", PayloadClassifier.stripBom("\uFEFF{}"));
        assertEquals("{}", PayloadClassifier.stripBom("{}"));
        assertNull(PayloadClassifier.stripBom(null));
    }
}
