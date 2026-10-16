package dynamic.mapper.logs.capture;

import dynamic.mapper.logs.settings.TestSettings;

import dynamic.mapper.logs.collect.ServiceLogCollector;
import dynamic.mapper.logs.model.ServiceLogChunk;
import dynamic.mapper.logs.model.ServiceLogRecord;
import dynamic.mapper.logs.store.ServiceLogSink;

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
        buffer = new BoundedServiceLogBuffer();
        appender = new ServiceLogCaptureAppender(buffer, new TestSettings());
        appender.setContext(context);
        appender.start();
        logger = context.getLogger("dynamic.mapper.test.capture");
        logger.setAdditive(false);
        logger.setLevel(Level.TRACE);
        logger.addAppender(appender);
    }

    private Map<String, List<ServiceLogRecord>> drainAll() {
        return buffer.drain(List.of("tenantA", "tenantB", "tenantC", "tenantFromSdk"));
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

        List<ServiceLogRecord> records = drainAll().get("tenantA");

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

        String text = drainAll().get("tenantA").get(0).text();

        assertTrue(text.endsWith(" : Could not connect"), text);
    }

    @Test
    void aLineWithoutATenantIsNeverCaptured() {
        logger.error("global error");
        try (var scope = TenantLogContext.open("tenantA")) {
            logger.error("tenantB - looks like another tenant's line but is logged for tenantA");
        }

        Map<String, List<ServiceLogRecord>> drained = drainAll();

        assertEquals(java.util.Set.of("tenantA"), drained.keySet(), "the unattributed line went nowhere");
        assertEquals(1, drained.get("tenantA").size());
        assertNull(drained.get("tenantB"), "attribution is the MDC, never the text");
    }

    @Test
    void theSdksOwnTenantKeyIsHonouredToo() {
        MDC.put(TenantLogContext.SDK_KEY, "tenantFromSdk");
        logger.warn("inside an SDK scope");

        assertEquals(1, drainAll().get("tenantFromSdk").size());
    }

    @Test
    void linesBelowTheMinimumLevelAreNotCaptured() {
        try (var scope = TenantLogContext.open("tenantA")) {
            logger.info("info with a device payload");
            logger.debug("debug");
        }

        assertTrue(drainAll().isEmpty());
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

        assertTrue(drainAll().isEmpty());
    }

    @Test
    void aStackTraceStaysWithItsLine() {
        try (var scope = TenantLogContext.open("tenantA")) {
            logger.error("failed", new IllegalStateException("boom"));
        }

        String text = drainAll().get("tenantA").get(0).text();

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

        Map<String, List<ServiceLogRecord>> drained = drainAll();

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

    @Test
    void whatTheCollectorLogsWhileItStoresIsNotCapturedAndStoredInTurn() {
        // a sink that logs for the tenant while it stores, as the SDK and the HTTP client do at a low minimum level
        ServiceLogSink chatty = new ServiceLogSink() {
            @Override
            public void write(String tenant, String instance, ServiceLogChunk chunk) {
                try (var scope = TenantLogContext.open(tenant)) {
                    logger.warn("storing a chunk for {}", tenant);
                }
            }

            @Override
            public void purge(String tenant, java.time.Instant before) {
            }
        };
        buffer.offer(new ServiceLogRecord(java.time.Instant.now(), "WARN", "t", "tenantA", "d.m.Foo", "a real line"), 100);

        new ServiceLogCollector(buffer, chatty, () -> java.util.Set.of("tenantA"), new TestSettings(), "pod-1",
                java.time.Clock.systemUTC()).collect();

        assertTrue(drainAll().isEmpty(), "the line logged while storing must not come back as a new line to store");
        try (var scope = TenantLogContext.open("tenantA")) {
            logger.warn("after the run");
        }
        assertEquals(1, drainAll().get("tenantA").size(), "capture works again once the run is over");
    }

    @Test
    void aHugeMessageIsCutBeforeItEntersTheBuffer() {
        logger.detachAppender(appender);
        appender.stop();
        appender = new ServiceLogCaptureAppender(buffer, new TestSettings(c -> {
            c.setMinLevel("WARN");
            c.setMaxRecordBytes(1024);
        }));
        appender.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        appender.start();
        logger.addAppender(appender);

        try (var scope = TenantLogContext.open("tenantA")) {
            logger.error("payload {}", "x".repeat(3_000_000));
        }

        String text = drainAll().get("tenantA").get(0).text();
        assertTrue(text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 1024, "length " + text.length());
        assertTrue(text.endsWith("[truncated]"));
    }

    private void useSettings(TestSettings settings) {
        logger.detachAppender(appender);
        appender.stop();
        appender = new ServiceLogCaptureAppender(buffer, settings);
        appender.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        appender.start();
        logger.addAppender(appender);
    }

    @Test
    void aTenantThatSwitchedTheServiceLogOffIsNotCapturedButOthersAre() {
        useSettings(new TestSettings().with("tenantA", c -> c.setEnabled(false)));

        try (var a = TenantLogContext.open("tenantA")) {
            logger.error("from the tenant that opted out");
        }
        try (var b = TenantLogContext.open("tenantB")) {
            logger.error("from another tenant");
        }

        Map<String, List<ServiceLogRecord>> drained = drainAll();
        assertEquals(java.util.Set.of("tenantB"), drained.keySet());
    }

    @Test
    void eachTenantHasItsOwnMinimumLevel() {
        useSettings(new TestSettings().with("tenantA", c -> c.setMinLevel("INFO")).with("tenantB", c -> c.setMinLevel("ERROR")));

        try (var a = TenantLogContext.open("tenantA")) {
            logger.info("A info");
        }
        try (var b = TenantLogContext.open("tenantB")) {
            logger.info("B info");
            logger.warn("B warn");
            logger.error("B error");
        }

        Map<String, List<ServiceLogRecord>> drained = drainAll();
        assertEquals(1, drained.get("tenantA").size(), "A asked for INFO");
        assertEquals(1, drained.get("tenantB").size(), "B asked for ERROR only");
        assertTrue(drained.get("tenantB").get(0).text().endsWith("B error"));
    }

    @Test
    void aSettingChangedWhileRunningAppliesToTheNextLineWithoutARestart() {
        TestSettings live = new TestSettings();
        useSettings(live);
        try (var a = TenantLogContext.open("tenantA")) {
            logger.info("dropped at the default WARN");
        }
        assertTrue(drainAll().isEmpty());

        live.with("tenantA", c -> c.setMinLevel("INFO")); // what saving the Service configuration page does
        try (var a = TenantLogContext.open("tenantA")) {
            logger.info("kept after the change");
        }

        assertEquals(1, drainAll().get("tenantA").size());
    }

    @Test
    void eachTenantsBufferBoundIsItsOwnSetting() {
        useSettings(new TestSettings(c -> c.setMinLevel("WARN")).with("tenantA", c -> {
            c.setMinLevel("WARN");
            c.setMaxBufferedRecords(100); // the smallest allowed
        }));

        try (var a = TenantLogContext.open("tenantA")) {
            for (int i = 0; i < 150; i++) {
                logger.warn("line {}", i);
            }
        }

        List<ServiceLogRecord> kept = drainAll().get("tenantA");
        assertEquals(100, kept.size());
        assertTrue(kept.get(0).text().endsWith("line 50"), "the oldest 50 were dropped: " + kept.get(0).text());
    }
}
