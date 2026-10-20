package dynamic.mapper.mapping.resolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dynamic.mapper.mapping.cache.MappingCacheManager;
import dynamic.mapper.model.API;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.model.C8YMessage;
import dynamic.mapper.util.MappingLogContext;

class MappingResolverServiceLogTest {

    private final Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(MappingResolverService.class);
    private ListAppender<ILoggingEvent> appender;
    private Level previousLevel;

    @BeforeEach
    void capture() {
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void release() {
        logger.detachAppender(appender);
        logger.setLevel(previousLevel);
    }

    private static Mapping mapping(String identifier, boolean active, boolean debug, API targetApi) {
        Mapping m = new Mapping();
        m.setIdentifier(identifier);
        m.setName("name-" + identifier);
        m.setActive(active);
        m.setDebug(debug);
        m.setTargetAPI(targetApi);
        return m;
    }

    private List<ILoggingEvent> resolve(Mapping... mappings) throws Exception {
        MappingCacheManager cache = mock(MappingCacheManager.class);
        Map<String, Mapping> all = new LinkedHashMap<>();
        for (Mapping m : mappings) {
            all.put(m.getIdentifier(), m);
        }
        when(cache.getAllOutboundMappings("t1")).thenReturn(all);
        C8YMessage message = new C8YMessage();
        message.setApi(API.MEASUREMENT);
        new MappingResolverService(cache, mock(InventoryFilterEvaluator.class)).resolveOutbound("t1", message);
        return appender.list.stream().filter(e -> e.getFormattedMessage().contains("filtered out")).toList();
    }

    @Test
    void anInactiveMappingIsLoggedAtDebugEvenWithItsDebugFlagOn() throws Exception {
        List<ILoggingEvent> lines = resolve(mapping("inact1", false, true, API.MEASUREMENT));

        assertEquals(1, lines.size());
        assertEquals(Level.DEBUG, lines.get(0).getLevel());
        assertEquals("inact1", MappingLogContext.mappingOf(lines.get(0).getMDCPropertyMap()));
    }

    @Test
    void aMappingThatDoesNotMatchTheApiIsLoggedAtInfoInItsOwnContextWhenItsDebugFlagIsOn() throws Exception {
        List<ILoggingEvent> lines = resolve(mapping("flt1", true, true, API.EVENT));

        assertEquals(1, lines.size());
        assertEquals(Level.INFO, lines.get(0).getLevel());
        assertEquals("flt1", MappingLogContext.mappingOf(lines.get(0).getMDCPropertyMap()));
        assertTrue(lines.get(0).getFormattedMessage().contains("source API filter mismatch"));
        assertEquals(null, MappingLogContext.current(), "the scope is left again");
    }

    @Test
    void aMappingWithoutTheDebugFlagStaysSilentAtInfo() throws Exception {
        List<ILoggingEvent> lines = resolve(mapping("flt2", true, false, API.EVENT));

        assertTrue(lines.isEmpty());
    }

    @Test
    void everyMappingGetsItsOwnTag() throws Exception {
        List<ILoggingEvent> lines = resolve(mapping("a1", true, true, API.EVENT), mapping("b2", true, true, API.ALARM));

        assertEquals(List.of("a1", "b2"),
                lines.stream().map(e -> MappingLogContext.mappingOf(e.getMDCPropertyMap())).toList());
    }
}
