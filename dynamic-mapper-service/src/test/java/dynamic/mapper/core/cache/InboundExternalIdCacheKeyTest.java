package dynamic.mapper.core.cache;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import com.cumulocity.model.ID;
import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;

/**
 * The cache key is type plus value. C8YAgent evicts a device after an HTTP 422 with an ID built from
 * the request's external ID type; building it from the value twice evicted nothing.
 */
class InboundExternalIdCacheKeyTest {

    @Test
    void evictionNeedsTheExternalIdTypeNotTheValueTwice() {
        InboundExternalIdCache cache = new InboundExternalIdCache(10, "t");
        cache.putIdForExternalId(new ID("c8y_Serial", "berlin_01"), new ExternalIDRepresentation());

        cache.removeIdForExternalId(new ID("berlin_01", "berlin_01"));
        assertNotNull(cache.getIdByExternalId(new ID("c8y_Serial", "berlin_01")), "wrong type evicts nothing");

        cache.removeIdForExternalId(new ID("c8y_Serial", "berlin_01"));
        assertNull(cache.getIdByExternalId(new ID("c8y_Serial", "berlin_01")));
    }
}
