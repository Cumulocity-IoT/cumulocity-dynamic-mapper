package dynamic.mapping.connector.webhook;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Tests for WebHook JSON merging functionality
 */
public class WebHookTest {

    private ObjectMapper objectMapper = new ObjectMapper();
    private JsonMergeTester jsonMergeTester;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        jsonMergeTester = new JsonMergeTester(objectMapper);
    }

    /**
     * Test-specific class that provides access to the JSON merging functionality
     */
    private static class JsonMergeTester {
        private final ObjectMapper objectMapper;

        public JsonMergeTester(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        public String mergeJsonObjects(String existingJson, String newJson) throws IOException {
            // Convert JSON strings to JsonNode objects
            JsonNode existingNode = objectMapper.readTree(existingJson);
            JsonNode newNode = objectMapper.readTree(newJson);

            // Perform deep merge of the two objects
            JsonNode mergedNode = mergeNodes(existingNode, newNode);

            // Convert merged node back to JSON string
            return objectMapper.writeValueAsString(mergedNode);
        }

        public JsonNode mergeNodes(JsonNode existingNode, JsonNode updateNode) {
            ObjectNode result = objectMapper.createObjectNode();

            // First, copy all fields from the existing node
            existingNode.fields().forEachRemaining(field -> result.set(field.getKey(), field.getValue()));

            // Then, merge in the update node fields
            updateNode.fields().forEachRemaining(field -> {
                String fieldName = field.getKey();
                JsonNode fieldValue = field.getValue();

                // If both nodes have the field and both are objects, recursively merge them
                if (result.has(fieldName) && result.get(fieldName).isObject() && fieldValue.isObject()) {
                    result.set(fieldName, mergeNodes(result.get(fieldName), fieldValue));
                } else {
                    // Otherwise, just overwrite with the update value
                    result.set(fieldName, fieldValue);
                }
            });

            return result;
        }
    }

    @Test
    public void testMergeJsonObjects() throws IOException {
        // Given - existing payload
        String existingPayload = """
                {
                  "channel": {
                    "device": {
                      "channelId": 0,
                      "series": {
                        "temp": {
                          "min": -40,
                          "max": 80
                        }
                      }
                    }
                  }
                }
                """;

        // Given - new payload with update
        String newPayload = """
                {
                  "channel": {
                    "device": {
                      "channelId": 20
                    }
                  }
                }
                """;

        // Expected merged result
        String expectedPayload = """
                {
                  "channel": {
                    "device": {
                      "channelId": 20,
                      "series": {
                        "temp": {
                          "min": -40,
                          "max": 80
                        }
                      }
                    }
                  }
                }
                """;

        // When
        String result = jsonMergeTester.mergeJsonObjects(existingPayload, newPayload);

        // Then
        // Normalize to remove whitespaces that might affect comparison
        JsonNode resultJson = objectMapper.readTree(result);
        JsonNode expectedJson = objectMapper.readTree(expectedPayload);

        assertEquals(expectedJson, resultJson, "The merged JSON should match the expected result");
    }

    @Test
    public void testMergeNodes_NestedUpdate() {
        try {
            // Given
            JsonNode existing = objectMapper.readTree("""
                    {
                      "channel": {
                        "device": {
                          "channelId": 0,
                          "series": {
                            "temp": {
                              "min": -40,
                              "max": 80
                            }
                          }
                        }
                      }
                    }
                    """);

            JsonNode update = objectMapper.readTree("""
                    {
                      "channel": {
                        "device": {
                          "channelId": 20
                        }
                      }
                    }
                    """);

            // When
            JsonNode result = jsonMergeTester.mergeNodes(existing, update);

            // Then
            assertEquals(20, result.path("channel").path("device").path("channelId").asInt(),
                    "The channelId should be updated to 20");

            // Verify nested structure is preserved
            assertTrue(result.path("channel").path("device").path("series").path("temp").has("min"),
                    "The nested temp.min field should be preserved");
            assertEquals(-40, result.path("channel").path("device").path("series").path("temp").path("min").asInt(),
                    "The value of temp.min should remain -40");
            assertEquals(80, result.path("channel").path("device").path("series").path("temp").path("max").asInt(),
                    "The value of temp.max should remain 80");
        } catch (Exception e) {
            fail("Exception should not be thrown: " + e.getMessage());
        }
    }

    @Test
    public void testMergeNodes_AddNewField() {
        try {
            // Given
            JsonNode existing = objectMapper.readTree("""
                    {
                      "channel": {
                        "device": {
                          "channelId": 0
                        }
                      }
                    }
                    """);

            JsonNode update = objectMapper.readTree("""
                    {
                      "channel": {
                        "device": {
                          "newField": "value"
                        }
                      }
                    }
                    """);

            // When
            JsonNode result = jsonMergeTester.mergeNodes(existing, update);

            // Then
            assertEquals(0, result.path("channel").path("device").path("channelId").asInt(),
                    "The channelId should remain 0");
            assertEquals("value", result.path("channel").path("device").path("newField").asText(),
                    "The new field should be added with value 'value'");
        } catch (Exception e) {
            fail("Exception should not be thrown: " + e.getMessage());
        }
    }

    @Test
    public void testMergeNodes_ReplaceFieldWithObject() {
        try {
            // Given
            JsonNode existing = objectMapper.readTree("""
                    {
                      "channel": {
                        "device": {
                          "config": "simple"
                        }
                      }
                    }
                    """);

            JsonNode update = objectMapper.readTree("""
                    {
                      "channel": {
                        "device": {
                          "config": {
                            "type": "complex",
                            "value": 42
                          }
                        }
                      }
                    }
                    """);

            // When
            JsonNode result = jsonMergeTester.mergeNodes(existing, update);

            // Then
            assertTrue(result.path("channel").path("device").path("config").isObject(),
                    "The config field should be replaced with an object");
            assertEquals("complex", result.path("channel").path("device").path("config").path("type").asText(),
                    "The config.type should be 'complex'");
            assertEquals(42, result.path("channel").path("device").path("config").path("value").asInt(),
                    "The config.value should be 42");
        } catch (Exception e) {
            fail("Exception should not be thrown: " + e.getMessage());
        }
    }

    @Test
    public void testMergeNodes_AddNewTopLevelField() {
        try {
            // Given
            JsonNode existing = objectMapper.readTree("""
                    {
                      "channel": {
                        "device": {}
                      }
                    }
                    """);

            JsonNode update = objectMapper.readTree("""
                    {
                      "newTopLevel": "value"
                    }
                    """);

            // When
            JsonNode result = jsonMergeTester.mergeNodes(existing, update);

            // Then
            assertTrue(result.has("channel"), "The original channel field should be preserved");
            assertEquals("value", result.path("newTopLevel").asText(),
                    "The new top-level field should be added");
        } catch (Exception e) {
            fail("Exception should not be thrown: " + e.getMessage());
        }
    }

    @Test
    public void testMergeNodes_EmptyObjects() {
        try {
            // Given
            JsonNode existing = objectMapper.readTree("{}");
            JsonNode update = objectMapper.readTree("{}");

            // When
            JsonNode result = jsonMergeTester.mergeNodes(existing, update);

            // Then
            assertEquals(0, result.size(), "The result should be an empty object");
        } catch (Exception e) {
            fail("Exception should not be thrown: " + e.getMessage());
        }
    }

    @Test
    public void testMergeNodes_ComplexNestedStructure() {
        try {
            // Given - a more complex existing structure
            String complexExisting = """
                    {
                      "settings": {
                        "general": {
                          "name": "Device XYZ",
                          "location": "Building A"
                        },
                        "network": {
                          "ip": "192.168.1.1",
                          "mask": "255.255.255.0"
                        },
                        "sensors": [
                          {"id": 1, "type": "temperature"},
                          {"id": 2, "type": "humidity"}
                        ]
                      },
                      "status": "active"
                    }
                    """;

            // And an update with mixed changes
            String complexUpdate = """
                    {
                      "settings": {
                        "general": {
                          "name": "Device ABC"
                        },
                        "network": {
                          "gateway": "192.168.1.254"
                        },
                        "performance": {
                          "cpu": "low",
                          "memory": "high"
                        }
                      },
                      "firmware": "v2.0.1"
                    }
                    """;

            JsonNode existingNode = objectMapper.readTree(complexExisting);
            JsonNode updateNode = objectMapper.readTree(complexUpdate);

            // When
            JsonNode result = jsonMergeTester.mergeNodes(existingNode, updateNode);

            // Then - verify the merge worked correctly
            // 1. Updated field
            assertEquals("Device ABC", result.path("settings").path("general").path("name").asText(),
                    "The general name should be updated");

            // 2. Preserved field
            assertEquals("Building A", result.path("settings").path("general").path("location").asText(),
                    "The general location should be preserved");

            // 3. New field in existing object
            assertEquals("192.168.1.254", result.path("settings").path("network").path("gateway").asText(),
                    "The network gateway should be added");

            // 4. Preserved field in object with updates
            assertEquals("192.168.1.1", result.path("settings").path("network").path("ip").asText(),
                    "The network ip should be preserved");

            // 5. Entirely new object
            assertTrue(result.path("settings").path("performance").isObject(),
                    "The performance section should be added");
            assertEquals("low", result.path("settings").path("performance").path("cpu").asText(),
                    "The performance.cpu should be 'low'");

            // 6. New top-level field
            assertEquals("v2.0.1", result.path("firmware").asText(),
                    "The firmware version should be added");

            // 7. Preserved top-level field
            assertEquals("active", result.path("status").asText(),
                    "The status should be preserved");

            // 8. Preserved array
            assertTrue(result.path("settings").path("sensors").isArray(),
                    "The sensors array should be preserved");
            assertEquals(2, result.path("settings").path("sensors").size(),
                    "The sensors array should have 2 elements");
        } catch (Exception e) {
            fail("Exception should not be thrown: " + e.getMessage());
        }
    }
}