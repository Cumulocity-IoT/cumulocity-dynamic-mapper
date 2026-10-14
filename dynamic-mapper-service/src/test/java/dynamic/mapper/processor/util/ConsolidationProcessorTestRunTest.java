package dynamic.mapper.processor.util;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import dynamic.mapper.mapping.MappingService;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.status.MappingStatus;
import dynamic.mapper.processor.runtime.ProcessingContext;

/** A test run must never change a mapping's runtime status — including an inbound Live Test. */
class ConsolidationProcessorTestRunTest {

    private MappingService mappingService;
    private ConsolidationProcessor processor;
    private Mapping mapping;

    @BeforeEach
    void setUp() {
        mappingService = mock(MappingService.class);
        when(mappingService.getMappingStatus(any(), any(Mapping.class))).thenReturn(mock(MappingStatus.class));
        processor = new ConsolidationProcessor();
        ReflectionTestUtils.setField(processor, "mappingService", mappingService);
        mapping = Mapping.builder().identifier("m").build();
    }

    private ProcessingContext<Object> context(boolean testing, boolean testRun) {
        return ProcessingContext.<Object>builder().tenant("t1").mapping(mapping).testing(testing).testRun(testRun).build();
    }

    @Test
    void realRun_resetsTheFailureStreakAndRecordsTheOutcome() throws Exception {
        processor.process(context(false, false));

        verify(mappingService).resetFailureCountOnSuccess("t1", mapping);
        verify(mappingService).getMappingStatus("t1", mapping);
    }

    @Test
    void liveTest_realServicesButStillATestRun_touchesNothing() throws Exception {
        processor.process(context(false, true));

        verify(mappingService, never()).resetFailureCountOnSuccess(any(), any());
        verify(mappingService, never()).getMappingStatus(any(), any(Mapping.class));
    }

    @Test
    void dryRun_touchesNothing() throws Exception {
        processor.process(context(true, true));

        verify(mappingService, never()).resetFailureCountOnSuccess(any(), any());
        verify(mappingService, never()).getMappingStatus(any(), any(Mapping.class));
    }
}
