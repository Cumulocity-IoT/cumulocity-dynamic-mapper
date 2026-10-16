package dynamic.mapper.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;

/**
 * Boots Spring's real logging setup with the project's logback-spring.xml and application
 * properties, and checks what a log line actually looks like — the pattern, the converter and the
 * way the SDK clears its own key.
 */
@ExtendWith(OutputCaptureExtension.class)
class TenantLogPatternTest {

    @Configuration
    static class Empty {
    }

    private static final Logger LOG = LoggerFactory.getLogger("dynamic.mapper.test.TenantLogPatternTest");

    @Test
    void tenantIsAFieldOfEveryLine(CapturedOutput out) throws Exception {
        TenantLogging.register(); // what App.main does
        try (var ctx = new SpringApplicationBuilder(Empty.class)
                .web(WebApplicationType.NONE)
                .properties("logging.config=classpath:logback-spring.xml",
                        "logging.pattern.correlation=[%-11tenantId] ", "logging.threshold.console=INFO")
                .run()) {

            LOG.info("plain-line");
            LOG.info("tenantA - unscoped-prefixed-line");
            try (var a = TenantLogContext.open("tenantA")) {
                LOG.info("ours-line");
                LOG.info("tenantA - prefixed-line");
                LOG.info("Tenant tenantA - capitalised-line");
                LOG.info("tenantB - foreign-prefix-line");
                // The SDK's leaveContext() removes its key unconditionally after callForTenant(...)
                MDC.put(TenantLogContext.SDK_KEY, "tenantSdk");
                LOG.info("sdk-scope-line");
                MDC.remove(TenantLogContext.SDK_KEY);
                LOG.info("after-sdk-scope-line");
            }
            LOG.info("after-line");
            Thread.sleep(300); // AsyncAppender
        }
        String o = out.getAll();
        assertFalse(o.contains("PARSER_ERROR"), o);
        assertTrue(o.matches("(?s).*\\[-\\s*\\] \\S*TenantLogPatternTest\\s+: plain-line.*"), o);
        // prefix is dropped only when it repeats the line's tenant
        assertTrue(o.matches("(?s).*\\[-\\s*\\] \\S*TenantLogPatternTest\\s+: tenantA - unscoped-prefixed-line.*"), o);
        assertTrue(o.matches("(?s).*\\[tenantA\\s*\\] \\S*TenantLogPatternTest\\s+: prefixed-line.*"), o);
        assertTrue(o.matches("(?s).*\\[tenantA\\s*\\] \\S*TenantLogPatternTest\\s+: capitalised-line.*"), o);
        assertTrue(o.matches("(?s).*\\[tenantA\\s*\\] \\S*TenantLogPatternTest\\s+: tenantB - foreign-prefix-line.*"), o);
        assertTrue(o.matches("(?s).*\\[tenantA\\s*\\] \\S*TenantLogPatternTest\\s+: ours-line.*"), o);
        assertTrue(o.matches("(?s).*\\[tenantSdk\\s*\\] \\S*TenantLogPatternTest\\s+: sdk-scope-line.*"), o);
        assertTrue(o.matches("(?s).*\\[tenantA\\s*\\] \\S*TenantLogPatternTest\\s+: after-sdk-scope-line.*"), o);
        assertTrue(o.matches("(?s).*\\[-\\s*\\] \\S*TenantLogPatternTest\\s+: after-line.*"), o);
    }
}
