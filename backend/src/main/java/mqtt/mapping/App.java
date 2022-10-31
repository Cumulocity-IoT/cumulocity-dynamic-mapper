package mqtt.mapping;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.cumulocity.microservice.autoconfigure.MicroserviceApplication;
import com.cumulocity.microservice.context.annotation.EnableContextSupport;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.joda.JodaModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import mqtt.mapping.core.C8yAgent;
import mqtt.mapping.model.InnerNode;
import mqtt.mapping.model.InnerNodeSerializer;
import mqtt.mapping.model.MappingNode;
import mqtt.mapping.model.MappingNodeSerializer;
import mqtt.mapping.model.TreeNode;
import mqtt.mapping.model.TreeNodeSerializer;
import mqtt.mapping.processor.MappingType;
import mqtt.mapping.processor.PayloadProcessor;
import mqtt.mapping.processor.impl.FlatFileProcessor;
import mqtt.mapping.processor.impl.GenericBinaryProcessor;
import mqtt.mapping.processor.impl.JSONProcessor;
import mqtt.mapping.service.MQTTClient;
import mqtt.mapping.service.RFC3339DateFormat;

@MicroserviceApplication
@EnableContextSupport
@SpringBootApplication
@EnableAsync
public class App {

    @Autowired
    C8yAgent c8yAgent;

    @Autowired
    MQTTClient mqttClient;

    @Bean
    public TaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(25);
        return executor;
    }

    @Bean("cachedThreadPool")
    public ExecutorService cachedThreadPool() {
        return Executors.newCachedThreadPool();
    }

    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.enable(SerializationFeature.WRITE_ENUMS_USING_TO_STRING);
        objectMapper.enable(DeserializationFeature.READ_ENUMS_USING_TO_STRING);
        objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        objectMapper.disable(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE);
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        objectMapper.setDateFormat(new RFC3339DateFormat());
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.registerModule(new JodaModule());

        SimpleModule module = new SimpleModule();
        module.addSerializer(TreeNode.class, new TreeNodeSerializer());
        module.addSerializer(InnerNode.class, new InnerNodeSerializer());
        module.addSerializer(MappingNode.class, new MappingNodeSerializer());
        objectMapper.registerModule(module);
        return objectMapper;
    }

    @Bean("payloadProcessors")
    public Map<MappingType, PayloadProcessor<?,?>> payloadProcessor(ObjectMapper objectMapper, MQTTClient mqttClient,
            C8yAgent c8yAgent) {
        return Map.of(
            MappingType.JSON, new JSONProcessor<String,String>(objectMapper, mqttClient, c8yAgent),
            MappingType.FLAT_FILE, new FlatFileProcessor<String,String>(objectMapper, mqttClient, c8yAgent),
            MappingType.GENERIC_BINARY, new GenericBinaryProcessor<byte[], String>(objectMapper, mqttClient, c8yAgent)
            );
    }

    public static void main(String[] args) {
        SpringApplication.run(App.class, args);
    }
}
