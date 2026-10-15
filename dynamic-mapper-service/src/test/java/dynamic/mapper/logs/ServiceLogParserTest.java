package dynamic.mapper.logs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class ServiceLogParserTest {

    // Lines copied from a deployed service (docs/feature/service-logs.md)
    private static final String START = """
            MEMORY_LIMIT: 1907MB
            11:31:30,286 |-INFO in ch.qos.logback.classic.model.processor.Foo - Scan attribute
            11:31:27.558 [main] TRACE o.s.c.i.s.SpringFactoriesLoader - Loaded [x]
            2026-10-15T11:31:31.281Z  INFO 16 --- [           main] [-          ] dynamic.mapper.App                  : Starting App
            WARNING: A restricted method in java.lang.System has been called
            2026-10-15T11:32:35.574Z  INFO 16 --- [subscriptions-0] [t14070519  ] dynamic.mapper.core.BootstrapService     : Microservice subscribed
            2026-10-15T11:32:43.183Z  WARN 16 --- [  virtThread-11] [t2050305588] d.m.c.c.client.ConnectionStateManager    : Connection state changed: false -> true
            """;

    @Test
    void theTenantComesFromItsOwnFieldAndStartUpNoiseIsDropped() {
        List<ServiceLogRecord> records = ServiceLogParser.parse(START);

        assertEquals(3, records.size());
        assertNull(records.get(0).tenant(), "'-' means no tenant");
        assertFalse(records.get(0).isAttributed());
        assertEquals("t14070519", records.get(1).tenant(), "padding is trimmed");
        assertEquals("t2050305588", records.get(2).tenant());
        assertEquals("WARN", records.get(2).level());
        assertEquals("virtThread-11", records.get(2).thread());
        assertEquals("d.m.c.c.client.ConnectionStateManager", records.get(2).logger());
    }

    @Test
    void aMessageThatNamesAnotherTenantDoesNotChangeTheLinesTenant() {
        // The isolation rule: attribution is the field, never the text.
        String line = "2026-10-15T11:33:45.226Z  WARN 16 --- [subscriptions-0] [t15264971  ] d.m.n.s.MessagingManagementService       : "
                + "Could not list subscribers: 403 on GET .../tenants/t2050305588/namespaces: \"Microservice not subscribed by tenant t2050305588\"";

        List<ServiceLogRecord> records = ServiceLogParser.parse(line);

        assertEquals("t15264971", records.get(0).tenant());
    }

    @Test
    void aTenantLineIsNotAttributedFromAnUnattributedLinesText() {
        String line = "2026-10-15T10:41:49.774Z  INFO 16 --- [al-listener-4-1] [-          ] d.m.c.pulsar.MQTTServicePulsarCallback   : "
                + "t2050305588 - INITIAL: message on topic";

        ServiceLogRecord r = ServiceLogParser.parse(line).get(0);

        assertFalse(r.isAttributed(), "a leading tenant in the text must NOT attribute the line");
    }

    @Test
    void multiLinePayloadsAndStackTracesStayWithTheirRecord() {
        String logs = """
                2026-10-15T10:41:49.775Z  INFO 16 --- [al-listener-4-1] [t2050305588] d.m.p.inbound.InboundMessageDispatcher   : PROCESSING: payload: {
                  "temperature": 99.5,
                  "unit": "C"
                }
                2026-10-15T10:41:50.121Z ERROR 16 --- [  virtThread-38] [t2050305588] d.m.p.AbstractEnrichmentProcessor        : failed
                java.lang.IllegalStateException: boom
                \tat dynamic.mapper.Foo.bar(Foo.java:1)
                Caused by: java.io.IOException: io
                2026-10-15T10:41:51.139Z  INFO 16 --- [  virtThread-38] [t2050305588] d.m.processor.flow.JavaScriptConsole     : JS LOG
                """;

        List<ServiceLogRecord> records = ServiceLogParser.parse(logs);

        assertEquals(3, records.size());
        assertEquals(4, records.get(0).text().split("\n").length);
        assertTrue(records.get(0).text().endsWith("}"));
        assertTrue(records.get(1).text().contains("Caused by: java.io.IOException"));
    }

    @Test
    void jvmWarningsBetweenRecordsAreNotGluedToThePreviousRecord() {
        String logs = """
                2026-10-15T11:32:37.498Z  INFO 16 --- [subscriptions-0] [t14070519  ] d.mapper.core.GraalVMContextService      : Engine created
                WARNING: sun.misc.Unsafe::objectFieldOffset will be removed in a future release
                2026-10-15T11:32:41.289Z  INFO 16 --- [subscriptions-0] [t14070519  ] d.mapper.core.GraalVMContextService      : warm-up complete
                """;

        List<ServiceLogRecord> records = ServiceLogParser.parse(logs);

        assertEquals(2, records.size());
        assertFalse(records.get(0).text().contains("WARNING"));
    }

    @Test
    void emptyOrNullInputGivesNoRecords() {
        assertTrue(ServiceLogParser.parse(null).isEmpty());
        assertTrue(ServiceLogParser.parse("").isEmpty());
        assertTrue(ServiceLogParser.parse("just\nbanner\n").isEmpty());
    }
}
