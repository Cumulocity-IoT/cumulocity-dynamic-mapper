package dynamic.mapper.mapping;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.TransformationType;

/** When a save of a mapping makes the recorded de-duplication keys stale (issue #692). */
class MappingServiceDeduplicationKeysTest {

    private static Mapping mapping(String filter, String code, TransformationType type) {
        Mapping m = new Mapping();
        m.setFilterMapping(filter);
        m.setCode(code);
        m.setTransformationType(type);
        return m;
    }

    private static final String FILTER = "$not($isDuplicate(id, 60))";

    @Test
    void aSaveThatChangesNothingRelevantKeepsTheKeys() {
        Mapping before = mapping(FILTER, "Y29kZQ==", TransformationType.SMART_FUNCTION);
        Mapping after = mapping(FILTER, "Y29kZQ==", TransformationType.SMART_FUNCTION);
        after.setName("renamed");
        after.setActive(false);
        assertFalse(MappingService.changesDeduplicationKeys(before, after));
    }

    @Test
    void aChangedFilterClearsTheKeys() {
        assertTrue(MappingService.changesDeduplicationKeys(
                mapping(FILTER, null, TransformationType.JSONATA),
                mapping("$not($isDuplicate(id & time, 60))", null, TransformationType.JSONATA)));
        assertTrue(MappingService.changesDeduplicationKeys(
                mapping(FILTER, null, TransformationType.JSONATA),
                mapping(null, null, TransformationType.JSONATA)));
    }

    @Test
    void aChangedScriptOrTransformationTypeClearsTheKeys() {
        assertTrue(MappingService.changesDeduplicationKeys(
                mapping(null, "YQ==", TransformationType.SMART_FUNCTION),
                mapping(null, "Yg==", TransformationType.SMART_FUNCTION)));
        assertTrue(MappingService.changesDeduplicationKeys(
                mapping(FILTER, null, TransformationType.JSONATA),
                mapping(FILTER, null, TransformationType.SMART_FUNCTION)));
    }

    @Test
    void anUnknownPreviousMappingClearsTheKeys() {
        assertTrue(MappingService.changesDeduplicationKeys(null, mapping(FILTER, null, TransformationType.JSONATA)));
    }
}
