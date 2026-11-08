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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.core.ExtensionInboundRegistry;
import dynamic.mapper.mapping.MappingService;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.TransformationType;
import dynamic.mapper.model.extension.Extension;
import dynamic.mapper.model.extension.ExtensionEntry;
import dynamic.mapper.model.status.MappingStatus;
import dynamic.mapper.processor.extension.ProcessorExtensionInbound;
import dynamic.mapper.processor.extension.ProcessorExtensionOutbound;
import dynamic.mapper.processor.flow.FlowStateStore;
import dynamic.mapper.processor.inbound.processor.ExtensibleInboundProcessor;
import dynamic.mapper.processor.model.CumulocityObject;
import dynamic.mapper.processor.model.DeviceMessage;
import dynamic.mapper.processor.outbound.processor.ExtensibleOutboundProcessor;
import dynamic.mapper.processor.runtime.ProcessingContext;

/**
 * An extension's native state is written back by the processor after {@code onMessage}, for both
 * directions, also when the extension returns nothing, but not when it throws and not for test runs.
 */
class ExtensibleProcessorStatePersistenceTest {

    private static final String TENANT = "t1";
    private static final String MAPPING_ID = "m1";

    private final FlowStateStore store = new FlowStateStore();
    private final ExtensionInboundRegistry registry = mock(ExtensionInboundRegistry.class);
    private final MappingService mappingService = mock(MappingService.class);
    private final ProcessorExtensionInbound<byte[]> inboundExtension = mock(ProcessorExtensionInbound.class);
    private final ProcessorExtensionOutbound<Object> outboundExtension = mock(ProcessorExtensionOutbound.class);
    private Mapping mapping;
    private ExtensionEntry registered;

    @BeforeEach
    void setUp() {
        ExtensionEntry configured = ExtensionEntry.builder()
                .extensionName("ext").eventName("evt").fqnClassName("x.Y").direction(Direction.UNSPECIFIED).build();
        mapping = Mapping.builder()
                .identifier(MAPPING_ID).name("m")
                .transformationType(TransformationType.EXTENSION_JAVA)
                .extension(configured)
                .build();
        registered = ExtensionEntry.builder()
                .extensionName("ext").eventName("evt").fqnClassName("x.Y").direction(Direction.UNSPECIFIED).loaded(true).build();
        registered.setExtensionImplInbound(inboundExtension);
        registered.setExtensionImplOutbound(outboundExtension);
        Extension extension = new Extension("id", "ext");
        extension.getExtensionEntries().put("evt", registered);
        when(registry.getExtension(TENANT, "ext")).thenReturn(extension);
        when(mappingService.getMappingStatus(any(), any(Mapping.class)))
                .thenReturn(new MappingStatus("id", "m", MAPPING_ID, Direction.INBOUND, "t", "o", 0L, 0L, 0L, null));
    }

    private <O> ProcessingContext<O> context(O payload, boolean testing, Direction direction) {
        mapping.setDirection(direction);
        registered.setDirection(Direction.UNSPECIFIED);
        return ProcessingContext.<O>builder()
                .tenant(TENANT).topic("a/b").mapping(mapping).payload(payload)
                .serviceConfiguration(new ServiceConfiguration()).testing(testing)
                .build();
    }

    private void seed(Object value) {
        store.commitChanges(TENANT, MAPPING_ID, false, Map.of("seen", value), java.util.Set.of());
    }

    // ---- inbound ----

    @Test
    void inboundStateIsWrittenBackAndSeenByTheNextMessage() throws Exception {
        when(inboundExtension.onMessage(any(), any())).thenAnswer(inv -> {
            var ctx = (dynamic.mapper.processor.model.JavaExtensionContext) inv.getArgument(1);
            Object previous = ctx.getNativeState("seen");
            ctx.setNativeState("seen", previous == null ? 1 : (Integer) previous + 1);
            return new CumulocityObject[] { new CumulocityObject() };
        });
        ExtensibleInboundProcessor processor = new ExtensibleInboundProcessor(
                mappingService, registry, null, store);

        // SendInboundProcessor runs the deferred commit after a successful delivery.
        for (int i = 0; i < 2; i++) {
            ProcessingContext<byte[]> ctx = context(new byte[0], false, Direction.INBOUND);
            processor.process(ctx);
            ctx.runDeferredStateCommit();
        }

        assertEquals(2, store.loadState(TENANT, MAPPING_ID).get("seen"));
    }

    @Test
    void inboundStateIsNotWrittenUntilDeliverySucceeds() throws Exception {
        when(inboundExtension.onMessage(any(), any())).thenAnswer(inv -> {
            ((dynamic.mapper.processor.model.JavaExtensionContext) inv.getArgument(1)).setNativeState("id", "m1");
            return new CumulocityObject[] { new CumulocityObject() };
        });
        ProcessingContext<byte[]> ctx = context(new byte[0], false, Direction.INBOUND);

        new ExtensibleInboundProcessor(mappingService, registry, null, store).process(ctx);

        assertNull(store.loadState(TENANT, MAPPING_ID).get("id"));
    }

