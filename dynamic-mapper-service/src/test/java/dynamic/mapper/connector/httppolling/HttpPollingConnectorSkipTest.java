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

package dynamic.mapper.connector.httppolling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.cumulocity.sdk.client.SDKException;
import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.httppolling.HttpPollingConnector.PageOutcome;
import dynamic.mapper.mapping.MappingService;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.Qos;
import dynamic.mapper.model.status.MappingStatus;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.runtime.ProcessingContext;
import dynamic.mapper.processor.runtime.ProcessingResultWrapper;
import dynamic.mapper.processor.util.ProcessingResultHelper;

/**
 * A polled page whose processing keeps failing is retried until {@code maxProcessingAttempts}, then skipped
 * with the skip recorded as the mapping's last error, so one bad page cannot block the topic forever.
 */
class HttpPollingConnectorSkipTest {

    private static final String TOPIC = "devices/measurements";

    private HttpPollingConnector client;
    private MappingStatus status;
    private Mapping mapping;

    @BeforeEach
    void setUp() {
        MappingService mappingService = mock(MappingService.class);
        status = mock(MappingStatus.class);
        mapping = Mapping.builder().identifier("m1").mappingTopic(TOPIC).build();
        when(mappingService.getMappingStatus("t1", mapping)).thenReturn(status);
        ServiceConfiguration configuration = mock(ServiceConfiguration.class);
        when(configuration.getMaxProcessingAttempts()).thenReturn(3);

        client = new HttpPollingConnector();
        ReflectionTestUtils.setField(client, "tenant", "t1");
        ReflectionTestUtils.setField(client, "connectorIdentifier", "c1");
        ReflectionTestUtils.setField(client, "mappingService", mappingService);
        ReflectionTestUtils.setField(client, "serviceConfiguration", configuration);
    }

