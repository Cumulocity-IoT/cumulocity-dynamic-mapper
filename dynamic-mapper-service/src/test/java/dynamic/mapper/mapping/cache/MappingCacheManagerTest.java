package dynamic.mapper.mapping.cache;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dynamic.mapper.configuration.ConnectorId;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.validation.ResolveException;

class MappingCacheManagerTest {

    private static final String T = "t1";
    private MappingCacheManager cache;

    private static Mapping in(String id, String identifier, String topic) {
        return Mapping.builder().id(id).identifier(identifier).name(id).mappingTopic(topic)
                .direction(Direction.INBOUND).active(true).build();
    }

    @BeforeEach
    void setUp() {
        cache = new MappingCacheManager();
        cache.createTenantCache(T);
    }

    @Test
    void replaceMovesMappingToNewTopicWithoutDuplicates() throws ResolveException {
        cache.addMapping(T, in("1", "a", "x/old"));
        cache.replaceMapping(T, in("1", "a", "x/new"));

        assertTrue(cache.resolveInboundMappings(T, "x/old").isEmpty());
        assertEquals(1, cache.resolveInboundMappings(T, "x/new").size());
        assertEquals(1, cache.getAllInboundMappings(T).size());
    }

    @Test
    void identifierIndexFollowsAddReplaceRemove() {
        cache.addMapping(T, in("1", "a", "x/y"));
        assertTrue(cache.containsInboundMappingByIdentifier(T, "a"));

        cache.replaceMapping(T, in("1", "b", "x/y"));
        assertFalse(cache.containsInboundMappingByIdentifier(T, "a"));
        assertTrue(cache.containsInboundMappingByIdentifier(T, "b"));

        cache.removeInboundMapping(T, "1");
        assertFalse(cache.containsInboundMappingByIdentifier(T, "b"));
    }

    @Test
    void rebuildToleratesDuplicateIdsAndSwapsTree() throws ResolveException {
        cache.rebuildInboundCache(T, List.of(in("1", "a", "p/q"), in("1", "a", "p/q")), ConnectorId.INTERNAL);
        assertEquals(1, cache.getAllInboundMappings(T).size());
        assertEquals(1, cache.resolveInboundMappings(T, "p/q").size());
    }

    @Test
    void removedTenantIsNotResurrected() throws ResolveException {
        cache.removeTenantCache(T);
        cache.addMapping(T, in("1", "a", "x/y"));

        assertTrue(cache.getAllInboundMappings(T).isEmpty());
        assertTrue(cache.resolveInboundMappings(T, "x/y").isEmpty());
        assertFalse(cache.containsInboundMapping(T, "1"));
    }
}
