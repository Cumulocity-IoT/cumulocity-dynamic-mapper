package dynamic.mapper.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.configuration.ServiceLogsConfiguration.Settings;

class ServiceLogsConfigurationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void aTenantThatSetNothingGetsTheDefaultsFromTheCode() {
        Settings s = new ServiceLogsConfiguration().effective();

        assertTrue(s.enabled());
        assertEquals("INFO", s.minLevel());
        assertEquals(30_000, s.intervalMs());
        assertEquals(500, s.maxLinesPerRun());
        assertEquals(65_536, s.maxChunkBytes());
        assertEquals(8_192, s.maxRecordBytes());
        assertEquals(2_000, s.maxBufferedRecords());
        assertEquals(24, s.retentionHours());
        assertTrue(s.retentionEnabled());
        assertEquals(3_600_000, s.retentionIntervalMs());
        assertEquals(s, ServiceLogsConfiguration.defaults());
    }

    @Test
    void valuesThatAreNullFallBackToTheDefaults() {
        ServiceLogsConfiguration c = new ServiceLogsConfiguration();
        c.setEnabled(null);
        c.setMinLevel(null);
        c.setIntervalMs(null);
        c.setMaxLinesPerRun(null);
        c.setRetentionHours(null);

        assertEquals(ServiceLogsConfiguration.defaults(), c.effective());
    }

    @Test
    void outOfRangeValuesAreClampedSoOneTenantCannotHarmTheSharedService() {
        ServiceLogsConfiguration c = new ServiceLogsConfiguration();
        c.setIntervalMs(1);
        c.setMaxLinesPerRun(99_999_999);
        c.setMaxChunkBytes(10);
        c.setRetentionHours(100_000);
        c.setRetentionIntervalMs(1);

        Settings s = c.effective();

        assertEquals(5_000, s.intervalMs(), "never faster than every 5 seconds");
        assertEquals(5_000, s.maxLinesPerRun());
        assertEquals(1_024, s.maxChunkBytes());
        assertEquals(720, s.retentionHours());
        assertEquals(60_000, s.retentionIntervalMs());
    }

    @Test
    void aRecordIsNeverLargerThanAnEvent() {
        ServiceLogsConfiguration c = new ServiceLogsConfiguration();
        c.setMaxChunkBytes(2_048);
        c.setMaxRecordBytes(60_000);

        assertEquals(2_048, c.effective().maxRecordBytes());
    }

    @Test
    void theBacklogOfOneTenantIsBoundedInBytesNotOnlyInRecords() {
        ServiceLogsConfiguration c = new ServiceLogsConfiguration();
        c.setMaxRecordBytes(65_536);
        c.setMaxBufferedRecords(10_000); // would be 640 MB for one tenant

        Settings s = c.effective();

        assertTrue((long) s.maxBufferedRecords() * s.maxRecordBytes() <= 16L * 1024 * 1024,
                "backlog " + s.maxBufferedRecords() + " x " + s.maxRecordBytes());
        assertTrue(s.maxBufferedRecords() >= 100, "but never useless");
    }

    @Test
    void theLevelIsCaseInsensitiveAndAnUnknownOneFallsBackToTheDefault() {
        ServiceLogsConfiguration c = new ServiceLogsConfiguration();
        c.setMinLevel(" warn ");
        assertEquals("WARN", c.effective().minLevel());
        c.setMinLevel("LOUD");
        assertEquals("INFO", c.effective().minLevel());
    }

    @Test
    void aConfigurationStoredBeforeTheseSettingsExistedKeepsWorkingWithTheDefaults() throws Exception {
        ServiceConfiguration old = mapper.readValue("{\"logPayload\":true}", ServiceConfiguration.class);

        assertNotNull(old.getServiceLogs());
        assertEquals(ServiceLogsConfiguration.defaults(), old.getServiceLogs().effective());
        assertTrue(old.getLogPayload());
    }

    @Test
    void aPartialSettingsObjectOnlyChangesWhatItSays() throws Exception {
        ServiceConfiguration c = mapper.readValue("{\"serviceLogs\":{\"minLevel\":\"WARN\",\"enabled\":false}}",
                ServiceConfiguration.class);

        Settings s = c.getServiceLogs().effective();
        assertEquals("WARN", s.minLevel());
        assertFalse(s.enabled());
        assertEquals(24, s.retentionHours(), "untouched values keep their default");
    }

    @Test
    void savingAPartialUpdateFromTheConfigurationPageMergesIntoWhatIsStoredInsteadOfReplacingIt() throws Exception {
        ServiceConfiguration stored = mapper.readValue("{\"serviceLogs\":{\"minLevel\":\"WARN\",\"retentionHours\":48}}",
                ServiceConfiguration.class);

        // what ConfigurationController does with the body of PUT /configuration/service
        ServiceConfiguration updated = mapper.readerForUpdating(stored).readValue("{\"serviceLogs\":{\"retentionHours\":72}}");

        assertEquals("WARN", updated.getServiceLogs().effective().minLevel(), "kept");
        assertEquals(72, updated.getServiceLogs().effective().retentionHours(), "changed");
    }

    @Test
    void aNullInTheJsonDoesNotWipeAValue() throws Exception {
        ServiceConfiguration c = mapper.readValue("{\"serviceLogs\":{\"minLevel\":null,\"intervalMs\":null}}",
                ServiceConfiguration.class);

        assertEquals(ServiceLogsConfiguration.defaults(), c.getServiceLogs().effective());
    }

    @Test
    void theSettingsSurviveBeingSavedAndLoadedAgain() throws Exception {
        ServiceConfiguration c = new ServiceConfiguration();
        c.getServiceLogs().setMinLevel("ERROR");
        c.getServiceLogs().setRetentionHours(6);

        ServiceConfiguration back = mapper.readValue(mapper.writeValueAsString(c), ServiceConfiguration.class);

        assertEquals("ERROR", back.getServiceLogs().effective().minLevel());
        assertEquals(6, back.getServiceLogs().effective().retentionHours());
    }
}