    @Test
    void aProcessedPageIsSettledWithoutAnError() {
        assertTrue(client.settlePage(mapping, TOPIC, "{}", PageOutcome.PROCESSED));

        verify(status, never()).recordError(anyLong(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void aFailedPageIsRetriedUntilTheLimitThenSkippedWithARecordedError() {
        when(status.getCursor()).thenReturn("c-100");

        assertFalse(client.settlePage(mapping, TOPIC, "{\"a\":1}", PageOutcome.FAILED));
        assertFalse(client.settlePage(mapping, TOPIC, "{\"a\":1}", PageOutcome.FAILED));
        verify(status, never()).recordError(anyLong(), org.mockito.ArgumentMatchers.any());

        assertTrue(client.settlePage(mapping, TOPIC, "{\"a\":1}", PageOutcome.FAILED), "third failure: skip");
        verify(status).recordError(anyLong(), contains("Skipped polled data of topic [" + TOPIC + "] after 3"));
    }

    @Test
    void withACursorTheSamePositionCountsEvenWhenNewDataChangesTheBody() {
        when(status.getCursor()).thenReturn("c-100");

        assertFalse(client.settlePage(mapping, TOPIC, "{\"items\":[1]}", PageOutcome.FAILED));
        assertFalse(client.settlePage(mapping, TOPIC, "{\"items\":[1,2]}", PageOutcome.FAILED)); // new data arrived
        assertTrue(client.settlePage(mapping, TOPIC, "{\"items\":[1,2,3]}", PageOutcome.FAILED));
    }

    @Test
    void withoutACursorTheBodyIdentifiesThePage() {
        when(status.getCursor()).thenReturn(null);

        assertFalse(client.settlePage(mapping, TOPIC, "{\"a\":1}", PageOutcome.FAILED));
        assertFalse(client.settlePage(mapping, TOPIC, "{\"a\":1}", PageOutcome.FAILED));
        assertFalse(client.settlePage(mapping, TOPIC, "{\"b\":2}", PageOutcome.FAILED), "a different page has its own count");
        assertTrue(client.settlePage(mapping, TOPIC, "{\"a\":1}", PageOutcome.FAILED));
    }

    @Test
    void aProcessedPageForgetsEarlierFailures() {
        when(status.getCursor()).thenReturn("c-100");
        client.settlePage(mapping, TOPIC, "{}", PageOutcome.FAILED);
        client.settlePage(mapping, TOPIC, "{}", PageOutcome.FAILED);
        assertTrue(client.settlePage(mapping, TOPIC, "{}", PageOutcome.PROCESSED));

        assertFalse(client.settlePage(mapping, TOPIC, "{}", PageOutcome.FAILED));
        assertFalse(client.settlePage(mapping, TOPIC, "{}", PageOutcome.FAILED));
    }

    @Test
    void aSkipWithoutAMappingIsStillSettledAndLogged() {
        for (int i = 0; i < 2; i++) {
            assertFalse(client.settlePage(null, TOPIC, "{}", PageOutcome.FAILED));
        }

        assertTrue(client.settlePage(null, TOPIC, "{}", PageOutcome.FAILED));
        verify(status, never()).recordError(anyLong(), eq(null));
    }

    @Test
    void aServerErrorIsRetriedButNeverCountedSoAnOutageDoesNotSkipData() {
        when(status.getCursor()).thenReturn("c-100");

        for (int i = 0; i < 20; i++) {
            assertFalse(client.settlePage(mapping, TOPIC, "{}", PageOutcome.SERVER_ERROR),
                    "a server error holds the cursor");
        }

        verify(status, never()).recordError(anyLong(), org.mockito.ArgumentMatchers.any());
        assertFalse(client.settlePage(mapping, TOPIC, "{}", PageOutcome.FAILED), "the page still has its full budget");
        assertFalse(client.settlePage(mapping, TOPIC, "{}", PageOutcome.FAILED));
        assertTrue(client.settlePage(mapping, TOPIC, "{}", PageOutcome.FAILED));
    }

    @Test
    void aServerErrorDoesNotResetTheCountOfAPageThatAlsoFailedForItsOwnReasons() {
        when(status.getCursor()).thenReturn("c-100");
        client.settlePage(mapping, TOPIC, "{}", PageOutcome.FAILED);
        client.settlePage(mapping, TOPIC, "{}", PageOutcome.FAILED);

        assertFalse(client.settlePage(mapping, TOPIC, "{}", PageOutcome.SERVER_ERROR));

        assertTrue(client.settlePage(mapping, TOPIC, "{}", PageOutcome.FAILED), "the third own failure still skips");
    }

    // ---- how a processing result is classified ----

    private ProcessingResultWrapper<Object> withContextError(Exception error) {
        @SuppressWarnings("unchecked")
        ProcessingContext<Object> context = mock(ProcessingContext.class);
        when(context.hasError()).thenReturn(true);
        when(context.getErrors()).thenReturn(List.of(error));
        return ProcessingResultHelper.success(List.of(context));
    }

    @Test
    void aServerErrorFromCumulocityIsClassifiedAsAServerError() {
        ProcessingResultWrapper<Object> result = withContextError(
                new ProcessingException("Cumulocity down", new SDKException(503, "unavailable")));

        assertEquals(PageOutcome.SERVER_ERROR, client.awaitProcessingOutcome(result, TOPIC));
    }

    @Test
    void aClientErrorAndAMappingErrorWithoutAStatusAreClassifiedAsFailed() {
        assertEquals(PageOutcome.FAILED, client.awaitProcessingOutcome(
                withContextError(new ProcessingException("rejected", new SDKException(422, "invalid"))), TOPIC));
        assertEquals(PageOutcome.FAILED, client.awaitProcessingOutcome(
                withContextError(new ProcessingException("mapping bug")), TOPIC));
    }

    @Test
    void aCleanResultIsProcessedAndAMissingOrCancelledOneFailed() {
        assertEquals(PageOutcome.PROCESSED, client.awaitProcessingOutcome(
                ProcessingResultHelper.success(List.<ProcessingContext<Object>>of()), TOPIC));
        assertEquals(PageOutcome.FAILED, client.awaitProcessingOutcome(ProcessingResultHelper.failure(), TOPIC));

        ProcessingResultWrapper<Object> cancelled = ProcessingResultHelper.success(List.<ProcessingContext<Object>>of());
        cancelled.getCancellationRequested().set(true);
        assertEquals(PageOutcome.FAILED, client.awaitProcessingOutcome(cancelled, TOPIC));
    }

    @Test
    void aTimeoutIsClassifiedAsFailedAndTheWorkerIsCancelled() {
        ProcessingResultWrapper<Object> result = ProcessingResultHelper.successAsync(
                new CompletableFuture<List<ProcessingContext<Object>>>(), Qos.AT_LEAST_ONCE, 50);

        assertEquals(PageOutcome.FAILED, client.awaitProcessingOutcome(result, TOPIC));
        assertTrue(result.getCancellationRequested().get());
    }
}
