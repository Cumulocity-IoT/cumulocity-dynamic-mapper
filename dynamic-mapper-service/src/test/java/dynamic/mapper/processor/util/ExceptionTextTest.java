package dynamic.mapper.processor.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ConcurrentModificationException;

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
}
