package dynamic.mapper.logs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;

/** The feature is off unless explicitly enabled, and complete when it is. */
class ServiceLogCollectorWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ServiceLogSink.class, () -> mock(ServiceLogSink.class))
            .withBean(MicroserviceSubscriptionsService.class, () -> mock(MicroserviceSubscriptionsService.class))
            .withUserConfiguration(ServiceLogCollectorConfiguration.class, ServiceLogCapture.class, ServiceLogCollectorJob.class,
                    ServiceLogRetentionJob.class);

    @Test
    void isOffByDefault() {
        runner.run(ctx -> {
            assertEquals(0, ctx.getBeansOfType(ServiceLogCollectorJob.class).size());
            assertEquals(0, ctx.getBeansOfType(ServiceLogCapture.class).size());
        });
    }

    @Test
    void isOffWhenExplicitlyDisabled() {
        runner.withPropertyValues("service.logs.collector.enabled=false")
                .run(ctx -> assertEquals(0, ctx.getBeansOfType(ServiceLogCapture.class).size()));
    }

    @Test
    void isCompleteWhenEnabledAndTheJobGetsTheCaptureAsItsBuffer() {
        runner.withPropertyValues("service.logs.collector.enabled=true").run(ctx -> {
            assertEquals(1, ctx.getBeansOfType(ServiceLogCollectorJob.class).size());
            assertEquals(1, ctx.getBeansOfType(ServiceLogCapture.class).size());
            assertEquals(1, ctx.getBeansOfType(ServiceLogPolicy.class).size());
        });
    }

    @Test
    void retentionRunsEvenWhenTheCollectorIsOffSoStoredLinesNeverPileUp() {
        runner.withPropertyValues("service.logs.collector.enabled=false").run(ctx -> {
            assertEquals(0, ctx.getBeansOfType(ServiceLogCollectorJob.class).size());
            assertEquals(1, ctx.getBeansOfType(ServiceLogRetentionJob.class).size());
        });
    }

    @Test
    void retentionCanBeSwitchedOff() {
        runner.withPropertyValues("service.logs.retention.enabled=false")
                .run(ctx -> assertEquals(0, ctx.getBeansOfType(ServiceLogRetentionJob.class).size()));
    }
}
