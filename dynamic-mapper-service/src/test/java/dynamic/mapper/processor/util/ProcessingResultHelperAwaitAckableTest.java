package dynamic.mapper.processor.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dynamic.mapper.model.Qos;
import dynamic.mapper.processor.runtime.ProcessingContext;
import dynamic.mapper.processor.runtime.ProcessingResultWrapper;

/**
 * Connectors without their own result handling (AMQP, HTTP, Kafka) acknowledge only when
 * {@link ProcessingResultHelper#awaitAckable} says so.
 */
class ProcessingResultHelperAwaitAckableTest {

    private static final Logger LOG = LoggerFactory.getLogger(ProcessingResultHelperAwaitAckableTest.class);

    @Test
    void acknowledgesWhenThePipelineSucceeded() {
        ProcessingResultWrapper<byte[]> wrapper = ProcessingResultHelper.success(List.<ProcessingContext<byte[]>>of());

        assertTrue(ProcessingResultHelper.awaitAckable(wrapper, "t", "topic", LOG));
    }

    @Test
    void acknowledgesAMessageTheDispatcherSkipped() {
        // no processing future: nothing was dispatched, so redelivery would never help
        assertTrue(ProcessingResultHelper.awaitAckable(ProcessingResultHelper.failure(), "t", "topic", LOG));
        assertTrue(ProcessingResultHelper.awaitAckable(null, "t", "topic", LOG));
    }

    @Test
    void doesNotAcknowledgeWhenThePipelineFailed() {
        CompletableFuture<List<ProcessingContext<byte[]>>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("boom"));
        ProcessingResultWrapper<byte[]> wrapper = ProcessingResultHelper.successAsync(failed, Qos.AT_LEAST_ONCE, 1000);

        assertFalse(ProcessingResultHelper.awaitAckable(wrapper, "t", "topic", LOG));
    }

    @Test
    void doesNotAcknowledgeAndCancelsWhenThePipelineTimesOut() {
        CompletableFuture<List<ProcessingContext<byte[]>>> never = new CompletableFuture<>();
        ProcessingResultWrapper<byte[]> wrapper = ProcessingResultHelper.successAsync(never, Qos.AT_LEAST_ONCE, 50);

        assertFalse(ProcessingResultHelper.awaitAckable(wrapper, "t", "topic", LOG));
        assertTrue(wrapper.getCancellationRequested().get(), "a timed out pipeline must be cancelled");
    }

    @Test
    void doesNotAcknowledgeACancelledPipeline() {
        ProcessingResultWrapper<byte[]> wrapper = ProcessingResultHelper.success(List.<ProcessingContext<byte[]>>of());
        wrapper.getCancellationRequested().set(true);

        assertFalse(ProcessingResultHelper.awaitAckable(wrapper, "t", "topic", LOG));
    }
}
