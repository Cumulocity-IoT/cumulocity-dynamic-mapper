/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package dynamic.mapper.processor.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cumulocity.sdk.client.SDKException;

import dynamic.mapper.mapping.MappingService;
import dynamic.mapper.model.DynamicMapperRequest;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.status.MappingStatus;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.runtime.ProcessingContext;

/** How the result of one leg (requests, errors, warnings) is collected and judged at the end of the pipeline. */
class ResultCollectionTest {

    private static final Logger LOG = LoggerFactory.getLogger(ResultCollectionTest.class);

    private MappingService mappingService;
    private MappingStatus status;
    private ConsolidationProcessor processor;
    private Mapping mapping;

    @BeforeEach
    void setUp() {
        mappingService = mock(MappingService.class);
        status = mock(MappingStatus.class);
        when(mappingService.getMappingStatus(any(), any(Mapping.class))).thenReturn(status);
        processor = new ConsolidationProcessor(mappingService);
        mapping = Mapping.builder().identifier("m").build();
    }

    private ProcessingContext<Object> context() {
        return ProcessingContext.<Object>builder().tenant("t1").mapping(mapping).build();
    }

    private static DynamicMapperRequest failedRequest() {
        DynamicMapperRequest request = DynamicMapperRequest.builder().build();
        request.setError(new RuntimeException("publish failed"));
        return request;
    }

    @Test
    void addRequest_returnsTheOwnIndexUnderConcurrentAdds() throws Exception {
        ProcessingContext<Object> context = context();
        int threads = 16;
        int perThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<Integer>>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                start.await();
                List<Integer> indexes = new ArrayList<>();
                for (int i = 0; i < perThread; i++) {
                    DynamicMapperRequest request = DynamicMapperRequest.builder().build();
                    int index = context.addRequest(request);
                    // The index must point at THIS request, not at one another thread added meanwhile.
                    if (context.getRequests().get(index) != request) {
                        throw new AssertionError("index " + index + " does not address the added request");
                    }
                    indexes.add(index);
                }
                return indexes;
            }));
        }
        start.countDown();
        Set<Integer> all = new HashSet<>();
        for (Future<List<Integer>> f : futures) {
            all.addAll(f.get());
        }
        pool.shutdown();
        assertEquals(threads * perThread, all.size(), "every add must get a distinct index");
    }

    @Test
    void replaceRequests_keepsTheListConcurrent() {
        ProcessingContext<Object> context = context();
        context.replaceRequests(new ArrayList<>(List.of(DynamicMapperRequest.builder().build())));
        assertTrue(context.getRequests() instanceof java.util.concurrent.CopyOnWriteArrayList);
    }

    @Test
    void addError_wrapsForeignExceptionsAndKeepsProcessingExceptions() {
        ProcessingContext<Object> context = context();
        ProcessingException own = new ProcessingException("own");
        context.addError(own, "ignored");
        SDKException sdk = new SDKException(503, "down");
        context.addError(sdk, "wrapped");

        assertEquals(2, context.getErrors().size());
        assertTrue(context.getErrors().get(0) == own);
        assertEquals(503, ProcessingResultHelper.httpStatusOf(context.getErrors().get(1)));
    }

    @Test
    void requestOnlyFailure_isBookedAsError_notAsProcessed() throws Exception {
        ProcessingContext<Object> context = context();
        context.addRequest(failedRequest());

        processor.process(context);

        verify(status).recordError(anyLong(), anyString());
        verify(status, never()).recordProcessed(anyLong());
        verify(mappingService, never()).resetFailureCountOnSuccess(any(), any());
    }

    @Test
    void cancelledLegWithoutError_isNotBookedAsProcessed() throws Exception {
        ProcessingContext<Object> context = context();
        var wrapper = dynamic.mapper.processor.runtime.ProcessingResultWrapper.<Object>builder().build();
        wrapper.getCancellationRequested().set(true);
        context.setProcessingResultWrapper(wrapper);

        processor.process(context);

        verify(status).recordError(anyLong(), anyString());
        verify(status, never()).recordProcessed(anyLong());
    }

    @Test
    void cleanLeg_isBookedAsProcessed() throws Exception {
        processor.process(context());

        verify(status).recordProcessed(anyLong());
        verify(mappingService).resetFailureCountOnSuccess("t1", mapping);
    }

    @Test
    void aLegReachingTheEndTwice_isBookedOnce() throws Exception {
        ProcessingContext<Object> context = context();

        processor.process(context);
        context.addError(new ProcessingException("late failure"));
        processor.process(context);

        verify(status, times(1)).recordWork(anyLong(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
        verify(status, times(1)).recordProcessed(anyLong());
        verify(status, never()).recordError(anyLong(), anyString());
    }

    @Test
    void extractMaxHttpStatus_readsSdkExceptionsFromTheCauseChainAndSkipsNullLegs() {
        ProcessingContext<Object> context = context();
        context.getErrors().add(new RuntimeException("outer", new SDKException(502, "bad gateway")));
        List<ProcessingContext<?>> results = new ArrayList<>();
        results.add(null);
        results.add(context);

        assertEquals(502, ProcessingResultHelper.extractMaxHttpStatus(results, "t1", "topic", LOG));
        assertFalse(ProcessingResultHelper.extractMaxHttpStatus(results, "t1", "topic", LOG) < 500);
    }
}
