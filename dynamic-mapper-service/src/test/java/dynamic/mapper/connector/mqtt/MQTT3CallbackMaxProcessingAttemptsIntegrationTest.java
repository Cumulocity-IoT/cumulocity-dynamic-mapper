package dynamic.mapper.connector.mqtt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.datatypes.MqttTopic;
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish;

import dynamic.mapper.configuration.ConnectorConfiguration;
import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.callback.GenericMessageCallback;
import dynamic.mapper.configuration.ConnectorConfigurationService;
import dynamic.mapper.core.ServiceRegistry;
import dynamic.mapper.model.Qos;
import dynamic.mapper.processor.runtime.ProcessingContext;
import dynamic.mapper.processor.runtime.ProcessingResultWrapper;
import dynamic.mapper.processor.util.ProcessingResultHelper;

/**
 * The service setting {@code maxProcessingAttempts} through the real MQTT callback and the real
 * {@code MessageFailureTracker}: the broker redelivers a message that was not acknowledged, and the callback
 * drops it on the failure that reaches the limit.
 *
 * <p>The setting is 3. The first message fails twice (fewer attempts than the limit) and succeeds on the
 * third delivery, so it is processed and acknowledged once. The second message fails on every delivery: it is
 * redelivered until the third failure reaches the limit, then acknowledged and dropped, and the pipeline is
 * not run a fourth time.</p>
 */
class MQTT3CallbackMaxProcessingAttemptsIntegrationTest {

    private static final int MAX_PROCESSING_ATTEMPTS = 3;
    private static final String TENANT = "t1";

    private GenericMessageCallback dispatcher;
    private MQTT3Callback callback;

    @BeforeEach
    void setUp() throws Exception {
        ServiceConfiguration configuration = mock(ServiceConfiguration.class);
        when(configuration.getLogPayload()).thenReturn(false);
        when(configuration.getMaxProcessingAttempts()).thenReturn(MAX_PROCESSING_ATTEMPTS);

        // run the acknowledgement task on the calling thread so the test needs no waiting
        ExecutorService sameThread = mock(ExecutorService.class);
        when(sameThread.submit(any(Callable.class))).thenAnswer(inv -> {
            ((Callable<?>) inv.getArgument(0)).call();
            return CompletableFuture.completedFuture(null);
        });

        // no reconnect on a processing error: the broker is simulated by delivering the message again
        ConnectorConfiguration connectorConfiguration = mock(ConnectorConfiguration.class);
        when(connectorConfiguration.getProperties()).thenReturn(Map.of("reconnectOnProcessingError", false));
        ConnectorConfigurationService connectorConfigurationService = mock(ConnectorConfigurationService.class);
        when(connectorConfigurationService.getConnectorConfiguration("id", TENANT))
                .thenReturn(connectorConfiguration);

        ServiceRegistry serviceRegistry = mock(ServiceRegistry.class);
        when(serviceRegistry.getServiceConfiguration(TENANT)).thenReturn(configuration);
        when(serviceRegistry.getVirtualThreadPool()).thenReturn(sameThread);
        when(serviceRegistry.getConnectorConfigurationService()).thenReturn(connectorConfigurationService);

        dispatcher = mock(GenericMessageCallback.class);
        callback = new MQTT3Callback(TENANT, serviceRegistry, dispatcher, "id", "name", "client", () -> {
        });
    }

    private ProcessingResultWrapper<Object> succeeded() {
        return ProcessingResultHelper.success(List.<ProcessingContext<Object>>of());
    }

    private ProcessingResultWrapper<Object> failed() {
        CompletableFuture<List<ProcessingContext<Object>>> future = new CompletableFuture<>();
        future.completeExceptionally(new IllegalStateException("boom"));
        return ProcessingResultHelper.successAsync(future, Qos.AT_LEAST_ONCE, 1000);
    }

    private Mqtt3Publish publish(String topic, String payload) {
        Mqtt3Publish publish = mock(Mqtt3Publish.class);
        when(publish.getTopic()).thenReturn(MqttTopic.of(topic));
        when(publish.getPayload()).thenAnswer(inv -> Optional.of(java.nio.ByteBuffer.wrap(payload.getBytes())));
        when(publish.getQos()).thenReturn(MqttQos.AT_LEAST_ONCE);
        return publish;
    }

    @Test
    void aMessageThatSucceedsBeforeTheLimitIsProcessedAndAMessageThatKeepsFailingIsDropped() {
        // first message: attempts 1 and 2 fail (< 3), attempt 3 succeeds
        AtomicInteger firstDeliveries = new AtomicInteger();
        when(dispatcher.onMessage(any())).thenAnswer(inv ->
                firstDeliveries.incrementAndGet() < MAX_PROCESSING_ATTEMPTS ? failed() : succeeded());
        Mqtt3Publish first = publish("device/1/measurement", "{\"temp\":1}");

        callback.accept(first);
        verify(first, never()).acknowledge();
        callback.accept(first);
        verify(first, never()).acknowledge();
        callback.accept(first);

        assertEquals(MAX_PROCESSING_ATTEMPTS, firstDeliveries.get());
        verify(first, times(1)).acknowledge();

        // second message: every attempt fails, the redelivery that reaches the limit is dropped
        AtomicInteger secondDeliveries = new AtomicInteger();
        when(dispatcher.onMessage(any())).thenAnswer(inv -> {
            secondDeliveries.incrementAndGet();
            return failed();
        });
        Mqtt3Publish second = publish("device/2/measurement", "{\"temp\":\"poison\"}");

        for (int attempt = 1; attempt < MAX_PROCESSING_ATTEMPTS; attempt++) {
            callback.accept(second);
            verify(second, never()).acknowledge();
        }
        callback.accept(second);

        assertEquals(MAX_PROCESSING_ATTEMPTS, secondDeliveries.get());
        verify(second, times(1)).acknowledge();   // dropped on the failure that reaches the limit
        verify(first, times(1)).acknowledge();    // the first message is unaffected

        // the counter was cleared with the drop: the same payload gets a fresh count
        callback.accept(second);
        verify(second, times(1)).acknowledge();
        assertEquals(MAX_PROCESSING_ATTEMPTS + 1, secondDeliveries.get());
    }
}
