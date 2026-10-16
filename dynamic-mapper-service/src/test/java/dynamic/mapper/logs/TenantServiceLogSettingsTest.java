package dynamic.mapper.logs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.configuration.ServiceLogsConfiguration;
import dynamic.mapper.core.TenantRegistry;

class TenantServiceLogSettingsTest {

    private final TenantRegistry registry = mock(TenantRegistry.class);
    private final TenantServiceLogSettings provider = new TenantServiceLogSettings(registry);

    @Test
    void aTenantWhoseConfigurationIsNotKnownYetGetsTheDefaults() {
        when(registry.getServiceConfiguration("tenantA")).thenReturn(null);

        assertEquals(ServiceLogsConfiguration.defaults(), provider.forTenant("tenantA"));
    }

    @Test
    void aConfigurationWithoutTheSettingsObjectGetsTheDefaults() {
        ServiceConfiguration configuration = new ServiceConfiguration();
        configuration.setServiceLogs(null);
        when(registry.getServiceConfiguration("tenantA")).thenReturn(configuration);

        assertEquals(ServiceLogsConfiguration.defaults(), provider.forTenant("tenantA"));
    }

    @Test
    void eachTenantGetsItsOwnValuesAndTheyAreClamped() {
        ServiceConfiguration a = new ServiceConfiguration();
        a.getServiceLogs().setMinLevel("WARN");
        ServiceConfiguration b = new ServiceConfiguration();
        b.getServiceLogs().setMinLevel("ERROR");
        b.getServiceLogs().setIntervalMs(1);
        when(registry.getServiceConfiguration("tenantA")).thenReturn(a);
        when(registry.getServiceConfiguration("tenantB")).thenReturn(b);

        assertEquals("WARN", provider.forTenant("tenantA").minLevel());
        assertEquals("ERROR", provider.forTenant("tenantB").minLevel());
        assertEquals(5_000, provider.forTenant("tenantB").intervalMs());
    }

    @Test
    void aChangeSavedLaterIsSeenByTheNextCallWithoutARestart() {
        ServiceConfiguration configuration = new ServiceConfiguration();
        when(registry.getServiceConfiguration("tenantA")).thenReturn(configuration);
        assertEquals("INFO", provider.forTenant("tenantA").minLevel());

        ServiceConfiguration saved = new ServiceConfiguration();
        saved.getServiceLogs().setMinLevel("WARN");
        when(registry.getServiceConfiguration("tenantA")).thenReturn(saved); // what addServiceConfiguration does

        assertEquals("WARN", provider.forTenant("tenantA").minLevel());
    }
}
