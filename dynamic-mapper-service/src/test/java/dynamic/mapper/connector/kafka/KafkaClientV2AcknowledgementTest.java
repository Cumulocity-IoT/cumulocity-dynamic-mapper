package dynamic.mapper.connector.kafka;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import dynamic.mapper.connector.kafka.KafkaClientV2.KafkaConsumerWrapper;
import dynamic.mapper.model.Qos;
import dynamic.mapper.processor.runtime.ProcessingContext;
import dynamic.mapper.processor.runtime.ProcessingResultWrapper;
import dynamic.mapper.processor.util.ProcessingResultHelper;

/**
 * A Kafka record is settled (its offset may be committed) only when the pipeline result says so; the
 * poll loop seeks back to a record that returns {@code false}.
 */
class KafkaClientV2AcknowledgementTest {

    private KafkaClientV2 client;
    private final ConsumerRecord<String, String> record = new ConsumerRecord<>("t", 0, 5L, "k", "v");
    private KafkaConsumerWrapper wrapper;

    @BeforeEach
    void setUp() {
        client = spy(new KafkaClientV2());
        ReflectionTestUtils.setField(client, "tenant", "t1");
        Properties consumerProperties = new Properties();
        consumerProperties.put("enable.auto.commit", "false"); // the shipped default: the connector commits
        ReflectionTestUtils.setField(client, "kafkaConsumerProperties", consumerProperties);
        wrapper = spy(new KafkaConsumerWrapper(mock(KafkaConsumer.class), "t"));
        doNothing().when(client).handleProcessingError(any(), any(), anyInt());
    }

    @Test
    void aSuccessfulPipelineSettlesTheRecord() {
        ProcessingResultWrapper<Object> result = ProcessingResultHelper.success(List.<ProcessingContext<Object>>of());

        assertTrue(client.processMessageWithQos(record, wrapper, result, 0));
        verify(wrapper).requestCommit(new TopicPartition("t", 0), new OffsetAndMetadata(6L));
        verify(client, never()).handleProcessingError(any(), any(), anyInt());
    }

    @Test
    void aFailedPipelineDoesNotSettleTheRecord() {
        CompletableFuture<List<ProcessingContext<Object>>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("boom"));
        ProcessingResultWrapper<Object> result = ProcessingResultHelper.successAsync(failed, Qos.AT_LEAST_ONCE, 1000);

        assertFalse(client.processMessageWithQos(record, wrapper, result, 1000));
        verify(wrapper, never()).requestCommit(any(), any());
        verify(client).handleProcessingError(any(), any(), anyInt());
    }

    @Test
    void aCancelledPipelineDoesNotSettleTheRecord() {
        // a CPU timeout completes the future early with the flag set: the record was not processed
        ProcessingResultWrapper<Object> result = ProcessingResultHelper.success(List.<ProcessingContext<Object>>of());
        result.getCancellationRequested().set(true);

        assertFalse(client.processMessageWithQos(record, wrapper, result, 0));
        verify(wrapper, never()).requestCommit(any(), any());
    }

    @Test
    void aTimedOutPipelineDoesNotSettleTheRecordAndIsCancelled() {
        ProcessingResultWrapper<Object> result = ProcessingResultHelper.successAsync(
                new CompletableFuture<List<ProcessingContext<Object>>>(), Qos.AT_LEAST_ONCE, 50);

        assertFalse(client.processMessageWithQos(record, wrapper, result, 50));
        assertTrue(result.getCancellationRequested().get());
        verify(wrapper, never()).requestCommit(any(), any());
    }
}
