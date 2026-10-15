package dynamic.mapper.logs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;

/** On unless the operator switches it off; the retention job is there either way. */
class ServiceLogCollectorWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ServiceLogSink.class, () -> mock(ServiceLogSink.class))
            .withBean(ServiceLogSettingsProvider.class, () -> new TestSettings())
            .withBean(MicroserviceSubscriptionsService.class, () -> mock(MicroserviceSubscriptionsService.class))
            .withUserConfiguration(ServiceLogCapture.class, ServiceLogCollectorJob.class, ServiceLogRetentionJob.class);

    @Test
    void isOnWhenNothingIsConfiguredBecauseEveryValueHasADefaultInCode() {
        runner.run(ctx -> {
            assertEquals(1, ctx.getBeansOfType(ServiceLogCapture.class).size());
            assertEquals(1, ctx.getBeansOfType(ServiceLogCollectorJob.class).size());
            assertEquals(1, ctx.getBeansOfType(ServiceLogRetentionJob.class).size());
        });
    }

    @Test
    void theOperatorCanSwitchTheWholeFeatureOffButRetentionStillRunsToCleanUp() {
        runner.withPropertyValues("service.logs.enabled=false").run(ctx -> {
            assertEquals(0, ctx.getBeansOfType(ServiceLogCapture.class).size());
            assertEquals(0, ctx.getBeansOfType(ServiceLogCollectorJob.class).size());
            assertEquals(1, ctx.getBeansOfType(ServiceLogRetentionJob.class).size());
        });
    }
}
