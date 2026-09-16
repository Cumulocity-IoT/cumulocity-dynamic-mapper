/*
 * Copyright (c) 2025 Cumulocity GmbH.
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.model.DynamicMapperRequest;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.runtime.OutputCollector;
import dynamic.mapper.processor.runtime.ProcessingContext;
import dynamic.mapper.processor.runtime.RoutingContext;
import dynamic.mapper.processor.util.CamelHeaders;
import dynamic.mapper.service.MappingService;

/**
 * Covers {@code AbstractExtensibleResultProcessor.syncOutputToContext(...)}.
 *
 * <p>The processor accumulates into a local {@link OutputCollector} and merges it back into
 * the {@link ProcessingContext}, which is the single mutable owner of that state. An earlier
 * revision merged only {@code requests} and {@code warnings}, silently dropping {@code errors}
 * and {@code logs}. Nothing exercised this path, so the gap was invisible — hence this test.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AbstractExtensibleResultProcessorSyncTest {

    private static final String TEST_TENANT = "testTenant";

    @Mock
    private MappingService mappingService;

    @Mock
    private C8YAgent c8yAgent;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private Exchange exchange;

    @Mock
    private Message message;

    private ProcessingContext<Object> context;

    @BeforeEach
    void setUp() {
        Mapping mapping = new Mapping();
        mapping.setName("Test Mapping");

        context = ProcessingContext.builder()
                .tenant(TEST_TENANT)
                .mapping(mapping)
                .topic("test/topic")
                .build();

        when(exchange.getIn()).thenReturn(message);
        when(message.getHeader(CamelHeaders.PROCESSING_CONTEXT, ProcessingContext.class))
                .thenReturn(context);
    }

    /**
     * Processor that pushes one entry into every channel of the collector, so the test can
     * assert that all four survive the merge.
     */
    private AbstractExtensibleResultProcessor processorEmitting(
            DynamicMapperRequest request, Exception error, String warning, String logLine) {
        return new AbstractExtensibleResultProcessor(mappingService, objectMapper, c8yAgent) {
            @Override
            protected void processExtensionResults(
                    RoutingContext routing, OutputCollector output, ProcessingContext<?> ctx) {
                output.addRequest(request);
                output.addError(error);
                output.addWarning(warning);
                output.addLog(logLine);
            }

            @Override
            protected void handleProcessingError(
                    Exception e, ProcessingContext<?> ctx, String tenant, Mapping mapping) {
                // process() swallows exceptions via this hook; rethrow so a broken test
                // surfaces as a failure instead of a silently empty context.
                throw new IllegalStateException("unexpected processing error", e);
            }
        };
    }

    @Test
    void syncsAllFourChannelsBackToContext() throws Exception {
        DynamicMapperRequest request = new DynamicMapperRequest();
        Exception error = new IllegalStateException("extension blew up");

        processorEmitting(request, error, "a warning", "a log line").process(exchange);

        assertEquals(1, context.getRequests().size(), "requests should be merged");
        assertSame(request, context.getRequests().get(0));

        assertEquals(1, context.getErrors().size(), "errors must not be dropped");
        assertSame(error, context.getErrors().get(0));

        assertEquals(1, context.getWarnings().size(), "warnings should be merged");
        assertEquals("a warning", context.getWarnings().get(0));

        assertEquals(1, context.getLogs().size(), "logs must not be dropped");
        assertEquals("a log line", context.getLogs().get(0));
    }

    @Test
    void appendsRatherThanReplacingExistingContextEntries() throws Exception {
        DynamicMapperRequest preExisting = new DynamicMapperRequest();
        context.getRequests().add(preExisting);
        context.getWarnings().add("earlier warning");
        context.getLogs().add("earlier log");

        DynamicMapperRequest fresh = new DynamicMapperRequest();
        processorEmitting(fresh, new IllegalStateException("boom"), "new warning", "new log")
                .process(exchange);

        // Entries written by earlier pipeline steps must survive the merge.
        assertEquals(2, context.getRequests().size());
        assertSame(preExisting, context.getRequests().get(0));
        assertSame(fresh, context.getRequests().get(1));

        assertEquals(2, context.getWarnings().size());
        assertEquals("earlier warning", context.getWarnings().get(0));

        assertEquals(2, context.getLogs().size());
        assertEquals("earlier log", context.getLogs().get(0));
    }
}
