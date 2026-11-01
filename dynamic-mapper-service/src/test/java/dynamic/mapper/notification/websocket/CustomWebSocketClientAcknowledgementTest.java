package dynamic.mapper.notification.websocket;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dynamic.mapper.configuration.ConnectorId;
import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.core.ServiceRegistry;
import dynamic.mapper.model.DynamicMapperRequest;
import dynamic.mapper.model.Qos;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.runtime.ProcessingContext;
import dynamic.mapper.processor.runtime.ProcessingResultWrapper;
import dynamic.mapper.processor.util.ProcessingResultHelper;

/**
 * The outbound Notification 2.0 client acknowledges a notification only after the pipeline settled it, and
 * asks Cumulocity to redeliver (by reconnecting) otherwise, until the failure limit is reached.
 */
class CustomWebSocketClientAcknowledgementTest {

    private static final String ACK = "ack-1";
    private static final String RAW = ACK + "\n/t1/managedobjects\nCREATE\n\n{}";

    private NotificationCallback callback;
    private CustomWebSocketClient client;

    /** Runs submitted work on the calling thread, so the test can assert right after onMessage. */
    private static final class DirectExecutor extends AbstractExecutorService {
        @Override public void execute(Runnable command) { command.run(); }
        @Override public void shutdown() { }
        @Override public List<Runnable> shutdownNow() { return List.of(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
    }

    @BeforeEach
    void setUp() throws Exception {
        ServiceRegistry registry = mock(ServiceRegistry.class);
        ExecutorService direct = new DirectExecutor();
        when(registry.getVirtualThreadPool()).thenReturn(direct);
        ServiceConfiguration configuration = mock(ServiceConfiguration.class);
        when(configuration.getLogPayload()).thenReturn(false);
        when(configuration.getMaxProcessingAttempts()).thenReturn(3);
        when(registry.getServiceConfiguration("t1")).thenReturn(configuration);
        callback = mock(NotificationCallback.class);
        client = spy(new CustomWebSocketClient("t1", registry, new URI("ws://localhost:1"), callback,
                ConnectorId.INTERNAL));
        doNothing().when(client).send(anyString());
        doNothing().when(client).reconnect();
    }

    private void returning(Supplier<ProcessingResultWrapper<Object>> result) {
        when(callback.onNotification(any())).thenAnswer(inv -> result.get());
    }

    private ProcessingResultWrapper<Object> succeeded() {
        return ProcessingResultHelper.success(List.<ProcessingContext<Object>>of());
    }

    private ProcessingResultWrapper<Object> failed() {
        CompletableFuture<List<ProcessingContext<Object>>> future = new CompletableFuture<>();
        future.completeExceptionally(new IllegalStateException("boom"));
        return ProcessingResultHelper.successAsync(future, Qos.AT_LEAST_ONCE, 1000);
    }

    private ProcessingResultWrapper<Object> withRequestStatus(int status) {
        DynamicMapperRequest request = mock(DynamicMapperRequest.class);
        when(request.hasError()).thenReturn(true);
        when(request.getError()).thenReturn(new ProcessingException("publish failed", status));
        @SuppressWarnings("unchecked")
        ProcessingContext<Object> context = mock(ProcessingContext.class);
        when(context.getRequests()).thenReturn(List.of(request));
        ProcessingResultWrapper<Object> result = ProcessingResultHelper.success(List.of(context));
        result.setConsolidatedQos(Qos.AT_LEAST_ONCE);
        return result;
    }

    private ProcessingResultWrapper<Object> atLeastOnce(ProcessingResultWrapper<Object> result) {
        result.setConsolidatedQos(Qos.AT_LEAST_ONCE);
        return result;
    }

    @Test
    void acknowledgesAfterASuccessfulPipeline() {
        returning(() -> atLeastOnce(succeeded()));

        client.onMessage(RAW);

        verify(client).send(ACK);
        verify(client, never()).reconnect();
    }

    @Test
    void reconnectsAfterAFailedPipelineSoCumulocityRedelivers() {
        returning(() -> atLeastOnce(failed()));

        client.onMessage(RAW);

        verify(client).reconnect();
        verify(client, never()).send(ACK);
    }

    @Test
    void reconnectsAfterACancelledPipeline() {
        returning(() -> {
            ProcessingResultWrapper<Object> result = atLeastOnce(succeeded());
            result.getCancellationRequested().set(true); // a CPU timeout completed the future early
            return result;
        });

        client.onMessage(RAW);

        verify(client).reconnect();
        verify(client, never()).send(ACK);
    }

    @Test
    void aServerErrorWhilePublishingIsRetried() {
        returning(() -> withRequestStatus(503));

        client.onMessage(RAW);

        verify(client).reconnect();
        verify(client, never()).send(ACK);
    }

    @Test
    void aClientErrorWhilePublishingIsAcknowledged() {
        returning(() -> withRequestStatus(400));

        client.onMessage(RAW);

        verify(client).send(ACK);
        verify(client, never()).reconnect();
    }

    @Test
    void dropsTheNotificationAfterTheConfiguredAttempts() {
        returning(() -> atLeastOnce(failed())); // the limit is 3

        client.onMessage(RAW);
        client.onMessage(RAW);
        verify(client, never()).send(ACK);

        client.onMessage(RAW); // third failure: acknowledged and dropped

        verify(client, times(2)).reconnect();
        verify(client).send(ACK);
    }

    @Test
    void aSuccessForgetsEarlierFailures() {
        returning(() -> atLeastOnce(failed()));
        client.onMessage(RAW);
        client.onMessage(RAW);
        returning(() -> atLeastOnce(succeeded()));
        client.onMessage(RAW);

        returning(() -> atLeastOnce(failed()));
        client.onMessage(RAW);
        client.onMessage(RAW);

        verify(client, times(1)).send(ACK); // only the success acknowledged; the count restarted
        verify(client, times(4)).reconnect();
    }
}