    @Test
    void inboundStateIsWrittenBackWhenTheExtensionReturnsNothing() throws Exception {
        when(inboundExtension.onMessage(any(), any())).thenAnswer(inv -> {
            ((dynamic.mapper.processor.model.JavaExtensionContext) inv.getArgument(1)).setNativeState("hash", "h1");
            return new CumulocityObject[0]; // deduplicated
        });
        ProcessingContext<byte[]> ctx = context(new byte[0], false, Direction.INBOUND);

        new ExtensibleInboundProcessor(mappingService, registry, null, store).process(ctx);

        assertEquals("h1", store.loadState(TENANT, MAPPING_ID).get("hash"));
        assertTrue(ctx.isIgnoreFurtherProcessing());
        assertFalse(ctx.hasError());
    }

    @Test
    void inboundStateIsNotWrittenWhenTheExtensionThrows() throws Exception {
        seed(1);
        when(inboundExtension.onMessage(any(), any())).thenAnswer(inv -> {
            ((dynamic.mapper.processor.model.JavaExtensionContext) inv.getArgument(1)).setNativeState("seen", 99);
            throw new IllegalStateException("boom");
        });
        ProcessingContext<byte[]> ctx = context(new byte[0], false, Direction.INBOUND);

        new ExtensibleInboundProcessor(mappingService, registry, null, store).process(ctx);

        assertTrue(ctx.hasError());
        assertEquals(1, store.loadState(TENANT, MAPPING_ID).get("seen"),
                "a failed message is redelivered, so its state change must not be kept");
    }

    @Test
    void inboundTestRunDoesNotTouchStoredState() throws Exception {
        seed(1);
        when(inboundExtension.onMessage(any(), any())).thenAnswer(inv -> {
            var ctx = (dynamic.mapper.processor.model.JavaExtensionContext) inv.getArgument(1);
            assertEquals(null, ctx.getNativeState("seen"), "a test run starts from empty state");
            ctx.setNativeState("seen", 99);
            return new CumulocityObject[] { new CumulocityObject() };
        });

        new ExtensibleInboundProcessor(mappingService, registry, null, store)
                .process(context(new byte[0], true, Direction.INBOUND));

        assertEquals(1, store.loadState(TENANT, MAPPING_ID).get("seen"));
    }

    // ---- outbound ----

    @Test
    void outboundStateIsWrittenBackAndSeenByTheNextMessage() throws Exception {
        when(outboundExtension.onMessage(any(), any())).thenAnswer(inv -> {
            var ctx = (dynamic.mapper.processor.model.JavaExtensionContext) inv.getArgument(1);
            Object previous = ctx.getNativeState("seen");
            ctx.setNativeState("seen", previous == null ? 1 : (Integer) previous + 1);
            return new DeviceMessage[] { new DeviceMessage() };
        });
        ExtensibleOutboundProcessor processor = new ExtensibleOutboundProcessor(mappingService, registry, store);

        // SendOutboundProcessor runs the deferred commit after a successful publish.
        for (int i = 0; i < 2; i++) {
            ProcessingContext<Object> ctx = context(new Object(), false, Direction.OUTBOUND);
            processor.process(ctx);
            ctx.runDeferredStateCommit();
        }

        assertEquals(2, store.loadState(TENANT, MAPPING_ID).get("seen"));
    }

    @Test
    void outboundStateIsNotWrittenUntilPublishSucceeds() throws Exception {
        when(outboundExtension.onMessage(any(), any())).thenAnswer(inv -> {
            ((dynamic.mapper.processor.model.JavaExtensionContext) inv.getArgument(1)).setNativeState("id", "m1");
            return new DeviceMessage[] { new DeviceMessage() };
        });

        new ExtensibleOutboundProcessor(mappingService, registry, store)
                .process(context(new Object(), false, Direction.OUTBOUND));

        assertNull(store.loadState(TENANT, MAPPING_ID).get("id"));
    }

    @Test
    void outboundTestRunDoesNotTouchStoredState() throws Exception {
        seed(1);
        when(outboundExtension.onMessage(any(), any())).thenAnswer(inv -> {
            ((dynamic.mapper.processor.model.JavaExtensionContext) inv.getArgument(1)).setNativeState("seen", 99);
            return new DeviceMessage[] { new DeviceMessage() };
        });

        new ExtensibleOutboundProcessor(mappingService, registry, store)
                .process(context(new Object(), true, Direction.OUTBOUND));

        assertEquals(1, store.loadState(TENANT, MAPPING_ID).get("seen"));
    }
}
