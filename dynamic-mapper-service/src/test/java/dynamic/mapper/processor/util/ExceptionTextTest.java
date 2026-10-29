package dynamic.mapper.processor.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.util.ConcurrentModificationException;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class ExceptionTextTest {

    @Test
    void theMessageIsUsedWhenThereIsOne() {
        assertEquals("boom", ExceptionText.of(new IllegalStateException("boom")));
    }

    @Test
    void anExceptionWithoutMessageFallsBackToItsCauseThenToItsClass() {
        assertEquals("root cause",
                ExceptionText.of(new RuntimeException(null, new IllegalArgumentException("root cause"))));
        assertEquals("java.util.ConcurrentModificationException",
                ExceptionText.of(new ConcurrentModificationException()));
        assertEquals("java.util.ConcurrentModificationException",
                ExceptionText.of(new ConcurrentModificationException("  ")));
    }

    @Test
    void nullIsHandled() {
        assertEquals("unknown error", ExceptionText.of(null));
    }

    @Test
    void causeCyclesDoNotHang() {
        RuntimeException a = new RuntimeException();
        RuntimeException b = new RuntimeException();
        a.initCause(b);
        b.initCause(a);

        assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> assertEquals("java.lang.RuntimeException", ExceptionText.of(a)));
    }

    @Test
    void selfCauseDoesNotHang() {
        RuntimeException a = new RuntimeException() {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> ExceptionText.of(a));
    }
}
