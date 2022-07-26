package mqttagent;

import com.cumulocity.microservice.autoconfigure.MicroserviceApplication;
import com.cumulocity.microservice.context.annotation.EnableContextSupport;
import com.cumulocity.microservice.context.inject.TenantScope;
import mqttagent.callbacks.GenericCallback;
import mqttagent.services.C8yAgent;
import mqttagent.services.MQTTClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import javax.annotation.PostConstruct;
import java.util.TimeZone;

@MicroserviceApplication
@EnableContextSupport
@SpringBootApplication
@EnableAsync
public class App {

    Logger logger = LoggerFactory.getLogger(App.class);

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

    public static void main(String[] args) {
        SpringApplication.run(App.class, args);
    }


}

