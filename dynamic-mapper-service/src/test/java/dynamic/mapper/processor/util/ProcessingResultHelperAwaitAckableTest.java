package dynamic.mapper.processor.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cumulocity.sdk.client.SDKException;
import dynamic.mapper.model.DynamicMapperRequest;
import dynamic.mapper.model.Qos;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.util.ProcessingResultHelper.AckOutcome;
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

    // ---- errors recorded on requests (outbound publishing) ----

    private ProcessingResultWrapper<Object> withRequestError(Exception error) {
        DynamicMapperRequest request = mock(DynamicMapperRequest.class);
        when(request.hasError()).thenReturn(true);
        when(request.getError()).thenReturn(error);
        @SuppressWarnings("unchecked")
        ProcessingContext<Object> context = mock(ProcessingContext.class);
        when(context.hasError()).thenReturn(false);
        when(context.getRequests()).thenReturn(List.of(request));
        return ProcessingResultHelper.success(List.of(context));
    }

    @Test
    void requestErrorsAreIgnoredUnlessAskedFor() {
        ProcessingResultWrapper<Object> result = withRequestError(
                new ProcessingException("webhook failed", 503));

        assertTrue(ProcessingResultHelper.awaitAckable(result, "t", "topic", LOG),
                "inbound callers never looked at request errors and must not start to");
    }

    @Test
    void aServerErrorOnARequestIsRetriedWhenRequestErrorsCount() {
        ProcessingResultWrapper<Object> result = withRequestError(
                new ProcessingException("webhook failed", 503));

        assertEquals(AckOutcome.RETRY,
                ProcessingResultHelper.awaitOutcome(result, "t", "topic", LOG, null, true));
    }

    @Test
    void aClientErrorOnARequestIsAcknowledged() {
        ProcessingResultWrapper<Object> result = withRequestError(
                new ProcessingException("webhook rejected", 400));

        assertEquals(AckOutcome.ACK,
                ProcessingResultHelper.awaitOutcome(result, "t", "topic", LOG, null, true));
    }

    @Test
    void theStatusIsFoundAlongTheCauseChain() {
        ProcessingResultWrapper<Object> result = withRequestError(
                new RuntimeException("wrapped", new ProcessingException("webhook failed", 502)));

        assertEquals(AckOutcome.RETRY,
                ProcessingResultHelper.awaitOutcome(result, "t", "topic", LOG, null, true));
    }

    @Test
    void anSdkServerErrorOnARequestIsRetried() {
        ProcessingResultWrapper<Object> result = withRequestError(new SDKException(503, "unavailable"));

        assertEquals(AckOutcome.RETRY,
                ProcessingResultHelper.awaitOutcome(result, "t", "topic", LOG, null, true));
    }

    @Test
    void aRequestErrorWithoutAStatusIsAcknowledged() {
        ProcessingResultWrapper<Object> result = withRequestError(new IllegalStateException("mapping bug"));

        assertEquals(AckOutcome.ACK,
                ProcessingResultHelper.awaitOutcome(result, "t", "topic", LOG, null, true));
    }
}
