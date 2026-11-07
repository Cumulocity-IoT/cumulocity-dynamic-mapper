package dynamic.mapper.connector.amqp;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Envelope;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.callback.GenericMessageCallback;
import dynamic.mapper.core.ServiceRegistry;
import dynamic.mapper.model.Qos;
import dynamic.mapper.processor.runtime.ProcessingContext;
import dynamic.mapper.processor.runtime.ProcessingResultWrapper;
import dynamic.mapper.processor.util.ProcessingResultHelper;
import jakarta.jms.BytesMessage;

/**
 * AMQP messages are acknowledged only after the pipeline succeeded, and a failure leaves them
 * unacknowledged (AMQP 1.0) or requeues them (AMQP 0-9-1).
 */
class AMQPCallbacksAcknowledgementTest {

    private ServiceRegistry serviceRegistry;
    private GenericMessageCallback dispatcher;

    @BeforeEach
    void setUp() {
        serviceRegistry = mock(ServiceRegistry.class);
        ServiceConfiguration configuration = mock(ServiceConfiguration.class);
        when(configuration.getLogPayload()).thenReturn(false);
        when(configuration.getMaxProcessingAttempts()).thenReturn(3);
        when(serviceRegistry.getServiceConfiguration(any())).thenReturn(configuration);
        dispatcher = mock(GenericMessageCallback.class);
    }

    private ProcessingResultWrapper<Object> succeeded() {
        return ProcessingResultHelper.success(List.<ProcessingContext<Object>>of());
    }

    private ProcessingResultWrapper<Object> failed() {
        CompletableFuture<List<ProcessingContext<Object>>> future = new CompletableFuture<>();
        future.completeExceptionally(new IllegalStateException("boom"));
        return ProcessingResultHelper.successAsync(future, Qos.AT_LEAST_ONCE, 1000);
    }

    // ---- AMQP 0-9-1 ----

    private void deliver(AMQPCallback callback, Channel channel) throws Exception {
        Envelope envelope = new Envelope(7L, false, "exchange", "a.b");
        callback.handleDelivery("tag", envelope, new AMQP.BasicProperties(), "x".getBytes());
    }

    @Test
    void amqp091AcksAfterASuccessfulPipeline() throws Exception {
        Channel channel = mock(Channel.class);
        when(dispatcher.onMessage(any())).thenReturn((ProcessingResultWrapper) succeeded());

        deliver(new AMQPCallback(channel, false, "t1", serviceRegistry, dispatcher, "id", "name"), channel);

        verify(channel).basicAck(7L, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void amqp091NacksWithRequeueAfterAFailedPipeline() throws Exception {
        Channel channel = mock(Channel.class);
        when(dispatcher.onMessage(any())).thenReturn((ProcessingResultWrapper) failed());

        deliver(new AMQPCallback(channel, false, "t1", serviceRegistry, dispatcher, "id", "name"), channel);

        verify(channel).basicNack(7L, false, true);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void amqp091SendsNeitherAckNorNackForAnAutoAckConsumer() throws Exception {
        Channel channel = mock(Channel.class);
        when(dispatcher.onMessage(any())).thenReturn((ProcessingResultWrapper) failed());

        deliver(new AMQPCallback(channel, true, "t1", serviceRegistry, dispatcher, "id", "name"), channel);

        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void amqp091DropsAMessageAfterTheConfiguredAttempts() throws Exception {
        // the redelivery of a message has a new delivery tag but the same message id
        Channel channel = mock(Channel.class);
        when(dispatcher.onMessage(any())).thenAnswer(inv -> failed());
        AMQPCallback callback = new AMQPCallback(channel, false, "t1", serviceRegistry, dispatcher, "id", "name");
        AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder().messageId("poison").build();

        for (long tag = 1; tag <= 3; tag++) {
            callback.handleDelivery("tag", new Envelope(tag, true, "exchange", "a.b"), properties, "x".getBytes());
        }

        verify(channel).basicNack(1L, false, true);
        verify(channel).basicNack(2L, false, true);
        verify(channel).basicAck(3L, false);   // third failure: dropped
        verify(channel, never()).basicNack(3L, false, true);
    }

    // ---- AMQP 1.0 ----

    private final jakarta.jms.Session session = mock(jakarta.jms.Session.class);

    private BytesMessage jmsMessage() throws Exception {
        BytesMessage message = mock(BytesMessage.class);
        when(message.getBodyLength()).thenReturn(1L);
        return message;
    }

    @Test
    void amqp10AcknowledgesAfterASuccessfulPipeline() throws Exception {
        BytesMessage message = jmsMessage();
        when(dispatcher.onMessage(any())).thenReturn((ProcessingResultWrapper) succeeded());

        new AMQP10Callback("t1", serviceRegistry, dispatcher, "id", "name", "addr", Qos.AT_LEAST_ONCE, session)
                .onMessage(message);

        verify(message).acknowledge();
    }

    @Test
    void amqp10DoesNotAcknowledgeAfterAFailedPipeline() throws Exception {
        BytesMessage message = jmsMessage();
        when(dispatcher.onMessage(any())).thenReturn((ProcessingResultWrapper) failed());

        new AMQP10Callback("t1", serviceRegistry, dispatcher, "id", "name", "addr", Qos.AT_LEAST_ONCE, session)
                .onMessage(message);

        verify(message, never()).acknowledge();
        verify(session).recover();
    }

    @Test
    void amqp10DropsTheMessageAfterTheConfiguredAttempts() throws Exception {
        BytesMessage message = jmsMessage();
        when(message.getJMSMessageID()).thenReturn("ID:1");
        when(dispatcher.onMessage(any())).thenReturn((ProcessingResultWrapper) failed());
        AMQP10Callback callback = new AMQP10Callback("t1", serviceRegistry, dispatcher, "id", "name", "addr",
                Qos.AT_LEAST_ONCE, session);

        for (int i = 0; i < dynamic.mapper.configuration.ServiceConfiguration.DEFAULT_MAX_PROCESSING_ATTEMPTS; i++) {
            callback.onMessage(message);
        }

        verify(session, org.mockito.Mockito.times(
                dynamic.mapper.configuration.ServiceConfiguration.DEFAULT_MAX_PROCESSING_ATTEMPTS - 1)).recover();
        verify(message).acknowledge();
    }

    @Test
    void amqp10AtMostOnceAcknowledgesBeforeDispatch() throws Exception {
        BytesMessage message = jmsMessage();
        when(dispatcher.onMessage(any())).thenReturn((ProcessingResultWrapper) failed());

        new AMQP10Callback("t1", serviceRegistry, dispatcher, "id", "name", "addr", Qos.AT_MOST_ONCE, session)
                .onMessage(message);

        verify(message).acknowledge();
    }
}
