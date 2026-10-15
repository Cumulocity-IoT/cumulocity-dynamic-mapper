package dynamic.mapper.logs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;

/** The feature is off unless explicitly enabled, and on when it is. */
class ServiceLogCollectorWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ServiceLogSource.class, () -> mock(ServiceLogSource.class))
            .withBean(ServiceLogSink.class, () -> mock(ServiceLogSink.class))
            .withBean(MicroserviceSubscriptionsService.class, () -> mock(MicroserviceSubscriptionsService.class))
            .withUserConfiguration(ServiceLogCollectorJob.class);

    @Test
    void isOffByDefault() {
        runner.run(ctx -> assertEquals(0, ctx.getBeansOfType(ServiceLogCollectorJob.class).size()));
    }

    @Test
    void isOffWhenExplicitlyDisabled() {
        runner.withPropertyValues("service.logs.collector.enabled=false")
                .run(ctx -> assertEquals(0, ctx.getBeansOfType(ServiceLogCollectorJob.class).size()));
    }

    @Test
    void isOnWhenEnabled() {
        runner.withPropertyValues("service.logs.collector.enabled=true", "C8Y_BOOTSTRAP_TENANT=owner")
                .run(ctx -> assertEquals(1, ctx.getBeansOfType(ServiceLogCollectorJob.class).size()));
    }
}
