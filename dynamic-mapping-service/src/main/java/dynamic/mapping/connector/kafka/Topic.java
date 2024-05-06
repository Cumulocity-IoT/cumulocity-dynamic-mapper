package dynamic.mapping.connector.kafka;

import org.apache.commons.lang3.SerializationUtils;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Arrays;
import java.util.Properties;

@Slf4j
public class Topic implements AutoCloseable {
    private final TopicConfig topicConfig;

    private final Consumer<byte[], byte[]> consumer;

    public Topic(final TopicConfig topicConfig) {
        this.topicConfig = topicConfig;

        final Properties props = SerializationUtils.clone(topicConfig.getDefaultPropertiesConsumer());

        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, topicConfig.getBootstrapServers());
        props.put("group.id", topicConfig.getGroupId());
        // this is a common topic consumer, so we just pull byte arrays and pass them
        // to a listener, we don't do any decoding in here
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        String jaasTemplate = "org.apache.kafka.common.security.scram.ScramLoginModule required username=\"%s\" password=\"%s\";";
        String jaasCfg = String.format(jaasTemplate, topicConfig.getUsername(), topicConfig.getPassword());
        props.put("sasl.jaas.config", jaasCfg);

        consumer = new KafkaConsumer<>(props);
        try {
            consumer.partitionsFor(topicConfig.getTopic()); // just to check connectivity immediately
        } catch (final Exception e) {
            try {
                consumer.close();
            } catch (final Exception ignore) {
            }
            throw e;
        }
    }

    /**
     * We can exit from this method only by an exception. Most important cases:
     * 1. org.apache.kafka.common.errors.WakeupException - if we call close() method
     * 2. org.apache.kafka.common.errors.InterruptException - if the current thread
     * has
     * been interrupted
     * 
     * @see KafkaConsumer#poll(Duration)
     *
     * @param listener
     */
    public void consumeUntilError(final TopicEventListener listener) {
        consumer.subscribe(Arrays.asList(topicConfig.getTopic()));

        while (true) {
            final ConsumerRecords<byte[], byte[]> records = consumer.poll(Duration.ofSeconds(10));
            for (ConsumerRecord<byte[], byte[]> record : records) {
                try {
                    Object key = record.key();
                    Object event = record.value();
                    byte[] keyByte;
                    byte[] eventByte;
                    if (key instanceof String) {
                        keyByte = ((String) key).getBytes();
                    } else {
                        keyByte = record.key();
                    }
                    if (event instanceof String) {
                        eventByte = ((String) event).getBytes();
                    } else {
                        eventByte = record.key();
                    }
                    listener.onEvent(keyByte, eventByte);
                } catch (final InterruptedException e) { // can be thrown by a blocking operation inside onEvent()
                    throw new org.apache.kafka.common.errors.InterruptException(e);
                } catch (final Exception error) {
                    // just log ("Unexpected error while listener.onEvent() notification", e)
                    // don't corrupt the consuming loop because of
                    // an error in a listener
                    log.error("Tenant {} - Failed to process message on topic {} with error: ", topicConfig.getTenant(),
                            topicConfig.getTopic(),
                            error);
                }
            }
        }
    }

    @Override
    public void close() {
        try {
            consumer.wakeup();
        } finally {
            consumer.close();
        }
    }
}
