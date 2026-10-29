package dynamic.mapper.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.util.MessageRoutingSupport;
import dynamic.mapper.util.MappingLogContext;

class MessageRoutingSupportLogScopeTest {

    private static class Routing extends MessageRoutingSupport {
        Routing() {
            super(mock(ConnectorRegistry.class));
        }

        @Override
        protected boolean isMappingDeployed(String tenant, Mapping mapping, String connectorIdentifier) {
            return false;
        }

        boolean valid(Mapping mapping) {
            return isValidMapping("t1", mapping, "dmmqttsvc");
        }
    }

    @Test
    void theNotDeployedLineNamesItsMappingInTheLogContext() {
        Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(MessageRoutingSupport.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            Mapping mapping = new Mapping();
            mapping.setIdentifier("reconcile72360");
            mapping.setName("test-reconcile");
            mapping.setId("491727545");
            mapping.setActive(true);

            assertFalse(new Routing().valid(mapping));

            ILoggingEvent line = appender.list.stream()
                    .filter(e -> e.getFormattedMessage().contains("not deployed")).findFirst().orElseThrow();
            assertEquals("reconcile72360", MappingLogContext.mappingOf(line.getMDCPropertyMap()));
            assertNull(MDC.get(MappingLogContext.KEY), "the scope is left again");
        } finally {
            logger.detachAppender(appender);
        }
    }
}
