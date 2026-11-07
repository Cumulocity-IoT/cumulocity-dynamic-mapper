package dynamic.mapper.connector.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

class KafkaMetadataTest {

    @Test
    void headersBecomeHeaderFields() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("t", 0, 0L, "k", "v");
        record.headers().add("source", "gw-1".getBytes(StandardCharsets.UTF_8));
        assertEquals(Map.of("header.source", "gw-1"), KafkaClientV2.kafkaMetadata(record));
    }

    @Test
    void noHeadersGivesNull() {
        assertNull(KafkaClientV2.kafkaMetadata(new ConsumerRecord<>("t", 0, 0L, "k", "v")));
    }
}
