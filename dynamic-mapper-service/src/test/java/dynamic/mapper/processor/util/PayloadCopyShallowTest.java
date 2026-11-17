package dynamic.mapper.processor.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class PayloadCopyShallowTest {

    @Test
    void topLevelIsPrivateNestedIsShared() {
        Map<String, Object> nested = new LinkedHashMap<>(Map.of("a", 1));
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("id", "1");
        source.put("nested", nested);
        source.put("list", List.of(1, 2));

        Map<String, Object> copy = PayloadCopy.shallowCopy(source);
        copy.put("_IDENTITY_", "x"); // what enrichment does

        assertNotSame(source, copy);
        assertFalse(source.containsKey("_IDENTITY_"), "enrichment tokens must not reach the shared source");
        assertSame(nested, copy.get("nested"));
        assertEquals(List.of("id", "nested", "list", "_IDENTITY_"), List.copyOf(copy.keySet()));
    }

    @Test
    void nonMapFallsBackToDeepCopy() {
        List<Object> source = List.of(Map.of("a", 1));
        List<Object> copy = PayloadCopy.shallowCopy(source);
        assertNotSame(source, copy);
        assertNotSame(source.get(0), copy.get(0));
    }
}
