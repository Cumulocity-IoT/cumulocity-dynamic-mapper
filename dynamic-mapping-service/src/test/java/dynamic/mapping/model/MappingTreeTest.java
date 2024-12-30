package dynamic.mapping.model;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
class MappingTreeTest {
    private ObjectMapper objectMapper;
    private List<Mapping> mappings;
    private static final String TEST_MAPPING_FILE = "/mappings-test-INBOUND.json";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        try {
            mappings = deserializeMappings(TEST_MAPPING_FILE);
        } catch (IOException e) {
            fail("Failed to load test data: " + e.getMessage());
        }
    }

    @Nested
    @DisplayName("Deserialization Tests")
    class DeserializationTests {
        @Test
        @DisplayName("Should successfully deserialize mappings from JSON file")
        void testDeserializeMapping() {
            assertNotNull(mappings, "Mappings should not be null");
            assertEquals(8, mappings.size(), "Should have 4 mappings");
            // Add more specific assertions about the mapping content
        }

        @Test
        @DisplayName("Should throw exception for non-existent file")
        void testDeserializeMappingWithNonExistentFile() {
            assertThrows(IOException.class, () -> {
                deserializeMappings("/non-existent-file.json");
            });
        }
    }

    @Nested
    @DisplayName("Mapping Tree Building Tests")
    class MappingTreeBuildingTests {
        @Test
        @DisplayName("Should successfully build mapping tree")
        void testBuildMappingTree() throws ResolveException {
            List<Mapping> resolvedMappings;
            // Now you can use the mappings initialized in setUp()
            assertNotNull(mappings, "Mappings should be available for tree building");

            // Add your tree building logic and assertions
            MappingTreeNode tree = MappingTreeNode.createRootNode("TEST_TENANT");
            tree.addMapping(mappings.get(0));
            resolvedMappings = tree.resolveMapping("device/test");
            assertEquals(1, resolvedMappings.size(), "Should resolve 1 mapping");
            assertEquals("Mapping - 00", resolvedMappings.get(0).getName(), "Should resiolve 1 mapping");

            // adding a map a child, should not return more resolved mappings
            tree.addMapping(mappings.get(1));
            resolvedMappings = tree.resolveMapping("device/test");
            assertEquals(1, resolvedMappings.size(), "Should the same mapping");
        }

        @Test
        @DisplayName("Should successfully build mapping tree")
        void testBuildResolvingWildcardPlus() throws ResolveException {
            List<Mapping> resolvedMappings;
            // Now you can use the mappings initialized in setUp()
            assertNotNull(mappings, "Mappings should be available for tree building");

            // Add your tree building logic and assertions
            MappingTreeNode tree = MappingTreeNode.createRootNode("TEST_TENANT");
            tree.addMapping(mappings.get(5));
            tree.addMapping(mappings.get(6));
            resolvedMappings = tree.resolveMapping("device/test1/sub");
            assertEquals(1, resolvedMappings.size(), "Should resolve 1 mapping");
            assertEquals("Mapping - 05", resolvedMappings.get(0).getName(), "Should resolve mapping 5");

            resolvedMappings = tree.resolveMapping("device/test1/some/special/sub");
            assertEquals(1, resolvedMappings.size(), "Should resolve 1 mapping");
            assertEquals("Mapping - 06", resolvedMappings.get(0).getName(), "Should resolve mapping 5");

            tree.addMapping(mappings.get(7));
            resolvedMappings = tree.resolveMapping("device/test1/some/special/sub");
            assertEquals(2, resolvedMappings.size(), "Should resolve 1 mapping");

        }

        @Test
        @DisplayName("Should handle removing and adding mappings")
        void testBuildMappingTreeWithEmptyList() throws ResolveException {
            List<Mapping> resolvedMappings;
            // Now you can use the mappings initialized in setUp()
            assertNotNull(mappings, "Mappings should be available for tree building");

            // Add your tree building logic and assertions
            MappingTreeNode tree = MappingTreeNode.createRootNode("TEST_TENANT");
            tree.addMapping(mappings.get(0));
            tree.addMapping(mappings.get(1));
            tree.addMapping(mappings.get(2));

            resolvedMappings = tree.resolveMapping("device/test/sub/subsub");
            assertEquals(1, resolvedMappings.size(), "Should resolve 1 mapping");
            assertEquals("Mapping - 02", resolvedMappings.get(0).getName(), "Should resolve mapping 02");

            resolvedMappings = tree.resolveMapping("device/test/sub");
            assertEquals(1, resolvedMappings.size(), "Should resolve 1 mapping");
            assertEquals("Mapping - 01", resolvedMappings.get(0).getName(), "Should resolve mapping 01");

            tree.deleteMapping(mappings.get(1));

            resolvedMappings = tree.resolveMapping("device/test/sub/subsub");
            assertEquals(1, resolvedMappings.size(), "Should still return the leaf mapping, even if an inner node is deleted");
            assertEquals("Mapping - 02", resolvedMappings.get(0).getName(), "Should resolve mapping 02");

            resolvedMappings = tree.resolveMapping("device/test/sub");
            assertEquals(0, resolvedMappings.size(), "Should not resolve to any mapping");

        }
    }

    private List<Mapping> deserializeMappings(String filePath) throws IOException {
        try (InputStream inputStream = getClass().getResourceAsStream(filePath)) {
            if (inputStream == null) {
                throw new IOException("Could not find file: " + filePath);
            }

            return objectMapper.readValue(
                    inputStream,
                    new TypeReference<List<Mapping>>() {
                    });
        } catch (IOException e) {
            log.error("Error deserializing mappings file: {}", e.getMessage());
            throw e;
        }
    }
}