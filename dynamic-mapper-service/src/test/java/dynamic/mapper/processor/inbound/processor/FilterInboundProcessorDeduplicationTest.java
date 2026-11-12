package dynamic.mapper.processor.inbound.processor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.Map;

import org.junit.jupiter.api.Test;

import dynamic.mapper.core.ServiceRegistry;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.inbound.MessageDeduplicationService;
import dynamic.mapper.processor.runtime.ProcessingContext;

/** The {@code $isDuplicate(key, windowSeconds)} function of the mapping filter (issue #692). */
class FilterInboundProcessorDeduplicationTest {

    private final MessageDeduplicationService store = new MessageDeduplicationService();
    private final FilterInboundProcessor processor = new FilterInboundProcessor(mock(ServiceRegistry.class), store);

    private static Mapping mapping(String filter) {
        return Mapping.builder().identifier("m1").name("m1").debug(false).filterMapping(filter).build();
    }

    private static ProcessingContext<Object> context(Mapping mapping, Object payload) {
        return ProcessingContext.<Object>builder().tenant("t").mapping(mapping).topic("a/b").payload(payload).build();
    }

    private boolean dropped(Mapping m, Object payload) throws Exception {
        var ctx = context(m, payload);
        processor.process(ctx);
        return ctx.isIgnoreFurtherProcessing();
    }

    private static final String FILTER = "$not($isDuplicate(id & '|' & time, 60))";

    @Test
    void dropsTheSecondCopyWithTheSameKey() throws Exception {
        Mapping m = mapping(FILTER);
        assertFalse(dropped(m, Map.of("id", "d1", "time", "t1", "seq", 1)));
        assertTrue(dropped(m, Map.of("id", "d1", "time", "t1", "seq", 2)), "fields outside the key are ignored");
        var third = context(m, Map.of("id", "d1", "time", "t1"));
        processor.process(third);
        assertTrue(third.isDroppedAsDuplicate(), "the context says why it was dropped");
    }

    @Test
    void differentKeyIsNotADuplicate() throws Exception {
        Mapping m = mapping(FILTER);
        dropped(m, Map.of("id", "d1", "time", "t1"));
        assertFalse(dropped(m, Map.of("id", "d1", "time", "t2")));
    }

    @Test
    void wholePayloadKeyCatchesExactRepeats() throws Exception {
        Mapping m = mapping("$not($isDuplicate($string($), 60))");
        dropped(m, Map.of("v", 1));
        assertTrue(dropped(m, Map.of("v", 1)));
        assertFalse(dropped(m, Map.of("v", 2)));
    }

    @Test
    void missingKeyFailsOpen() throws Exception {
        Mapping m = mapping("$not($isDuplicate(nothere, 60))");
        dropped(m, Map.of("v", 1));
        assertFalse(dropped(m, Map.of("v", 1)));
    }

    @Test
    void invalidWindowFailsOpen() throws Exception {
        Mapping m = mapping("$not($isDuplicate(id, 'x'))");
        dropped(m, Map.of("id", "d1"));
        assertFalse(dropped(m, Map.of("id", "d1")));
    }

    @Test
    void combinesWithOtherConditions() throws Exception {
        Mapping m = mapping("value > 10 and $not($isDuplicate(id, 60))");
        assertTrue(dropped(m, Map.of("id", "d1", "value", 5)), "rejected by the other condition");
        assertFalse(dropped(m, Map.of("id", "d1", "value", 20)), "the rejected message was not recorded");
        assertTrue(dropped(m, Map.of("id", "d1", "value", 20)));
    }

    @Test
    void testRunsNeverRecord() throws Exception {
        Mapping m = mapping(FILTER);
        var first = context(m, Map.of("id", "d1", "time", "t1"));
        first.setTestRun(true);
        processor.process(first);
        assertFalse(first.isIgnoreFurtherProcessing());
        assertFalse(dropped(m, Map.of("id", "d1", "time", "t1")), "the test run left no fingerprint");
    }
}
