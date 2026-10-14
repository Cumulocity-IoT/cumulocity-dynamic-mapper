package dynamic.mapper.core.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.cumulocity.model.ID;
import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;

class ExternalIdCacheTtlTest {

    private static ExternalIDRepresentation rep(String sourceId) {
        ManagedObjectRepresentation mo = new ManagedObjectRepresentation();
        mo.setId(new GId(sourceId));
        ExternalIDRepresentation rep = new ExternalIDRepresentation();
        rep.setManagedObject(mo);
        return rep;
    }

    @Test
    void inboundEntryExpiresAfterTtl() {
        try (InboundExternalIdCache cache = new InboundExternalIdCache(10, "ttl-in-1", Duration.ofMillis(50))) {
            ID id = new ID("c8y_Serial", "dev1");
            cache.putIdForExternalId(id, rep("1"));
            assertNotNull(cache.getIdByExternalId(id));
            sleep(120);
            assertNull(cache.getIdByExternalId(id));
            assertEquals(0, cache.getCacheSize());
        }
    }

    @Test
    void outboundEntryExpiresAfterTtl() {
        try (OutboundExternalIdCache cache = new OutboundExternalIdCache(10, "ttl-out-1", Duration.ofMillis(50))) {
            OutboundIdKey key = new OutboundIdKey("1", "c8y_Serial");
            cache.putExternalIdForSource(key, rep("1"));
            assertNotNull(cache.getExternalIdForSource(key));
            sleep(120);
            assertNull(cache.getExternalIdForSource(key));
        }
    }

    @Test
    void removeBySourceIdEvictsMatchingEntries() {
        try (InboundExternalIdCache in = new InboundExternalIdCache(10, "ttl-in-2");
                OutboundExternalIdCache out = new OutboundExternalIdCache(10, "ttl-out-2")) {
            ID a = new ID("c8y_Serial", "a");
            ID b = new ID("c8y_Serial", "b");
            in.putIdForExternalId(a, rep("1"));
            in.putIdForExternalId(b, rep("2"));
            in.removeBySourceId("1");
            assertNull(in.getIdByExternalId(a));
            assertNotNull(in.getIdByExternalId(b));

            out.putExternalIdForSource(new OutboundIdKey("1", "x"), rep("1"));
            out.putExternalIdForSource(new OutboundIdKey("1", "y"), rep("1"));
            out.putExternalIdForSource(new OutboundIdKey("2", "x"), rep("2"));
            out.removeBySourceId("1");
            assertEquals(1, out.getCacheSize());
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
