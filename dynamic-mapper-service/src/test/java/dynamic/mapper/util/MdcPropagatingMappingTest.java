package dynamic.mapper.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;

class MdcPropagatingMappingTest {

    @Test
    void aTaskKeepsTheMappingOfItsSubmitterAndAPooledThreadNeverKeepsItAfterwards() throws Exception {
        ExecutorService pool = new MdcPropagatingExecutorService(Executors.newSingleThreadExecutor());
        try {
            String inside;
            try (MappingLogContext scope = MappingLogContext.open("aaa")) {
                inside = pool.submit(MappingLogContext::current).get();
            }
            String outside = pool.submit(MappingLogContext::current).get();

            assertEquals("aaa", inside);
            assertNull(outside);
        } finally {
            pool.shutdownNow();
        }
    }
}
