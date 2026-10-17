/*
 * Copyright (c) 2025 Cumulocity GmbH
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @authors Christof Strack
 */

package dynamic.mapper.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.inbound.InboundMessageRouter;
import dynamic.mapper.processor.inbound.processor.*;
import dynamic.mapper.processor.model.C8YMessage;
import dynamic.mapper.processor.outbound.OutboundMessageRouter;
import dynamic.mapper.processor.outbound.processor.*;
import dynamic.mapper.processor.util.ConsolidationProcessor;
import dynamic.mapper.util.MappingLogContext;

/**
 * The service log filter relies on every line logged while a router processes one mapping carrying that mapping's
 * identifier. The first processor of the pipeline is made to fail, which makes the router log its route error from
 * inside the scope — a real log line of the real router, whose MDC is then inspected.
 */
class RouterMappingLogScopeTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger routeLogger;

    @BeforeEach
    void capture() {
        routeLogger = (Logger) LoggerFactory.getLogger("dynamic.mapper.processor.util.MessageRoutingSupport");
        appender = new ListAppender<>();
        appender.start();
        routeLogger.addAppender(appender);
    }

    @AfterEach
    void release() {
        routeLogger.detachAppender(appender);
        MDC.clear();
    }

    private static Mapping mapping(String identifier) {
        return Mapping.builder().id("id-" + identifier).identifier(identifier).name("m " + identifier).build();
    }

    private List<String> mappingsOfRouteErrors() {
        List<String> out = new ArrayList<>();
        for (ILoggingEvent e : appender.list) {
            if (e.getFormattedMessage().contains("ROUTE ERROR")) {
                out.add(e.getMDCPropertyMap().get(MappingLogContext.KEY));
            }
        }
        return out;
    }

    @Test
    void inboundLinesOfAMappingCarryItsIdentifierAndNothingLeaksToTheNextMapping() throws Exception {
        DeserializationInboundProcessor deserialization = mock(DeserializationInboundProcessor.class);
        when(deserialization.process(any(), any(), any(), any(), anyBoolean())).thenThrow(new IllegalStateException("boom"));
        InboundMessageRouter router = new InboundMessageRouter(mock(ConnectorRegistry.class), mock(ExecutorService.class),
                mock(ExtensibleInboundProcessor.class), mock(ExtensibleResultInboundProcessor.class),
                mock(InternalProtobufProcessor.class), mock(EnrichmentInboundProcessor.class), mock(FlowInboundProcessor.class),
                mock(SubstitutionResultInboundProcessor.class), deserialization, mock(JSONataInboundProcessor.class),
                mock(FlowResultInboundProcessor.class), mock(FilterInboundProcessor.class), mock(SendInboundProcessor.class),
                mock(ConsolidationProcessor.class));
        Method process = InboundMessageRouter.class.getDeclaredMethod("processSingleInboundMapping", Mapping.class,
                ConnectorMessage.class, boolean.class, ServiceConfiguration.class, dynamic.mapper.processor.runtime.ProcessingResultWrapper.class);
        process.setAccessible(true);
        ConnectorMessage message = mock(ConnectorMessage.class);
        when(message.getTenant()).thenReturn("tenantA");

        MDC.put(MappingLogContext.KEY, "enclosing"); // whatever was in the context before is restored afterwards
        process.invoke(router, mapping("aaa"), message, false, mock(ServiceConfiguration.class), null);
        process.invoke(router, mapping("bbb"), message, false, mock(ServiceConfiguration.class), null);

        assertEquals(List.of("aaa", "bbb"), mappingsOfRouteErrors());
        assertEquals("enclosing", MDC.get(MappingLogContext.KEY));
    }

    @Test
    void outboundLinesOfAMappingCarryItsIdentifierAndTheScopeIsClosedAfterwards() throws Exception {
        DeserializationOutboundProcessor deserialization = mock(DeserializationOutboundProcessor.class);
        when(deserialization.process(any(), any(), any(), any(), anyBoolean())).thenThrow(new IllegalStateException("boom"));
        OutboundMessageRouter router = new OutboundMessageRouter(mock(ConnectorRegistry.class), mock(ExecutorService.class),
                mock(EnrichmentOutboundProcessor.class), mock(ExtensibleOutboundProcessor.class),
                mock(ExtensibleResultOutboundProcessor.class), mock(FlowOutboundProcessor.class),
                mock(SubstitutionResultOutboundProcessor.class), deserialization, mock(JSONataOutboundProcessor.class),
                mock(FlowResultOutboundProcessor.class), mock(SendOutboundProcessor.class), mock(ConsolidationProcessor.class));
        Method process = OutboundMessageRouter.class.getDeclaredMethod("processSingleOutboundMapping", Mapping.class,
                C8YMessage.class, String.class, boolean.class, ServiceConfiguration.class,
                dynamic.mapper.processor.runtime.ProcessingResultWrapper.class);
        process.setAccessible(true);
        C8YMessage message = mock(C8YMessage.class);
        when(message.getTenant()).thenReturn("tenantA");

        process.invoke(router, mapping("ccc"), message, "connector", false, mock(ServiceConfiguration.class), null);

        assertEquals(List.of("ccc"), mappingsOfRouteErrors());
        assertNull(MDC.get(MappingLogContext.KEY), "the scope is closed once the mapping is done");
        assertTrue(appender.list.size() > 0);
    }
}
