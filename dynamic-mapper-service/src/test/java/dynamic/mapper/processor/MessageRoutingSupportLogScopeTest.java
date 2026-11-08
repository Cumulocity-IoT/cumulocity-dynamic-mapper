/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *  @authors Christof Strack, Stefan Witschel
 *
 */

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
