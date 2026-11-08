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

package dynamic.mapper.connector.amqp;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.cumulocity.sdk.client.SDKException;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Envelope;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.callback.GenericMessageCallback;
import dynamic.mapper.core.ServiceRegistry;
import dynamic.mapper.model.Qos;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.runtime.ProcessingContext;
import dynamic.mapper.processor.runtime.ProcessingResultWrapper;

/**
 * Cumulocity is unavailable while AMQP 0-9-1 messages arrive (Path E of
 * {@code resources/testing/reliability/TEST-SETUP-C8Y-UNAVAILABLE.md}): the pipeline fails with a 502 from the
 * platform. A requeued message comes back with a new delivery tag and the same message id.
 */
class AMQPCallbackC8yOutageTest {

    private static final int LIMIT = 3;

    private final AtomicBoolean platformDown = new AtomicBoolean();
    private Channel channel;
    private AMQPCallback callback;
    private final AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder().messageId("m-1").build();

    @BeforeEach
    void setUp() {
        ServiceConfiguration configuration = mock(ServiceConfiguration.class);
        when(configuration.getLogPayload()).thenReturn(false);
        when(configuration.getMaxProcessingAttempts()).thenReturn(LIMIT);
        ServiceRegistry serviceRegistry = mock(ServiceRegistry.class);
        when(serviceRegistry.getServiceConfiguration(any())).thenReturn(configuration);
        GenericMessageCallback dispatcher = mock(GenericMessageCallback.class);
        when(dispatcher.onMessage(any())).thenAnswer(inv -> result(platformDown.get()));
        channel = mock(Channel.class);
        callback = new AMQPCallback(channel, false, "t1", serviceRegistry, dispatcher, "id", "name");
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private ProcessingResultWrapper<Object> result(boolean error) {
        ProcessingContext<Object> context = mock(ProcessingContext.class);
        when(context.hasError()).thenReturn(error);
        when(context.getErrors()).thenReturn(error
                ? List.of(new ProcessingException("Cumulocity down", new SDKException(502, "Bad Gateway")))
                : List.of());
        return ProcessingResultWrapper.<Object>builder()
                .processingResult((java.util.concurrent.Future) CompletableFuture.completedFuture(
                        Collections.singletonList(context)))
                .consolidatedQos(Qos.AT_LEAST_ONCE)
                .pipelineTimeoutMS(0)
                .build();
    }

    private void deliver(long tag) throws Exception {
        callback.handleDelivery("tag", new Envelope(tag, tag > 1, "exchange", "a.b"), properties, "x".getBytes());
    }

    @Test
    void aMessageIsRequeuedDuringTheOutageAndAcknowledgedAfterRecovery() throws Exception {
        platformDown.set(true);
        deliver(1);
        deliver(2);
        platformDown.set(false);   // fault.off
        deliver(3);

        verify(channel).basicNack(1L, false, true);
        verify(channel).basicNack(2L, false, true);
        verify(channel).basicAck(3L, false);
    }

    @Test
    void aMessageIsDroppedWhenTheOutageOutlastsTheLimit() throws Exception {
        // characterizes the cost of the poison-pill guard: a 502 counts like any other failure
        platformDown.set(true);
        deliver(1);
        deliver(2);
        deliver(3);

        verify(channel).basicNack(1L, false, true);
        verify(channel).basicNack(2L, false, true);
        verify(channel).basicAck(3L, false);                       // dropped
        verify(channel, never()).basicNack(3L, false, true);
    }
}
