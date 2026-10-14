package dynamic.mapper.processor.outbound.processor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.core.ServiceRegistry;
import dynamic.mapper.mapping.MappingService;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.status.MappingStatus;
import dynamic.mapper.processor.runtime.ProcessingContext;

/**
 * The outbound error handlers used to count failures (and so could auto-deactivate the mapping) even
 * for a test run.
 */
class OutboundTestRunAccountingTest {

    private MappingService mappingService;
    private EnrichmentOutboundProcessor processor;
    private final Mapping mapping = Mapping.builder().identifier("m").build();
    private MappingStatus status;

    @BeforeEach
    void setUp() {
        mappingService = mock(MappingService.class);
        status = mock(MappingStatus.class);
        processor = new EnrichmentOutboundProcessor(mock(ServiceRegistry.class), mappingService,
                mock(C8YAgent.class), null);
    }

    private ProcessingContext<Object> context(boolean testing) {
        return ProcessingContext.<Object>builder().tenant("t1").mapping(mapping).testing(testing).testRun(testing).build();
    }

    @Test
    void realRun_countsTheFailure() {
        processor.handleEnrichmentError("t1", mapping, new RuntimeException("boom"), context(false), status);

        verify(status).incrementErrors();
        verify(mappingService).increaseAndHandleFailureCount("t1", mapping, status);
    }

    @Test
    void testRun_doesNotCountTheFailure() {
        processor.handleEnrichmentError("t1", mapping, new RuntimeException("boom"), context(true), status);

        verify(status, never()).incrementErrors();
        verify(mappingService, never()).increaseAndHandleFailureCount(any(), any(), any());
    }
}
