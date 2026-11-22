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

package dynamic.mapper.connector.pulsar;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.PulsarClientException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.callback.GenericMessageCallback;
import dynamic.mapper.core.ServiceRegistry;
import dynamic.mapper.model.Qos;
import dynamic.mapper.processor.runtime.ProcessingContext;
import dynamic.mapper.processor.runtime.ProcessingResultWrapper;
import dynamic.mapper.processor.util.ProcessingResultHelper;

/**
 * A Pulsar message is acknowledged only after its pipeline succeeded, negatively acknowledged when it failed, and
 * discarded as a poison pill after {@code maxProcessingAttempts} failures. The decision is the shared
 * {@code MessageFailureTracker.settle}.
 */
@SuppressWarnings({ "unchecked", "rawtypes" })
class PulsarCallbackAcknowledgementTest {

    private static final int LIMIT = 3;

    private PulsarCallback callback;
    private Consumer<byte[]> consumer;
    private Message<byte[]> message;
    private GenericMessageCallback dispatcher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        ServiceConfiguration configuration = mock(ServiceConfiguration.class);
        when(configuration.getLogPayload()).thenReturn(false);
        when(configuration.getMaxProcessingAttempts()).thenReturn(LIMIT);
        ExecutorService sameThread = mock(ExecutorService.class);
        when(sameThread.submit(any(Callable.class))).thenAnswer(inv -> {
            ((Callable<?>) inv.getArgument(0)).call();
            return CompletableFuture.completedFuture(null);
        });
        ServiceRegistry registry = mock(ServiceRegistry.class);
        when(registry.getServiceConfiguration("t1")).thenReturn(configuration);
        when(registry.getVirtualThreadPool()).thenReturn(sameThread);
        dispatcher = mock(GenericMessageCallback.class);
        consumer = mock(Consumer.class);
        message = mock(Message.class);
        when(message.getTopicName()).thenReturn("persistent://public/default/x");
        when(message.getData()).thenReturn("{}".getBytes(StandardCharsets.UTF_8));
        when(message.getMessageId()).thenReturn(MessageId.earliest); // the same on every redelivery
        callback = new PulsarCallback("t1", registry, dispatcher, "id", "name");
    }

    private ProcessingResultWrapper<Object> succeeded() {
        return ProcessingResultHelper.success(List.<ProcessingContext<Object>>of());
    }

    private ProcessingResultWrapper<Object> failed() {
        CompletableFuture<List<ProcessingContext<Object>>> future = new CompletableFuture<>();
        future.completeExceptionally(new IllegalStateException("boom"));
        return ProcessingResultHelper.successAsync(future, Qos.AT_LEAST_ONCE, 1000);
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void aSuccessfulPipelineAcknowledgesTheMessage() throws Exception {
        when(dispatcher.onMessage(any())).thenReturn((ProcessingResultWrapper) succeeded());

        callback.received(consumer, message);

        verify(consumer).acknowledge(message);
        verify(consumer, never()).negativeAcknowledge(any(Message.class));
    }

    @Test
    void aFailedPipelineNegativelyAcknowledgesTheMessage() throws Exception {
        when(dispatcher.onMessage(any())).thenReturn((ProcessingResultWrapper) failed());

        callback.received(consumer, message);

        verify(consumer).negativeAcknowledge(message);
        verify(consumer, never()).acknowledge(any(Message.class));
    }

    @Test
    void aMessageIsDroppedAfterTheConfiguredAttempts() throws Exception {
        when(dispatcher.onMessage(any())).thenAnswer(inv -> failed());

        for (int attempt = 1; attempt <= LIMIT; attempt++) {
            callback.received(consumer, message);
        }

        verify(consumer, times(LIMIT - 1)).negativeAcknowledge(message);
        verify(consumer, times(1)).acknowledge(message); // the failure that reaches the limit: discarded
    }

    @Test
    void aSuccessForgetsEarlierFailures() throws Exception {
        when(dispatcher.onMessage(any())).thenAnswer(inv -> failed());
        callback.received(consumer, message);
        callback.received(consumer, message);
        when(dispatcher.onMessage(any())).thenAnswer(inv -> succeeded());
        callback.received(consumer, message);
        when(dispatcher.onMessage(any())).thenAnswer(inv -> failed());
        callback.received(consumer, message);
        callback.received(consumer, message);

        // only the success acknowledged: the count started over, so the later two failures are below the limit
        verify(consumer, times(1)).acknowledge(message);
        verify(consumer, times(4)).negativeAcknowledge(message);
    }

    @Test
    void aMessageTheDispatcherSkippedIsAcknowledged() throws Exception {
        // no processing future: nothing was dispatched, so a redelivery would never help
        when(dispatcher.onMessage(any())).thenReturn((ProcessingResultWrapper) ProcessingResultWrapper.builder().build());

        callback.received(consumer, message);

        verify(consumer).acknowledge(message);
    }

    @Test
    void anUnexpectedExceptionIsCountedAndNegativelyAcknowledged() throws Exception {
        // a cancelled future throws CancellationException from Future.get: the pool swallowed anything unexpected
        when(dispatcher.onMessage(any())).thenAnswer(inv -> {
            CompletableFuture<List<ProcessingContext<Object>>> cancelled = new CompletableFuture<>();
            cancelled.cancel(true);
            return ProcessingResultWrapper.<Object>builder()
                    .processingResult(cancelled)
                    .consolidatedQos(Qos.AT_LEAST_ONCE)
                    .pipelineTimeoutMS(0)
                    .build();
        });

        callback.received(consumer, message);

        verify(consumer).negativeAcknowledge(message);
    }

    @Test
    void aFailedAcknowledgeIsLoggedAndDoesNotTurnIntoANegativeAcknowledge() throws Exception {
        when(dispatcher.onMessage(any())).thenReturn((ProcessingResultWrapper) succeeded());
        doThrow(new PulsarClientException("broker gone")).when(consumer).acknowledge(message);

        callback.received(consumer, message);

        verify(consumer, never()).negativeAcknowledge(any(Message.class));
    }
}
