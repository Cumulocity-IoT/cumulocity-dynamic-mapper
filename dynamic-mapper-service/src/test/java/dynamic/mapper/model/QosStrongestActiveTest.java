package dynamic.mapper.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Which QoS a set of resolved mappings asks the transport for. */
class QosStrongestActiveTest {

    private Mapping mapping(Qos qos, boolean active) {
        Mapping m = new Mapping();
        m.setQos(qos);
        m.setActive(active);
        return m;
    }

    @Test
    void theStrongestActiveLevelWins() {
        assertEquals(Qos.EXACTLY_ONCE, Qos.strongestActive(List.of(mapping(Qos.AT_LEAST_ONCE, true),
                mapping(Qos.EXACTLY_ONCE, true), mapping(Qos.AT_MOST_ONCE, true))));
    }

    @Test
    void anInactiveMappingDoesNotRaiseTheLevel() {
        assertEquals(Qos.AT_LEAST_ONCE, Qos.strongestActive(List.of(mapping(Qos.AT_LEAST_ONCE, true),
                mapping(Qos.EXACTLY_ONCE, false))));
    }

    @Test
    void noMappingOrNoActiveMappingMeansAtMostOnce() {
        assertEquals(Qos.AT_MOST_ONCE, Qos.strongestActive(null));
        assertEquals(Qos.AT_MOST_ONCE, Qos.strongestActive(List.of()));
        assertEquals(Qos.AT_MOST_ONCE, Qos.strongestActive(List.of(mapping(Qos.EXACTLY_ONCE, false))));
    }

    @Test
    void aMappingWithoutQosCountsAsTheDefaultAndNullElementsAreIgnored() {
        assertEquals(Qos.DEFAULT, Qos.strongestActive(Arrays.asList(null, mapping(null, true))));
    }
}
