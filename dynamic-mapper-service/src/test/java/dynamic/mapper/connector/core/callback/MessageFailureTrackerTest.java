package dynamic.mapper.connector.core.callback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import dynamic.mapper.configuration.ServiceConfiguration;

class MessageFailureTrackerTest {

    private final MessageFailureTracker tracker = new MessageFailureTracker(() -> 3);

    @Test
    void dropsAMessageOnTheConfiguredFailure() {
        assertFalse(tracker.recordFailureReachedLimit("m"));
        assertFalse(tracker.recordFailureReachedLimit("m"));
        assertTrue(tracker.recordFailureReachedLimit("m"), "the third failure reaches the limit of 3");
    }

    @Test
    void startsFreshAfterADrop() {
        for (int i = 0; i < 3; i++) {
            tracker.recordFailureReachedLimit("m");
        }

        assertEquals(0, tracker.failures("m"));
        assertFalse(tracker.recordFailureReachedLimit("m"));
    }

    @Test
    void aSuccessForgetsTheFailures() {
        tracker.recordFailureReachedLimit("m");
        tracker.recordFailureReachedLimit("m");

        tracker.clear("m");

        assertEquals(0, tracker.failures("m"));
        assertFalse(tracker.recordFailureReachedLimit("m"));
    }

    @Test
    void messagesAreCountedSeparately() {
        tracker.recordFailureReachedLimit("a");
        tracker.recordFailureReachedLimit("a");

        assertFalse(tracker.recordFailureReachedLimit("b"));
        assertEquals(2, tracker.failures("a"));
    }

    @Test
    void theLimitFollowsTheConfigurationOnEveryFailure() {
        ServiceConfiguration configuration = mock(ServiceConfiguration.class);
        when(configuration.getMaxProcessingAttempts()).thenReturn(2);
        MessageFailureTracker followed = MessageFailureTracker.forConfiguration(() -> configuration);

        assertFalse(followed.recordFailureReachedLimit("m"));
        assertTrue(followed.recordFailureReachedLimit("m"));
        assertEquals(2, followed.limit());

        when(configuration.getMaxProcessingAttempts()).thenReturn(4);
        assertEquals(4, followed.limit());
    }

    @Test
    void theLimitFollowsAReplacedConfigurationObject() {
        ServiceConfiguration first = mock(ServiceConfiguration.class);
        when(first.getMaxProcessingAttempts()).thenReturn(2);
        ServiceConfiguration second = mock(ServiceConfiguration.class);
        when(second.getMaxProcessingAttempts()).thenReturn(7);
        java.util.concurrent.atomic.AtomicReference<ServiceConfiguration> registry =
                new java.util.concurrent.atomic.AtomicReference<>(first);
        MessageFailureTracker followed = MessageFailureTracker.forConfiguration(registry::get);

        assertEquals(2, followed.limit());
        registry.set(second);
        assertEquals(7, followed.limit());
    }

    @Test
    void aMissingOrInvalidConfigurationFallsBackToTheDefault() {
        ServiceConfiguration configuration = mock(ServiceConfiguration.class);
        when(configuration.getMaxProcessingAttempts()).thenReturn(null, 0, -1);

        assertEquals(ServiceConfiguration.DEFAULT_MAX_PROCESSING_ATTEMPTS,
                MessageFailureTracker.forConfiguration(() -> configuration).limit());
        assertEquals(ServiceConfiguration.DEFAULT_MAX_PROCESSING_ATTEMPTS,
                MessageFailureTracker.forConfiguration(() -> configuration).limit());
        assertEquals(ServiceConfiguration.DEFAULT_MAX_PROCESSING_ATTEMPTS,
                MessageFailureTracker.forConfiguration(() -> configuration).limit());
        assertEquals(ServiceConfiguration.DEFAULT_MAX_PROCESSING_ATTEMPTS,
                MessageFailureTracker.forConfiguration(() -> null).limit());
        assertEquals(5, ServiceConfiguration.DEFAULT_MAX_PROCESSING_ATTEMPTS);
    }

    @Test
    void memoryIsBounded() {
        for (int i = 0; i < 20_000; i++) {
            tracker.recordFailureReachedLimit("m" + i);
        }

        assertEquals(0, tracker.failures("m0"), "the oldest entries are forgotten");
        assertEquals(1, tracker.failures("m19999"));
    }
}
