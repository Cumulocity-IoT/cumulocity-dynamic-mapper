package dynamic.mapper.notification.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class ExplorerWebSocketCallbackTest {

    private final NotificationCallback delegate = mock(NotificationCallback.class);
    private final AtomicReference<String> issue = new AtomicReference<>("initial");
    private final ExplorerWebSocketCallback callback =
            new ExplorerWebSocketCallback("t1", "s1", delegate, issue::set);

    @Test
    void closeIsReportedToTheSessionAndNotToTheConnector() {
        callback.onClose(1006, "Invalid status code received: 409");

        assertTrue(issue.get().contains("409"));
        verifyNoMoreInteractions(delegate); // the connector's status must stay untouched
    }

    @Test
    void openClearsTheReportedIssue() {
        callback.onClose(1006, null);
        assertTrue(issue.get().contains("1006"));

        callback.onOpen(URI.create("ws://localhost"));

        assertNull(issue.get());
        verifyNoMoreInteractions(delegate);
    }

    @Test
    void notificationsStillGoToTheDelegate() {
        Notification n = mock(Notification.class);
        callback.onNotification(n);
        verify(delegate).onNotification(n);
    }

    @Test
    void failingReporterDoesNotBreakTheCallback() {
        ExplorerWebSocketCallback failing = new ExplorerWebSocketCallback("t1", "s1", delegate, m -> {
            throw new IllegalStateException("boom");
        });
        failing.onClose(1000, "x");
        assertEquals("initial", issue.get());
    }
}
