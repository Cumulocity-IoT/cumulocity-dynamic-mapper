package dynamic.mapper.connector.kafka;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import dynamic.mapper.connector.kafka.KafkaClientV2.KafkaConsumerWrapper;
import dynamic.mapper.model.Qos;

/**
 * After repeated processing errors the consumer is restarted by its subscription key. For a
 * wildcard subscription that key is the pattern, not the topic of the failing record.
 */
class KafkaClientV2RestartTest {

    private static final int ERROR_THRESHOLD = 10;

    private ExecutorService direct;
    private KafkaClientV2 client;

    @BeforeEach
    void setUp() throws Exception {
        direct = Executors.newSingleThreadExecutor();
        client = spy(new KafkaClientV2());
        ReflectionTestUtils.setField(client, "virtualThreadPool", direct);
        ReflectionTestUtils.setField(client, "tenant", "t1");
        // no real consumers: the restart only needs to be observed
        doNothing().when(client).unsubscribe(anyString());
        doNothing().when(client).subscribe(anyString(), any(Qos.class));
    }

    @AfterEach
    void tearDown() {
        direct.shutdownNow();
    }

    private ConsumerRecord<String, String> record(String topic) {
        return new ConsumerRecord<>(topic, 0, 1L, "k", "v");
    }

    private void failRepeatedly(ConsumerRecord<String, String> record, KafkaConsumerWrapper wrapper) {
        for (int i = 0; i <= ERROR_THRESHOLD; i++) {
            client.handleProcessingError(record, wrapper, 500);
        }
    }

    private void awaitRestartTasks() throws Exception {
        direct.submit(() -> { }).get(); // single thread: earlier restart tasks have finished
    }

    @Test
    @SuppressWarnings("unchecked")
    void wildcardSubscriptionIsRestartedByItsPatternNotTheRecordTopic() throws Exception {
        KafkaConsumerWrapper wrapper = new KafkaConsumerWrapper(mock(KafkaConsumer.class), "#");

        failRepeatedly(record("telemetry"), wrapper);
        awaitRestartTasks();

        verify(client).unsubscribe("#");
        verify(client).subscribe("#", Qos.AT_MOST_ONCE);
        verify(client, never()).unsubscribe("telemetry");
        verify(client, never()).subscribe("telemetry", Qos.AT_MOST_ONCE);
    }

    @Test
    @SuppressWarnings("unchecked")
    void literalSubscriptionIsRestartedByItsTopic() throws Exception {
        KafkaConsumerWrapper wrapper = new KafkaConsumerWrapper(mock(KafkaConsumer.class), "telemetry");

        failRepeatedly(record("telemetry"), wrapper);
        awaitRestartTasks();

        verify(client).unsubscribe("telemetry");
        verify(client).subscribe("telemetry", Qos.AT_MOST_ONCE);
    }

    @Test
    @SuppressWarnings("unchecked")
    void noRestartBelowThreshold() throws Exception {
        KafkaConsumerWrapper wrapper = new KafkaConsumerWrapper(mock(KafkaConsumer.class), "#");

        for (int i = 0; i < ERROR_THRESHOLD; i++) {
            client.handleProcessingError(record("telemetry"), wrapper, 500);
        }
        awaitRestartTasks();

        verify(client, never()).unsubscribe(anyString());
        verify(client, never()).subscribe(anyString(), any(Qos.class));
    }
}
