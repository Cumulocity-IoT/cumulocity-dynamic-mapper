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

package dynamic.mapper.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.cumulocity.microservice.api.CumulocityClientProperties;
import com.cumulocity.microservice.context.ContextService;
import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.alarm.AlarmRepresentation;
import com.cumulocity.rest.representation.event.EventRepresentation;
import com.cumulocity.rest.representation.measurement.MeasurementCollectionRepresentation;
import com.cumulocity.rest.representation.operation.OperationRepresentation;
import com.cumulocity.sdk.client.ProcessingMode;
import com.cumulocity.sdk.client.SDKException;
import com.cumulocity.sdk.client.alarm.AlarmApi;
import com.cumulocity.sdk.client.devicecontrol.DeviceControlApi;
import com.cumulocity.sdk.client.event.EventApi;
import com.cumulocity.sdk.client.measurement.MeasurementApi;
import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.runtime.ProcessingContext;
import dynamic.mapper.processor.runtime.ProcessingResultWrapper;

/**
 * What {@link ApiRequestSender} does around the HTTP call: the permit of the request semaphore, the check for a
 * cancelled message, and the transient mode. The payload mapping per API is covered where it is used.
 */
class ApiRequestSenderTest {

    private static final String EVENT_JSON = "{\"type\":\"c8y_Test\",\"text\":\"hello\"}";

    private final EventApi eventApi = mock(EventApi.class);
    private final AlarmApi alarmApi = mock(AlarmApi.class);
    private final MeasurementApi measurementApi = mock(MeasurementApi.class);
    private final DeviceControlApi deviceControlApi = mock(DeviceControlApi.class);
    private final ProcessingModeService processingModeService = mock(ProcessingModeService.class);
    private final ProcessingContext<?> context = mock(ProcessingContext.class);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private Semaphore semaphore;
    private ApiRequestSender sender;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        semaphore = new Semaphore(2);
        sender = new ApiRequestSender(semaphore, eventApi, alarmApi, measurementApi, deviceControlApi,
                processingModeService, new ObjectMapper(), mock(ContextService.class),
                mock(CumulocityClientProperties.class));
        ProcessingResultWrapper<?> wrapper = mock(ProcessingResultWrapper.class);
        when(wrapper.getCancellationRequested()).thenReturn(cancelled);
        when(context.getTenant()).thenReturn("t1");
        when(context.getProcessingResultWrapper()).thenAnswer(inv -> wrapper);
    }

    private EventRepresentation createdEvent() {
        EventRepresentation created = new EventRepresentation();
        created.setId(new GId("42"));
        return created;
    }

    @Test
    void anEventIsPostedAndTheCallbackSeesTheCreatedEvent_thePermitIsReleased() throws Exception {
        EventRepresentation created = createdEvent();
        when(eventApi.create(any(EventRepresentation.class))).thenReturn(created);
        AtomicReference<EventRepresentation> seen = new AtomicReference<>();

        EventRepresentation result = sender.sendEvent(context, EVENT_JSON, false, seen::set);

        assertSame(created, result);
        assertSame(created, seen.get());
        assertEquals(2, semaphore.availablePermits());
    }

    @Test
    void aCancelledMessageSendsNothing_andKeepsNoPermit() throws Exception {
        cancelled.set(true);

        ProcessingException e = assertThrows(ProcessingException.class,
                () -> sender.sendEvent(context, EVENT_JSON, false, ev -> { }));

        assertTrue(e.getMessage().contains("cancelled"), e.getMessage());
        verify(eventApi, never()).create(any(EventRepresentation.class));
        assertEquals(2, semaphore.availablePermits());
    }

    @Test
    void aPlatformErrorReachesTheCaller_andTheCallbackIsNotRun() throws Exception {
        when(eventApi.create(any(EventRepresentation.class))).thenThrow(new SDKException(422, "rejected"));
        AtomicBoolean called = new AtomicBoolean();

        assertThrows(SDKException.class, () -> sender.sendEvent(context, EVENT_JSON, false, ev -> called.set(true)));

        assertTrue(!called.get());
        assertEquals(2, semaphore.availablePermits());
    }

    @Test
    void aMessageInterruptedWhileWaitingForAPermitFailsWithoutReleasingOneItNeverHad() {
        Semaphore none = new Semaphore(0);
        ApiRequestSender starved = new ApiRequestSender(none, eventApi, alarmApi, measurementApi, deviceControlApi,
                processingModeService, new ObjectMapper(), null, null);
        Thread.currentThread().interrupt();
        try {
            assertThrows(ProcessingException.class,
                    () -> starved.sendEvent(context, EVENT_JSON, false, ev -> { }));
        } finally {
            Thread.interrupted(); // the sender restores the flag; do not leak it into other tests
        }
        assertEquals(0, none.availablePermits(), "a permit that was never acquired must not be released");
    }

    @Test
    void anOperationLosesItsStatusBeforeItIsSent() throws Exception {
        when(deviceControlApi.create(any(OperationRepresentation.class))).thenAnswer(inv -> {
            OperationRepresentation op = inv.getArgument(0);
            op.setId(new GId("7"));
            return op;
        });

        sender.sendOperation(context, "{\"deviceId\":\"1\",\"status\":\"PENDING\"}", false);

        ArgumentCaptor<OperationRepresentation> sent = ArgumentCaptor.forClass(OperationRepresentation.class);
        verify(deviceControlApi).create(sent.capture());
        assertNull(sent.getValue().getStatus(), "the platform rejects 'status' on create");
    }

    @Test
    void aSingleMeasurementIsSentAsAOneElementCollection() throws Exception {
        when(measurementApi.createBulk(any(MeasurementCollectionRepresentation.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        sender.sendMeasurements(context, "{\"type\":\"c8y_Temp\"}",
                false);

        ArgumentCaptor<MeasurementCollectionRepresentation> sent =
                ArgumentCaptor.forClass(MeasurementCollectionRepresentation.class);
        verify(measurementApi).createBulk(sent.capture());
        assertEquals(1, sent.getValue().getMeasurements().size());
    }

    @Test
    void anAlarmIsPostedThroughThePersistentApiUnlessTheContextIsTransient() throws Exception {
        AlarmRepresentation created = new AlarmRepresentation();
        created.setId(new GId("9"));
        when(alarmApi.create(any(AlarmRepresentation.class))).thenReturn(created);

        sender.sendAlarm(context, "{\"type\":\"c8y_A\",\"text\":\"x\",\"severity\":\"MAJOR\"}", false);

        verify(alarmApi).create(any(AlarmRepresentation.class));
        verify(processingModeService, never()).callWithProcessingMode(any(), any());
    }

    @Test
    void aTransientEventGoesThroughTheTransientConnector() throws Exception {
        when(context.getProcessingMode()).thenReturn(ProcessingMode.TRANSIENT);
        EventRepresentation created = createdEvent();
        when(processingModeService.callWithProcessingMode(eq("TRANSIENT"), any())).thenReturn(created);

        EventRepresentation result = sender.sendEvent(context, EVENT_JSON, false, ev -> { });

        assertSame(created, result);
        verify(eventApi, never()).create(any(EventRepresentation.class));
    }

    @Test
    void aCancelledCustomRouteIsNotCalled() {
        cancelled.set(true);

        assertThrows(ProcessingException.class, () -> sender.sendCustom(context,
                mock(dynamic.mapper.model.DynamicMapperRequest.class)));
    }
}
