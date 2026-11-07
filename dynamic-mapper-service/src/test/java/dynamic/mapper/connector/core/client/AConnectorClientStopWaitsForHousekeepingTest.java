package dynamic.mapper.connector.core.client;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import dynamic.mapper.configuration.ConnectorId;
import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.mapping.MappingService;

/**
 * On unsubscribe the tenant's resources are removed right after the connector stopped. That is only safe when
 * {@code stopHousekeepingAndClose} also waits for a housekeeping cycle that is already running; this was the cause
 * of "Error during housekeeping: null" in multi-tenant deployments.
 */
class AConnectorClientStopWaitsForHousekeepingTest {

    private static final String TENANT = "test-tenant";

    static class SlowHousekeepingConnector extends AConnectorClientSubscriptionInitRetryTest.TestableConnector {
        final CountDownLatch started = new CountDownLatch(1);
        final AtomicBoolean finished = new AtomicBoolean();

        @Override
        protected void connectorSpecificHousekeeping(String tenant) {
            started.countDown();
            long end = System.currentTimeMillis() + 500;
            while (System.currentTimeMillis() < end) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    // a cycle in the middle of its work does not stop on interrupt (blocking I/O, SDK calls)
                }
            }
            finished.set(true);
        }
    }

    @Test
    void stopHousekeepingAndCloseReturnsOnlyAfterTheRunningCycleFinished() throws Exception {
        ConnectorRegistry registry = mock(ConnectorRegistry.class);
        when(registry.getConnectorStatusMap(TENANT)).thenReturn(new HashMap<>());
        MappingService mappingService = mock(MappingService.class);
        ServiceConfiguration config = mock(ServiceConfiguration.class);

        SlowHousekeepingConnector connector = new SlowHousekeepingConnector();
        connector.tenant = TENANT;
        connector.connectorName = "c";
        connector.connectorIdentifier = "c1";
        connector.connectorId = new ConnectorId("c", "c1");
        connector.connectorRegistry = registry;
        connector.mappingService = mappingService;
        connector.serviceConfiguration = config;
        connector.initializeManagers();

        Field f = AConnectorClient.class.getDeclaredField("housekeepingExecutor");
        f.setAccessible(true);
        ScheduledThreadPoolExecutor executor = (ScheduledThreadPoolExecutor) f.get(connector);
        Method run = AConnectorClient.class.getDeclaredMethod("runHousekeeping");
        run.setAccessible(true);
        executor.execute(() -> {
            try {
                run.invoke(connector);
            } catch (Exception ignored) {
                // failures inside the cycle are not what this test is about
            }
        });
        assertTrue(connector.started.await(5, TimeUnit.SECONDS), "housekeeping cycle did not start");

        connector.stopHousekeepingAndClose();

        assertTrue(connector.finished.get(), "stop returned while the housekeeping cycle was still running");
    }
}
