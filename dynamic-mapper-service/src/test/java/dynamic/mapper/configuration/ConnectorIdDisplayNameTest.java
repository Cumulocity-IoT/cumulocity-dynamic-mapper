package dynamic.mapper.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConnectorIdDisplayNameTest {

    @Test
    @DisplayName("is the connector name, not the internal identifier")
    void usesName() {
        assertEquals("Mqtt-emqx", new ConnectorId("Mqtt-emqx", "zhb6fs36").displayName());
    }

    @Test
    @DisplayName("falls back to the identifier for a connector without a name")
    void fallsBackToIdentifier() {
        assertEquals("zhb6fs36", new ConnectorId(null, "zhb6fs36").displayName());
        assertEquals("zhb6fs36", new ConnectorId("  ", "zhb6fs36").displayName());
    }
}
