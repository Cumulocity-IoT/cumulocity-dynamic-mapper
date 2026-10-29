package dynamic.mapper.logs.read;

import dynamic.mapper.logs.store.EventServiceLogSink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.cumulocity.rest.representation.event.EventRepresentation;

class EventServiceLogEventsTest {

    private static EventRepresentation event(Object fragment) {
        EventRepresentation e = new EventRepresentation();
        if (fragment != null) {
            e.setProperty(EventServiceLogSink.FRAGMENT, fragment);
        }
        return e;
    }

    @Test
    void aWellFormedEventBecomesAChunk() {
        ServiceLogReader.StoredChunk chunk = EventServiceLogEvents.toChunk(event(Map.of("dateFrom", "2026-10-15T12:00:00Z",
                "dateTo", "2026-10-15T12:00:05Z", "instance", "pod-1", "lines", 3, "omitted", 1, "logs", "x")));

        assertNotNull(chunk);
        assertEquals("pod-1", chunk.instance());
        assertEquals(3, chunk.lines());
        assertEquals(1, chunk.omitted());
    }

    @Test
    void anEventSomeoneElseCreatedWithGarbageDatesIsSkippedInsteadOfBreakingEveryRead() {
        // anyone who may create events in the tenant can create one of this type with any content
        assertNull(EventServiceLogEvents.toChunk(event(Map.of("dateFrom", "x", "dateTo", "yesterday"))));
    }

    @Test
    void anEventWithoutTheFragmentOrWithOtherShapesIsSkipped() {
        assertNull(EventServiceLogEvents.toChunk(event(null)));
        assertNull(EventServiceLogEvents.toChunk(event("just a string")));
        assertNull(EventServiceLogEvents.toChunk(event(Map.of("dateFrom", "2026-10-15T12:00:00Z"))));
    }

    @Test
    void theEventQueryOfAMappingKeepsTypeSourceAndDatesAndAddsTheFragment() {
        var params = EventServiceLogEvents.filter(com.cumulocity.model.idtype.GId.asGId("42"),
                java.time.Instant.parse("2026-10-15T12:00:00Z"), java.time.Instant.parse("2026-10-15T13:00:00Z"), "l19zjk")
                .getQueryParams();

        assertEquals("d11r_serviceLog", params.get("type"));
        assertEquals("42", params.get("source"));
        assertEquals("d11r_serviceLog_map_l19zjk", params.get("fragmentType"));
        assertNotNull(params.get("dateFrom"));
        assertNotNull(params.get("dateTo"));
    }

    @Test
    void theEventQueryWithoutAMappingHasNoFragmentRestriction() {
        var params = EventServiceLogEvents.filter(com.cumulocity.model.idtype.GId.asGId("42"), null, null, null).getQueryParams();

        assertEquals("d11r_serviceLog", params.get("type"));
        assertNull(params.get("fragmentType"));
    }

    @Test
    void anIdentifierThatIsNotOneNeverReachesTheQuery() {
        var events = new EventServiceLogEvents(null, null, null);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> events.list("t", null, null, false, 1, 50, "x&type=other"));
    }
}
