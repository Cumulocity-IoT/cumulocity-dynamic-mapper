package dynamic.mapper.processor.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;

class MappingCodeFingerprintTest {

    private static String b64(String js) {
        return Base64.getEncoder().encodeToString(js.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void hashFollowsTheCodeNotTheCachedEntry() {
        String a = b64("function onMessage(){return [];}");
        String b = b64("function onMessage(){return null;}");
        String hashA = MappingCodeFingerprint.sha256Hex("t", "fp-1", a);
        assertEquals(hashA, MappingCodeFingerprint.sha256Hex("t", "fp-1", new String(a)));
        // an edited mapping keeps its identifier: the hash must change with the code
        assertNotEquals(hashA, MappingCodeFingerprint.sha256Hex("t", "fp-1", b));
        assertEquals(64, hashA.length());
    }

    @Test
    void exportScanFollowsTheCode() {
        String plain = b64("function onMessage(){return [];}");
        String esm = b64("export function onMessage(){return [];}");
        assertFalse(MappingCodeFingerprint.containsExport("t", "fp-2", plain));
        assertFalse(MappingCodeFingerprint.containsExport("t", "fp-2", plain));
        assertTrue(MappingCodeFingerprint.containsExport("t", "fp-2", esm));
        assertFalse(MappingCodeFingerprint.containsExport("t", "fp-2", plain));
    }

    @Test
    void tenantsDoNotShareEntries() {
        String code = b64("export function onMessage(){}");
        assertTrue(MappingCodeFingerprint.containsExport("t1", "fp-3", code));
        assertFalse(MappingCodeFingerprint.containsExport("t2", "fp-3", b64("function onMessage(){}")));
    }
}
