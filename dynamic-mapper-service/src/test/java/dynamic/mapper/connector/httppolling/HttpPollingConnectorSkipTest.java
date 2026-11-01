package dynamic.mapper.connector.httppolling;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.mapping.MappingService;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.status.MappingStatus;

/**
 * A polled page whose processing keeps failing is retried until {@code maxProcessingAttempts}, then skipped
 * with the skip recorded as the mapping's last error, so one bad page cannot block the topic forever.
 */
class HttpPollingConnectorSkipTest {

    private static final String TOPIC = "devices/measurements";

    private HttpPollingConnector client;
    private MappingStatus status;
    private Mapping mapping;

    @BeforeEach
    void setUp() {
        MappingService mappingService = mock(MappingService.class);
        status = mock(MappingStatus.class);
        mapping = Mapping.builder().identifier("m1").mappingTopic(TOPIC).build();
        when(mappingService.getMappingStatus("t1", mapping)).thenReturn(status);
        ServiceConfiguration configuration = mock(ServiceConfiguration.class);
        when(configuration.getMaxProcessingAttempts()).thenReturn(3);

        client = new HttpPollingConnector();
        ReflectionTestUtils.setField(client, "tenant", "t1");
        ReflectionTestUtils.setField(client, "connectorIdentifier", "c1");
        ReflectionTestUtils.setField(client, "mappingService", mappingService);
        ReflectionTestUtils.setField(client, "serviceConfiguration", configuration);
    }

    @Test
    void aProcessedPageIsSettledWithoutAnError() {
        assertTrue(client.settlePage(mapping, TOPIC, "{}", true));

        verify(status, never()).recordError(anyLong(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void aFailedPageIsRetriedUntilTheLimitThenSkippedWithARecordedError() {
        when(status.getCursor()).thenReturn("c-100");

        assertFalse(client.settlePage(mapping, TOPIC, "{\"a\":1}", false));
        assertFalse(client.settlePage(mapping, TOPIC, "{\"a\":1}", false));
        verify(status, never()).recordError(anyLong(), org.mockito.ArgumentMatchers.any());

        assertTrue(client.settlePage(mapping, TOPIC, "{\"a\":1}", false), "third failure: skip");
        verify(status).recordError(anyLong(), contains("Skipped polled data of topic [" + TOPIC + "] after 3"));
    }

    @Test
    void withACursorTheSamePositionCountsEvenWhenNewDataChangesTheBody() {
        when(status.getCursor()).thenReturn("c-100");

        assertFalse(client.settlePage(mapping, TOPIC, "{\"items\":[1]}", false));
        assertFalse(client.settlePage(mapping, TOPIC, "{\"items\":[1,2]}", false)); // new data arrived
        assertTrue(client.settlePage(mapping, TOPIC, "{\"items\":[1,2,3]}", false));
    }

    @Test
    void withoutACursorTheBodyIdentifiesThePage() {
        when(status.getCursor()).thenReturn(null);

        assertFalse(client.settlePage(mapping, TOPIC, "{\"a\":1}", false));
        assertFalse(client.settlePage(mapping, TOPIC, "{\"a\":1}", false));
        assertFalse(client.settlePage(mapping, TOPIC, "{\"b\":2}", false), "a different page has its own count");
        assertTrue(client.settlePage(mapping, TOPIC, "{\"a\":1}", false));
    }

    @Test
    void aProcessedPageForgetsEarlierFailures() {
        when(status.getCursor()).thenReturn("c-100");
        client.settlePage(mapping, TOPIC, "{}", false);
        client.settlePage(mapping, TOPIC, "{}", false);
        assertTrue(client.settlePage(mapping, TOPIC, "{}", true));

        assertFalse(client.settlePage(mapping, TOPIC, "{}", false));
        assertFalse(client.settlePage(mapping, TOPIC, "{}", false));
    }

    @Test
    void aSkipWithoutAMappingIsStillSettledAndLogged() {
        for (int i = 0; i < 2; i++) {
            assertFalse(client.settlePage(null, TOPIC, "{}", false));
        }

        assertTrue(client.settlePage(null, TOPIC, "{}", false));
        verify(status, never()).recordError(anyLong(), eq(null));
    }
}
