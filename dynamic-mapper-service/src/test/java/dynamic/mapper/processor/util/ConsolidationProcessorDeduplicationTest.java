package dynamic.mapper.processor.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dynamic.mapper.core.ServiceRegistry;
import dynamic.mapper.mapping.MappingService;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.status.MappingStatus;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.inbound.MessageDeduplicationService;
import dynamic.mapper.processor.inbound.processor.FilterInboundProcessor;
import dynamic.mapper.processor.runtime.ProcessingContext;

/**
 * A key is recorded when the filter runs, and given back when the message does not end as delivered, so the
 * broker's retry of a failed message is not dropped as a repeat (issue #692).
 */
class ConsolidationProcessorDeduplicationTest {

    private MessageDeduplicationService store;
    private FilterInboundProcessor filter;
    private ConsolidationProcessor consolidation;
    private MappingStatus status;

    @BeforeEach
    void setUp() {
        store = new MessageDeduplicationService();
        filter = new FilterInboundProcessor(mock(ServiceRegistry.class), store);
        MappingService mappingService = mock(MappingService.class);
        status = new MappingStatus();
        when(mappingService.getMappingStatus(any(), any(Mapping.class))).thenReturn(status);
        consolidation = new ConsolidationProcessor(mappingService);
    }

    private static Mapping mapping(String filter) {
        return Mapping.builder().identifier("m1").name("m1").debug(false).filterMapping(filter).build();
    }

    private ProcessingContext<Object> filtered(Mapping m, Map<String, Object> payload) throws Exception {
        var ctx = ProcessingContext.<Object>builder().tenant("t").mapping(m).topic("a/b").payload(payload).build();
        filter.process(ctx);
        return ctx;
    }

    private static final String FILTER = "$not($isDuplicate(id, 60))";

    @Test
    void aMessageThatSucceedsKeepsItsKeyAndItsRepeatIsDropped() throws Exception {
        Mapping m = mapping(FILTER);
        consolidation.process(filtered(m, Map.of("id", "d1")));
        var repeat = filtered(m, Map.of("id", "d1"));
        assertTrue(repeat.isIgnoreFurtherProcessing());
        consolidation.process(repeat);
        assertTrue(repeat.isDroppedAsDuplicate());
    }

    @Test
    void aMessageThatFailsGivesItsKeyBackSoTheRetryIsProcessed() throws Exception {
        Mapping m = mapping(FILTER);
        var first = filtered(m, Map.of("id", "d1"));
        first.addError(new ProcessingException("send failed"));
        consolidation.process(first);

        var retry = filtered(m, Map.of("id", "d1"));
        assertFalse(retry.isIgnoreFurtherProcessing(), "the retry of a failed message is processed");
    }

    @Test
    void aRepeatThatIsDroppedDoesNotReleaseTheKeyOfTheOriginal() throws Exception {
        Mapping m = mapping(FILTER);
        consolidation.process(filtered(m, Map.of("id", "d1")));
        consolidation.process(filtered(m, Map.of("id", "d1"))); // dropped as a repeat
        assertTrue(filtered(m, Map.of("id", "d1")).isIgnoreFurtherProcessing());
    }

    @Test
    void aMessageRejectedByAnotherConditionGivesItsKeyBack() throws Exception {
        // $isDuplicate runs first here, so the key is recorded before the value condition rejects the message.
        Mapping m = mapping("$not($isDuplicate(id, 60)) and value > 10");
        var low = filtered(m, Map.of("id", "d1", "value", 5));
        assertTrue(low.isIgnoreFurtherProcessing());
        consolidation.process(low);

        var valid = filtered(m, Map.of("id", "d1", "value", 20));
        assertFalse(valid.isIgnoreFurtherProcessing(), "the rejected message left no key, so the order does not matter");
    }

    @Test
    void aFilterWithTwoKeysGivesBackTheNewKeyWhenTheSecondOneIsARepeat() throws Exception {
        Mapping m = mapping("$not($isDuplicate(a, 60)) and $not($isDuplicate(b, 60))");
        consolidation.process(filtered(m, Map.of("a", "a1", "b", "b1")));

        // records a2, then rejects the message because b1 is a repeat: nothing was delivered
        var rejected = filtered(m, Map.of("a", "a2", "b", "b1"));
        assertTrue(rejected.isDroppedAsDuplicate());
        consolidation.process(rejected);

        var later = filtered(m, Map.of("a", "a2", "b", "b2"));
        assertFalse(later.isIgnoreFurtherProcessing(), "a2 was given back, so a later message with it is processed");
        // the original message keeps its recording: a repeat of it is still dropped
        assertTrue(filtered(m, Map.of("a", "a1", "b", "b1")).isIgnoreFurtherProcessing());
    }

    @Test
    void aCancelledMessageGivesItsKeyBackEvenWithoutAnError() throws Exception {
        Mapping m = mapping(FILTER);
        var first = filtered(m, Map.of("id", "d1"));
        var wrapper = dynamic.mapper.processor.runtime.ProcessingResultWrapper.<Object>builder().build();
        wrapper.getCancellationRequested().set(true); // a timeout asked the send to stop; it returned without an error
        first.setProcessingResultWrapper(wrapper);
        assertFalse(first.hasError());
        consolidation.process(first);

        assertFalse(filtered(m, Map.of("id", "d1")).isIgnoreFurtherProcessing(),
                "the broker's redelivery of a message that was never sent is processed");
    }

    @Test
    void anInterruptedMessageGivesItsKeyBackEvenWithoutAnError() throws Exception {
        Mapping m = mapping(FILTER);
        var first = filtered(m, Map.of("id", "d1"));
        Thread.currentThread().interrupt();
        try {
            consolidation.process(first);
        } finally {
            Thread.interrupted(); // clear the flag, so it does not reach other tests
        }
        assertFalse(filtered(m, Map.of("id", "d1")).isIgnoreFurtherProcessing());
    }
}
