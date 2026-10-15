/*
 * Copyright (c) 2026 Cumulocity GmbH.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package dynamic.mapper.connector.pulsar;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.callback.GenericMessageCallback;
import dynamic.mapper.core.ServiceRegistry;
import dynamic.mapper.processor.runtime.ProcessingResultWrapper;
import dynamic.mapper.util.TenantLogContext;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageId;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A connector callback runs on the client library's own thread, with no tenant scope, and calls the
 * dispatcher synchronously. Everything logged from there — the callback's own lines and the
 * dispatcher's — must carry the tenant, and the library thread must be left clean afterwards.
 */
class CallbackTenantScopeTest {

    @Test
    void receivedRunsTheDispatcherUnderTheCallbacksTenant() {
        ServiceRegistry registry = mock(ServiceRegistry.class);
        GenericMessageCallback dispatcher = mock(GenericMessageCallback.class);
        Consumer<byte[]> consumer = mock();
        Message<byte[]> message = mock();
        AtomicReference<String> seenByDispatcher = new AtomicReference<>("not called");

        when(registry.getServiceConfiguration("tenantA")).thenReturn(new ServiceConfiguration());
        when(registry.getVirtualThreadPool()).thenReturn(mock(ExecutorService.class));
        when(message.getTopicName()).thenReturn("persistent://public/default/x");
        when(message.getData()).thenReturn("{}".getBytes(StandardCharsets.UTF_8));
        when(message.getMessageId()).thenReturn(MessageId.earliest);
        when(dispatcher.onMessage(any())).thenAnswer(invocation -> {
            seenByDispatcher.set(TenantLogContext.current());
            return ProcessingResultWrapper.builder().build();
        });

        assertNull(TenantLogContext.current());
        new PulsarCallback("tenantA", registry, dispatcher, "pulsar", "Pulsar").received(consumer, message);

        assertEquals("tenantA", seenByDispatcher.get());
        assertNull(TenantLogContext.current(), "the library thread must not keep the tenant");
    }
}
