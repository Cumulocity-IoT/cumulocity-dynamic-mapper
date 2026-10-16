package dynamic.mapper.logs;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import dynamic.mapper.configuration.ServiceLogsConfiguration;
import dynamic.mapper.configuration.ServiceLogsConfiguration.Settings;

/** Per-tenant service-log settings for tests: everything default except a WARN minimum level, which most tests assume. */
final class TestSettings implements ServiceLogSettingsProvider {

    private final Map<String, Settings> byTenant = new HashMap<>();
    private final Settings fallback;

    TestSettings() {
        this(c -> c.setMinLevel("WARN"));
    }

    TestSettings(Consumer<ServiceLogsConfiguration> edit) {
        this.fallback = build(edit);
    }

    static Settings build(Consumer<ServiceLogsConfiguration> edit) {
        ServiceLogsConfiguration configuration = new ServiceLogsConfiguration();
        edit.accept(configuration);
        return configuration.effective();
    }

    /** Gives one tenant its own settings (on top of the defaults, not of the fallback). */
    TestSettings with(String tenant, Consumer<ServiceLogsConfiguration> edit) {
        byTenant.put(tenant, build(edit));
        return this;
    }

    @Override
    public Settings forTenant(String tenant) {
        return byTenant.getOrDefault(tenant, fallback);
    }
}
