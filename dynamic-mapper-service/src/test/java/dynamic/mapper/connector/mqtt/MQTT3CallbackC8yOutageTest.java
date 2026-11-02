package dynamic.mapper.connector.mqtt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.cumulocity.sdk.client.SDKException;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.datatypes.MqttTopic;
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish;

import dynamic.mapper.configuration.ConnectorConfiguration;
import dynamic.mapper.configuration.ConnectorConfigurationService;
import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.callback.GenericMessageCallback;
import dynamic.mapper.core.ServiceRegistry;
import dynamic.mapper.model.Qos;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.runtime.ProcessingContext;
import dynamic.mapper.processor.runtime.ProcessingResultWrapper;

/**
 * Cumulocity is unavailable while MQTT messages arrive (the scenario of
 * {@code resources/testing/reliability/TEST-SETUP-C8Y-UNAVAILABLE.md}, Path E): the pipeline fails with a 502
 * from the platform. The broker is simulated by delivering the unacknowledged message again.
 */
class MQTT3CallbackC8yOutageTest {

    private static final int LIMIT = 3;
    private static final String TENANT = "t1";

    private final AtomicBoolean platformDown = new AtomicBoolean();
    private final AtomicInteger pipelineRuns = new AtomicInteger();
    private MQTT3Callback callback;

    @BeforeEach
    void setUp() throws Exception {
        ServiceConfiguration configuration = mock(ServiceConfiguration.class);
        when(configuration.getLogPayload()).thenReturn(false);
        when(configuration.getMaxProcessingAttempts()).thenReturn(LIMIT);

        ExecutorService sameThread = mock(ExecutorService.class);
        when(sameThread.submit(any(Callable.class))).thenAnswer(inv -> {
            ((Callable<?>) inv.getArgument(0)).call();
            return CompletableFuture.completedFuture(null);
        });

        ConnectorConfiguration connectorConfiguration = mock(ConnectorConfiguration.class);
        when(connectorConfiguration.getProperties()).thenReturn(Map.of("reconnectOnProcessingError", false));
        ConnectorConfigurationService connectorConfigurationService = mock(ConnectorConfigurationService.class);
        when(connectorConfigurationService.getConnectorConfiguration("id", TENANT)).thenReturn(connectorConfiguration);

        ServiceRegistry serviceRegistry = mock(ServiceRegistry.class);
        when(serviceRegistry.getServiceConfiguration(TENANT)).thenReturn(configuration);
        when(serviceRegistry.getVirtualThreadPool()).thenReturn(sameThread);
        when(serviceRegistry.getConnectorConfigurationService()).thenReturn(connectorConfigurationService);

        GenericMessageCallback dispatcher = mock(GenericMessageCallback.class);
        when(dispatcher.onMessage(any())).thenAnswer(inv -> {
            pipelineRuns.incrementAndGet();
            return platformDown.get() ? platformAnswers502() : succeeded();
        });
        callback = new MQTT3Callback(TENANT, serviceRegistry, dispatcher, "id", "name", "client", () -> {
        });
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private ProcessingResultWrapper<Object> result(boolean error) {
        ProcessingContext<Object> context = mock(ProcessingContext.class);
        when(context.hasError()).thenReturn(error);
        when(context.getErrors()).thenReturn(error
                ? List.of(new ProcessingException("Cumulocity down", new SDKException(502, "Bad Gateway")))
                : List.of());
        return ProcessingResultWrapper.<Object>builder()
                .processingResult((java.util.concurrent.Future) CompletableFuture.completedFuture(
                        Collections.singletonList(context)))
                .consolidatedQos(Qos.AT_LEAST_ONCE)
                .pipelineTimeoutMS(0)
                .build();
    }

    private ProcessingResultWrapper<Object> platformAnswers502() {
        return result(true);
    }

    private ProcessingResultWrapper<Object> succeeded() {
        return result(false);
    }

    private Mqtt3Publish publish(String topic, String payload) {
        Mqtt3Publish publish = mock(Mqtt3Publish.class);
        when(publish.getTopic()).thenReturn(MqttTopic.of(topic));
        when(publish.getPayload()).thenAnswer(inv -> Optional.of(ByteBuffer.wrap(payload.getBytes())));
        when(publish.getQos()).thenReturn(MqttQos.AT_LEAST_ONCE);
        return publish;
    }

    @Test
    void aMessageIsNotLostWhenThePlatformRecoversBeforeTheLimit() {
        Mqtt3Publish message = publish("device/1/measurement", "{\"temp\":1}");

        platformDown.set(true);
        for (int attempt = 1; attempt < LIMIT; attempt++) {   // outage: LIMIT - 1 failed deliveries
            callback.accept(message);
            verify(message, never()).acknowledge();
        }
        platformDown.set(false);                              // fault.off
        callback.accept(message);

        assertEquals(LIMIT, pipelineRuns.get());
        verify(message, times(1)).acknowledge();              // processed, not dropped
    }

    @Test
    void aMessageIsDroppedWhenTheOutageOutlastsTheLimit() {
        // characterizes the cost of the poison-pill guard: a 502 counts like any other failure
        Mqtt3Publish message = publish("device/1/measurement", "{\"temp\":2}");

        platformDown.set(true);
        for (int attempt = 1; attempt <= LIMIT; attempt++) {
            callback.accept(message);
        }
        platformDown.set(false);

        verify(message, times(1)).acknowledge();              // dropped on the failure that reaches the limit
        assertEquals(LIMIT, pipelineRuns.get());              // the pipeline never saw the recovery
    }

    @Test
    void messagesArrivingAfterTheOutageAreProcessedNormally() {
        Mqtt3Publish duringOutage = publish("device/1/measurement", "{\"temp\":3}");
        Mqtt3Publish afterOutage = publish("device/1/measurement", "{\"temp\":4}");

        platformDown.set(true);
        callback.accept(duringOutage);
        platformDown.set(false);
        callback.accept(afterOutage);

        verify(duringOutage, never()).acknowledge();          // waits for the broker to redeliver it
        verify(afterOutage, times(1)).acknowledge();
    }
}
