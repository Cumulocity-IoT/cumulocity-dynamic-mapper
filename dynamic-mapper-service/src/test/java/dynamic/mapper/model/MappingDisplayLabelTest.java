package dynamic.mapper.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/** The label used in user-facing messages: name and id as the mapping list shows them. */
class MappingDisplayLabelTest {

    private static Mapping mapping(String id, String name, String identifier) {
        Mapping m = new Mapping();
        m.setId(id);
        m.setName(name);
        m.setIdentifier(identifier);
        return m;
    }

    @Test
    @DisplayName("is the name followed by the id, never the internal identifier")
    void nameAndId() {
        assertEquals("Axioma Meter [20623140]", mapping("20623140", "Axioma Meter", "2cb0ddnq").displayLabel());
    }

    @Test
    @DisplayName("omits the id for a mapping that has not been persisted yet")
    void noIdYet() {
        assertEquals("Axioma Meter", mapping(null, "Axioma Meter", "2cb0ddnq").displayLabel());
    }

    @Test
    @DisplayName("still says something for a mapping without a name")
    void noName() {
        assertEquals("(unnamed mapping) [1]", mapping("1", null, "x").displayLabel());
    }

    @Test
    @DisplayName("is not serialized as a property of the mapping")
    void notSerialized() throws Exception {
        String json = new ObjectMapper().writeValueAsString(mapping("1", "n", "x"));
        assertFalse(json.contains("displayLabel"), json);
    }
}
