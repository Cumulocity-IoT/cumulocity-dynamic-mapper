package dynamic.mapper.processor.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ProcessingContextLiveTestIdTest {

    @Test
    void liveTestId_isUnchangedOutsideALiveTest() {
        ProcessingContext<Object> context = ProcessingContext.<Object>builder().build();

        assertEquals("device-1", context.liveTestId("device-1"));
        assertNull(context.liveTestId(null));
    }

    @Test
    void liveTestId_prefixesTheExternalIdInALiveTest() {
        ProcessingContext<Object> context = ProcessingContext.<Object>builder()
                .testExternalIdPrefix(ProcessingContext.TEST_EXTERNAL_ID_PREFIX).build();

        assertEquals("d11r_liveTest_device-1", context.liveTestId("device-1"));
        assertNull(context.liveTestId(null));
    }

    @Test
    void affectsMappingStatus_isOnlyTrueForARealRun() {
        assertEquals(true, ProcessingContext.<Object>builder().build().affectsMappingStatus());
        // Dry Run: mock services
        assertEquals(false, ProcessingContext.<Object>builder().testing(true).testRun(true).build().affectsMappingStatus());
        // inbound Live Test: real services (testing=false) but still a test run
        assertEquals(false, ProcessingContext.<Object>builder().testing(false).testRun(true).build().affectsMappingStatus());
        // outbound tests always run with testing=true
        assertEquals(false, ProcessingContext.<Object>builder().testing(true).build().affectsMappingStatus());
    }
}
