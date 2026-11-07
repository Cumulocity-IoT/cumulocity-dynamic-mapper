package dynamic.mapper.configuration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A tenant option stored by an older version can still hold properties that no longer exist.
 * Reading it must not fail, even with a mapper that rejects unknown properties.
 */
class ServiceConfigurationRemovedPropertiesTest {

    @Test
    void storedExplorerSessionTtlIsIgnored() {
        ObjectMapper strict = new ObjectMapper();
        ServiceConfiguration config = assertDoesNotThrow(() -> strict.readValue(
                "{\"explorerSessionTTLMinutes\": 30, \"mappingVersionRetention\": 7}",
                ServiceConfiguration.class));
        assertEquals(7, config.getMappingVersionRetention());
    }
}
