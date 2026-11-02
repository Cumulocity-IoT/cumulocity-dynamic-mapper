package dynamic.mapper.connector.core.callback;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import dynamic.mapper.configuration.ServiceConfiguration;

/**
 * Counts how often a message failed so a message that always fails does not loop forever.
 *
 * <p>Every connector that retries (a nack, a requeue, a seek back, a reconnect) records each failure here.
 * When the count reaches the limit the message is dropped: the connector acknowledges it and the mapping
 * status keeps the error. The limit is the service configuration {@code maxProcessingAttempts}
 * (default {@value #DEFAULT_MAX_ATTEMPTS}).</p>
 *
 * <p>The key must stay the same when the broker redelivers the message (a message id, an offset, or a
 * hash of topic and payload, not a delivery tag). Entries are bounded: when more than {@value #MAX_TRACKED}
 * messages are tracked the oldest is forgotten, which only means that message gets a fresh count.</p>
 */
public class MessageFailureTracker {

    public static final int DEFAULT_MAX_ATTEMPTS = ServiceConfiguration.DEFAULT_MAX_PROCESSING_ATTEMPTS;
    private static final int MAX_TRACKED = 10_000;

    private final IntSupplier maxAttempts;
    private final Map<String, Integer> failures = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
            return size() > MAX_TRACKED;
        }
    };

    public MessageFailureTracker(IntSupplier maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    /**
     * A tracker that resolves the tenant's current configuration on every failure. Use this form with a
     * registry lookup: updating the service configuration replaces the registry entry, so a captured
     * instance would keep the old limit.
     */
    public static MessageFailureTracker forConfiguration(Supplier<ServiceConfiguration> configuration) {
        return new MessageFailureTracker(() -> {
            ServiceConfiguration current = configuration.get();
            Integer configured = current == null ? null : current.getMaxProcessingAttempts();
            return configured == null || configured < 1 ? DEFAULT_MAX_ATTEMPTS : configured;
        });
    }

    /** Failures recorded so far for the message, for example to lengthen the wait on a retransmission. */
    public synchronized int failures(String key) {
        return failures.getOrDefault(key, 0);
    }

    /**
     * Records one failure of the message.
     *
     * @return {@code true} if the limit is reached: the counter is cleared and the caller must drop the
     *         message (acknowledge it) instead of retrying
     */
    public synchronized boolean recordFailureReachedLimit(String key) {
        int count = failures.merge(key, 1, Integer::sum);
        if (count >= maxAttempts.getAsInt()) {
            failures.remove(key);
            return true;
        }
        return false;
    }

    /** The message was processed (or dropped): forget its failures. */
    public synchronized void clear(String key) {
        failures.remove(key);
    }

    /** The current limit. */
    public int limit() {
        return maxAttempts.getAsInt();
    }
}
