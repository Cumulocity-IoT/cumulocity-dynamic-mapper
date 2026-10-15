package dynamic.mapper.logs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import dynamic.mapper.util.TenantLogContext;
import dynamic.mapper.util.TenantLogging;

/**
 * Uses the real logging framework: what is asserted here is what a deployed service would capture, tenant attribution
 * included.
 */
class ServiceLogCaptureAppenderTest {

    private BoundedServiceLogBuffer buffer;
    private ServiceLogCaptureAppender appender;
    private Logger logger;

    @BeforeAll
    static void registerConverters() {
        TenantLogging.register(); // what App.main does: the tenant-aware %m
    }

    @BeforeEach
    void attach() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        buffer = new BoundedServiceLogBuffer(1000);
        appender = new ServiceLogCaptureAppender(buffer, new ServiceLogPolicy("WARN", 500, 65536, List.of()));
        appender.setContext(context);
        appender.start();
        logger = context.getLogger("dynamic.mapper.test.capture");
        logger.setAdditive(false);
        logger.setLevel(Level.TRACE);
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
        appender.stop();
        MDC.clear();
    }

    @Test
    void aTenantsLineIsCapturedWithItsTenantAndTheConsoleFormat() {
        try (var scope = TenantLogContext.open("tenantA")) {
            logger.warn("Connector {} failed", "mqtt-1");
        }

        List<ServiceLogRecord> records = buffer.drainAll().get("tenantA");

        assertEquals(1, records.size());
        assertEquals("WARN", records.get(0).level());
        assertEquals("tenantA", records.get(0).tenant());
        String text = records.get(0).text();
        assertTrue(text.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z\\s+WARN \\[.*\\] dynamic\\.mapper\\.test\\.capture : Connector mqtt-1 failed"), text);
        // the text says "Z", so it must really be UTC: it has to be the very instant the record carries
        assertEquals(records.get(0).time(), Instant.parse(text.substring(0, text.indexOf(' '))));
    }

    @Test
    void theTenantPrefixTheCodeWritesByHandIsNotRepeatedInTheStoredLine() {
        try (var scope = TenantLogContext.open("tenantA")) {
            logger.error("tenantA - Could not connect");
        }

        String text = buffer.drainAll().get("tenantA").get(0).text();

        assertTrue(text.endsWith(" : Could not connect"), text);
    }

    @Test
    void aLineWithoutATenantIsNeverCaptured() {
        logger.error("global error");
        try (var scope = TenantLogContext.open("tenantA")) {
            logger.error("tenantB - looks like another tenant's line but is logged for tenantA");
        }

        Map<String, List<ServiceLogRecord>> drained = buffer.drainAll();

        assertEquals(java.util.Set.of("tenantA"), drained.keySet(), "the unattributed line went nowhere");
        assertEquals(1, drained.get("tenantA").size());
        assertNull(drained.get("tenantB"), "attribution is the MDC, never the text");
    }

    @Test
    void theSdksOwnTenantKeyIsHonouredToo() {
        MDC.put(TenantLogContext.SDK_KEY, "tenantFromSdk");
        logger.warn("inside an SDK scope");

        assertEquals(1, buffer.drainAll().get("tenantFromSdk").size());
    }

    @Test
    void linesBelowTheMinimumLevelAreNotCaptured() {
        try (var scope = TenantLogContext.open("tenantA")) {
            logger.info("info with a device payload");
            logger.debug("debug");
        }

        assertTrue(buffer.drainAll().isEmpty());
    }

    @Test
    void theCollectionPathNeverCapturesItself() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger own = context.getLogger("dynamic.mapper.logs.ServiceLogCollector");
        own.setAdditive(false);
        own.addAppender(appender);
        try (var scope = TenantLogContext.open("tenantA")) {
            own.error("collector failed");
        } finally {
            own.detachAppender(appender);
        }

        assertTrue(buffer.drainAll().isEmpty());
    }

    @Test
    void aStackTraceStaysWithItsLine() {
        try (var scope = TenantLogContext.open("tenantA")) {
            logger.error("failed", new IllegalStateException("boom"));
        }

        String text = buffer.drainAll().get("tenantA").get(0).text();

        assertTrue(text.contains("java.lang.IllegalStateException: boom"), text);
        assertTrue(text.contains("\tat ") || text.contains("\n\tat") || text.contains("at dynamic.mapper"), text);
    }

    @Test
    void linesLoggedConcurrentlyForDifferentTenantsNeverMix() throws Exception {
        int perTenant = 200;
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new java.util.ArrayList<>();
        for (String tenant : List.of("tenantA", "tenantB", "tenantC")) {
            threads.add(Thread.ofVirtual().unstarted(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    return;
                }
                try (var scope = TenantLogContext.open(tenant)) {
                    for (int i = 0; i < perTenant; i++) {
                        logger.warn("line of {} number {}", tenant, i);
                    }
                }
            }));
        }
        threads.forEach(Thread::start);
        start.countDown();
        for (Thread t : threads) {
            t.join();
        }

        Map<String, List<ServiceLogRecord>> drained = buffer.drainAll();

        for (String tenant : List.of("tenantA", "tenantB", "tenantC")) {
            assertEquals(perTenant, drained.get(tenant).size());
            for (ServiceLogRecord r : drained.get(tenant)) {
                assertTrue(r.text().contains("line of " + tenant + " "), tenant + " got: " + r.text());
                assertFalse(r.text().contains("line of " + otherThan(tenant)), r.text());
            }
        }
    }

    private static String otherThan(String tenant) {
        return tenant.equals("tenantA") ? "tenantB" : "tenantA";
    }
}
