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

package dynamic.mapper.core;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dynamic.mapper.configuration.CodeTemplate;
import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.configuration.TemplateType;
import dynamic.mapper.model.API;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingType;
import dynamic.mapper.processor.AbstractFlowProcessor;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.runtime.PooledGraalContext;
import dynamic.mapper.processor.runtime.ProcessingContext;
import dynamic.mapper.processor.runtime.ProcessingResultWrapper;
import dynamic.mapper.processor.runtime.SmartFunctionContext;
import dynamic.mapper.mapping.MappingService;
import lombok.extern.slf4j.Slf4j;
import org.graalvm.polyglot.Context;

/**
 * Exercises the race described in
 * {@code resources/testing/reliability/TEST-INSTRUCTIONS-GRAALVM-CANCEL-RACE.md}: a per-mapping
 * worker thread that is genuinely interrupted (via
 * {@link ProcessingResultWrapper#cancelProcessing()}) while it is in the middle of running
 * uncooperative JavaScript inside a real, pool-borrowed GraalVM {@link Context}.
 *
 * <p>This is deliberately an integration-style test: it uses a real {@link GraalVMContextService}
 * and drives {@link AbstractFlowProcessor#process(ProcessingContext)} directly against a minimal
 * concrete subclass, following the pattern used by {@code AbstractFlowProcessorTest} (mocked
 * collaborators, real GraalVM engine/context) and {@code GraalVMContextServiceTest} (real
 * {@link GraalVMContextService}, reflection to read {@code engineActiveContexts}).
 */
@Slf4j
class GraalVMContextCancellationRaceTest {

    private static final String TENANT = "cancel-race-tenant";

    private GraalVMContextService graalVMContextService;
    private ServiceConfiguration serviceConfiguration;
    private ExecutorService workerExecutor;

    @BeforeEach
    void setUp() {
        graalVMContextService = new GraalVMContextService();
        serviceConfiguration = buildServiceConfig();
        graalVMContextService.createGraalsResources(TENANT, serviceConfiguration);
        workerExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "cancel-race-worker");
            t.setDaemon(true);
            return t;
        });
    }

    @AfterEach
    void tearDown() {
        workerExecutor.shutdownNow();
        try {
            graalVMContextService.removeGraalsResources(TENANT);
        } catch (Exception ignored) {
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private <T> T field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(target);
    }

    /** Returns the active-context counter for the given engine (via reflection). */
    private AtomicInteger activeCountFor(Engine engine) throws Exception {
        Map<Engine, AtomicInteger> map = field(graalVMContextService, "engineActiveContexts");
        return map.get(engine);
    }

    private ServiceConfiguration buildServiceConfig() {
        ServiceConfiguration config = new ServiceConfiguration();
        Map<String, CodeTemplate> templates = new HashMap<>();

        CodeTemplate shared = new CodeTemplate();
        shared.setCode(Base64.getEncoder().encodeToString("// shared".getBytes()));
        templates.put(TemplateType.SHARED.name(), shared);

        CodeTemplate system = new CodeTemplate();
        system.setCode(Base64.getEncoder().encodeToString("// system".getBytes()));
        templates.put(TemplateType.SYSTEM.name(), system);

        config.setCodeTemplates(templates);
        // Disable time-based rotation — irrelevant here and must not fire mid-test.
        config.setEngineMaxAgeMinutes(0);
        return config;
    }

    private Mapping buildBusyLoopMapping(String identifier) {
        String jsCode = """
                function onMessage(message, flowContext) {
                    __testSync.workerStarted();
                    while (true) { for (var i = 0; i < 1000000; i++) {} }
                }
                """;
        String encodedCode = Base64.getEncoder().encodeToString(jsCode.getBytes());

        return Mapping.builder()
                .id("test_" + identifier)
                .identifier(identifier)
                .name("Cancel race mapping " + identifier)
                .mappingTopic("test/topic")
                .targetAPI(API.MEASUREMENT)
                .direction(Direction.INBOUND)
                .mappingType(MappingType.JSON)
                .code(encodedCode)
                .active(true)
                .debug(false)
                .build();
    }

    /** Completes almost immediately — the "let it finish normally" half of the concurrency test. */
    private Mapping buildQuickMapping(String identifier) {
        String jsCode = """
                function onMessage(message, flowContext) {
                    __testSync.workerStarted();
                    var s = 0;
                    for (var i = 0; i < 1000; i++) { s += i; }
                    return { sum: s };
                }
                """;
        String encodedCode = Base64.getEncoder().encodeToString(jsCode.getBytes());

        return Mapping.builder()
                .id("test_" + identifier)
                .identifier(identifier)
                .name("Cancel race quick mapping " + identifier)
                .mappingTopic("test/topic")
                .targetAPI(API.MEASUREMENT)
                .direction(Direction.INBOUND)
                .mappingType(MappingType.JSON)
                .code(encodedCode)
                .active(true)
                .debug(false)
                .build();
    }

    /**
     * JS-callable bridge (must be a {@code public} class with a {@code public} method for
     * GraalVM's default {@code HostAccess} to expose it — an anonymous/package-private class
     * fails reflectively with "Unknown identifier"). Signals the instant the busy-loop JS starts
     * running, so the test thread can synchronize on "worker has borrowed the context and
     * started JS" rather than guessing with {@code Thread.sleep(...)}.
     */
    public static class TestSyncBridge {
        private final CountDownLatch startedLatch;

        public TestSyncBridge(CountDownLatch startedLatch) {
            this.startedLatch = startedLatch;
        }

        public void workerStarted() {
            startedLatch.countDown();
        }
    }

    /** Minimal concrete AbstractFlowProcessor — mirrors FlowInboundProcessor's error handling
     *  (context.addError) so the cancellation exception is actually observable on the context,
     *  unlike AbstractFlowProcessorTest's Testable subclass which only sets flags. */
    static class TestFlowProcessor extends AbstractFlowProcessor {

        TestFlowProcessor(MappingService mappingService, GraalVMContextService graalVMContextService) {
            super(mappingService, graalVMContextService);
        }

        @Override
        protected String getProcessorName() {
            return "CancelRaceTestFlowProcessor";
        }

        @Override
        protected Value createInputMessage(Context graalContext, ProcessingContext<?> context) {
            Map<String, Object> messageData = new HashMap<>();
            messageData.put("payload", context.getPayload());
            messageData.put("topic", context.getTopic());
            return graalContext.asValue(messageData);
        }

        @Override
        protected void processResult(Value result, ProcessingContext<?> context, String tenant)
                throws ProcessingException {
            // Never reached in the cancellation path — the busy loop never returns normally.
        }

        @Override
        protected void handleProcessingError(Exception e, String errorMessage,
                ProcessingContext<?> context, String tenant, Mapping mapping) {
            if (e instanceof ProcessingException) {
                context.addError((ProcessingException) e);
            } else {
                context.addError(new ProcessingException(errorMessage, e));
            }
        }
    }

    private ProcessingContext<Object> buildProcessingContext(Mapping mapping, PooledGraalContext pooledCtx) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("deviceId", "test-device");

        ProcessingContext<Object> context = ProcessingContext.builder()
                .tenant(TENANT)
                .mapping(mapping)
                .payload(payload)
                .serviceConfiguration(serviceConfiguration)
                .topic("test/topic")
                .build();

        context.setPooledGraalContext(pooledCtx);
        context.setGraalContext(pooledCtx.getGraalContext());
        context.setFlowContext(new SmartFunctionContext(
                pooledCtx.getGraalContext(), TENANT, mock(InventoryEnrichmentClient.class), false));
        return context;
    }

    // -------------------------------------------------------------------------
    // Step 1-2 (single-worker case)
    // -------------------------------------------------------------------------

    @Test
    void workerHoldingPooledContextIsKilledOnCancelProcessing() throws Exception {
        Mapping mapping = buildBusyLoopMapping("busy_loop_single");
        Engine engine = graalVMContextService.peekGraalEngine(TENANT);
        String poolKey = TENANT + ":" + mapping.getIdentifier() + ":singleWorker";

        PooledGraalContext pooledCtx = graalVMContextService.borrowOrCreateContext(
                poolKey, TENANT, engine, false,
                graalVMContextService.getGraalsSourceShared(TENANT),
                graalVMContextService.getGraalsSourceSystem(TENANT),
                mapping.getCode(), mapping.getIdentifier());

        assertEquals(1, activeCountFor(engine).get(), "Borrowing should mark one context in-flight");

        ProcessingContext<Object> context = buildProcessingContext(mapping, pooledCtx);
        context.setEngineReleaseAction(
                () -> graalVMContextService.returnContext(poolKey, pooledCtx, engine));

        // Bridge object the busy-loop JS signals the instant it starts running, so the test
        // thread knows the context has genuinely been borrowed and JS execution has begun —
        // no Thread.sleep() timing guesses.
        CountDownLatch workerStarted = new CountDownLatch(1);
        pooledCtx.getGraalContext().getBindings("js").putMember("__testSync", new TestSyncBridge(workerStarted));

        ProcessingResultWrapper<Object> wrapper = ProcessingResultWrapper.<Object>builder()
                .pipelineTimeoutMS(30_000)
                .build();
        context.setProcessingResultWrapper(wrapper);

        TestFlowProcessor processor = new TestFlowProcessor(mock(MappingService.class), graalVMContextService);

        // markWorkerStarted()/markWorkerCompleted() mirror InboundMessageDispatcher's real
        // wiring around a submitted worker — this is what cancelAndDrain() below actually
        // observes. Future.get() is not usable for this: FutureTask.cancel(true) makes get()
        // throw CancellationException the instant cancel() is called, regardless of whether the
        // task's Runnable has actually finished running — it cannot tell a genuinely-terminated
        // worker from one still spinning post-interrupt.
        wrapper.markWorkerStarted();
        Future<?> workerFuture = workerExecutor.submit(() -> {
            try {
                processor.process(context);
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                wrapper.markWorkerCompleted();
            }
        });
        wrapper.registerWorkerFuture(workerFuture);

        assertTrue(workerStarted.await(5, TimeUnit.SECONDS),
                "Busy-loop JS should have signalled that it started executing");

        // Assertion (finding #3): the worker thread must actually terminate, not hang forever
        // with a killed-but-never-returning JS execution — cancelAndDrain() is the real
        // production call every broker callback uses on a timeout (see AbstractMqttCallback,
        // KafkaClientV2, etc.) precisely because Future.isDone()/get() cannot observe this.
        boolean drained = wrapper.cancelAndDrain(10_000);
        assertTrue(drained, "Worker must actually leave the pipeline after cancellation, not hang");

        // Assertion (finding #4): the interrupt/kill must be normalized into a recorded
        // ProcessingException, not swallowed or left to escape uncaught.
        assertTrue(context.hasError(), "Cancellation should be recorded as an error on the context");
        Exception recorded = context.getErrors().get(context.getErrors().size() - 1);
        Throwable cause = recorded.getCause();
        assertInstanceOf(PolyglotException.class, cause, "Recorded error should wrap the PolyglotException from the killed context");
        assertCancellationFlavor((PolyglotException) cause);

        // Assertion (finding #1): engine accounting must return to baseline, not leak an
        // in-flight count because both the interrupt path and the cancelAction path tried to
        // release the same context.
        assertEquals(0, activeCountFor(engine).get(),
                "engineActiveContexts must return to baseline after the cancelled worker completes");

        // Assertion (finding #2): the killed context must never come back from the pool.
        assertTrue(pooledCtx.isKilled(), "Context should be marked killed by the cancel action");

        PooledGraalContext reborrowed = graalVMContextService.borrowOrCreateContext(
                poolKey, TENANT, engine, false,
                graalVMContextService.getGraalsSourceShared(TENANT),
                graalVMContextService.getGraalsSourceSystem(TENANT),
                mapping.getCode(), mapping.getIdentifier());
        assertNotSame(pooledCtx.getGraalContext(), reborrowed.getGraalContext(),
                "A fresh borrow for the same pool key must not return the killed context");

        graalVMContextService.returnContext(poolKey, reborrowed, engine);
    }

    // -------------------------------------------------------------------------
    // Step 3.4 — concurrent workers sharing the same pool key
    // -------------------------------------------------------------------------

    /**
     * N workers borrow contexts under the *same* pool key simultaneously; a random half are
     * cancelled while the other half run a short, cooperative script to completion. This is the
     * case most likely to surface a real bug (finding #5): the single-worker test above mostly
     * validates wiring, not the {@code CopyOnWriteArrayList}/{@code AtomicInteger}/
     * {@code ConcurrentHashMap} races that only show up under concurrent borrow/return/kill
     * traffic against one pool key.
     */
    @Test
    void concurrentWorkersSharingPoolKeyRaceCorrectly() throws Exception {
        final int workerCount = 12;
        final Engine engine = graalVMContextService.peekGraalEngine(TENANT);
        // Two pool keys, one per code variant: borrowOrCreateContext only compiles the passed-in
        // code on a cache MISS — a cache HIT (deque.pollFirst() != null) returns whatever
        // onMessage function was already loaded into that idle instance, ignoring the code
        // argument entirely. Sharing one literal key across the busy-loop and quick mappings
        // would let a quick worker's finished context be handed to a "cancelled" worker under a
        // different mapping identity, silently running the wrong script — a test-harness
        // artifact, not the production race this test targets. Each subgroup still genuinely
        // contends its own pool key concurrently, which is what Step 3.4 stresses.
        final String busyPoolKey = TENANT + ":concurrent:busy";
        final String quickPoolKey = TENANT + ":concurrent:quick";

        record Worker(
                int index,
                boolean shouldCancel,
                PooledGraalContext pooledCtx,
                ProcessingContext<Object> context,
                ProcessingResultWrapper<Object> wrapper,
                CountDownLatch started,
                Future<?> future) {
        }

        java.util.List<Integer> indices = new java.util.ArrayList<>();
        for (int i = 0; i < workerCount; i++) {
            indices.add(i);
        }
        java.util.Collections.shuffle(indices, new java.util.Random(42));
        java.util.Set<Integer> toCancel = new java.util.HashSet<>(indices.subList(0, workerCount / 2));

        ExecutorService concurrentExecutor = Executors.newFixedThreadPool(workerCount, r -> {
            Thread t = new Thread(r, "cancel-race-concurrent-worker");
            t.setDaemon(true);
            return t;
        });

        java.util.List<Worker> workers = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < workerCount; i++) {
                boolean cancel = toCancel.contains(i);
                Mapping mapping = cancel ? buildBusyLoopMapping("concurrent_" + i)
                        : buildQuickMapping("concurrent_" + i);
                String poolKey = cancel ? busyPoolKey : quickPoolKey;

                PooledGraalContext pooledCtx = graalVMContextService.borrowOrCreateContext(
                        poolKey, TENANT, engine, false,
                        graalVMContextService.getGraalsSourceShared(TENANT),
                        graalVMContextService.getGraalsSourceSystem(TENANT),
                        mapping.getCode(), mapping.getIdentifier());

                ProcessingContext<Object> context = buildProcessingContext(mapping, pooledCtx);
                context.setEngineReleaseAction(
                        () -> graalVMContextService.returnContext(poolKey, pooledCtx, engine));

                CountDownLatch started = new CountDownLatch(1);
                pooledCtx.getGraalContext().getBindings("js").putMember("__testSync", new TestSyncBridge(started));

                ProcessingResultWrapper<Object> wrapper = ProcessingResultWrapper.<Object>builder()
                        .pipelineTimeoutMS(30_000)
                        .build();
                context.setProcessingResultWrapper(wrapper);

                TestFlowProcessor processor = new TestFlowProcessor(mock(MappingService.class), graalVMContextService);

                wrapper.markWorkerStarted();
                Future<?> future = concurrentExecutor.submit(() -> {
                    try {
                        processor.process(context);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        wrapper.markWorkerCompleted();
                    }
                });
                wrapper.registerWorkerFuture(future);

                workers.add(new Worker(i, cancel, pooledCtx, context, wrapper, started, future));
            }

            // Wait for every worker to signal it has actually started running JS before
            // cancelling any of them, so "cancelled" genuinely races a live execution rather
            // than a not-yet-scheduled task.
            for (Worker w : workers) {
                assertTrue(w.started().await(10, TimeUnit.SECONDS),
                        "Worker " + w.index() + " should have signalled it started executing");
            }

            // Cancel the chosen half concurrently (not sequentially) — this is what actually
            // stresses the shared bookkeeping; cancelling one at a time would barely differ from
            // the single-worker test above.
            java.util.List<Future<Boolean>> drainFutures = new java.util.ArrayList<>();
            ExecutorService cancellingExecutor = Executors.newFixedThreadPool(Math.max(1, workerCount / 2));
            try {
                for (Worker w : workers) {
                    if (w.shouldCancel()) {
                        drainFutures.add(cancellingExecutor.submit(() -> w.wrapper().cancelAndDrain(10_000)));
                    }
                }
                for (Future<Boolean> f : drainFutures) {
                    assertTrue(f.get(15, TimeUnit.SECONDS), "Cancelled worker must actually leave the pipeline");
                }
            } finally {
                cancellingExecutor.shutdownNow();
            }

            // Let the non-cancelled workers finish their (very short) script.
            for (Worker w : workers) {
                if (!w.shouldCancel()) {
                    assertDoesNotThrow(() -> w.future().get(10, TimeUnit.SECONDS),
                            "Worker " + w.index() + " should have completed its short script normally");
                }
            }

            for (Worker w : workers) {
                if (w.shouldCancel()) {
                    assertTrue(w.context().hasError(),
                            "Cancelled worker " + w.index() + " should have a recorded cancellation error");
                    assertTrue(w.pooledCtx().isKilled(),
                            "Cancelled worker " + w.index() + "'s context should be marked killed");
                } else {
                    assertFalse(w.context().hasError(),
                            "Non-cancelled worker " + w.index() + " should not have any recorded error");
                    assertFalse(w.pooledCtx().isKilled(),
                            "Non-cancelled worker " + w.index() + "'s context should not be killed");
                }
            }

            // Assertion (finding #1 & #5): despite N concurrent borrows/kills/returns against the
            // *same* pool key, the shared in-flight counter must land back exactly on baseline —
            // no double-decrement (from both the interrupt path and the cancelAction path racing
            // on one context) and no missed decrement (a release silently lost under contention).
            assertEquals(0, activeCountFor(engine).get(),
                    "engineActiveContexts must return to baseline after all concurrent workers complete");
        } finally {
            concurrentExecutor.shutdownNow();
        }
    }

    // -------------------------------------------------------------------------
    // Step 4 — non-pooled ("direct") GraalVM context path
    // -------------------------------------------------------------------------

    /**
     * {@code AbstractFlowProcessor}/{@code ProcessingContext.close()} still support a direct
     * (non-pooled) {@link Context} — the cancel action calls {@code Context.close(true)} instead
     * of {@code PooledGraalContext.kill()}, and {@code ProcessingContext.close()} closes the
     * {@link Context} itself rather than delegating to {@code engineReleaseAction}. Production
     * (via {@code AbstractEnrichmentProcessor}) currently always takes the pooled path, but this
     * branch remains live code that a Java extension or a future caller could exercise, so the
     * cancellation mechanics for it are verified here too, per the test instructions' Step 4.
     */
    @Test
    void workerHoldingDirectNonPooledContextIsKilledOnCancelProcessing() throws Exception {
        Mapping mapping = buildBusyLoopMapping("busy_loop_direct");
        Engine engine = graalVMContextService.peekGraalEngine(TENANT);

        Context directContext = Context.newBuilder("js")
                .engine(engine)
                .option("js.text-encoding", "true")
                .allowHostAccess(graalVMContextService.getHostAccess())
                .allowHostClassLookup(GraalVMContextService::isAllowedHostClass)
                .build();
        directContext.eval(graalVMContextService.getGraalsSourceShared(TENANT));
        directContext.eval(graalVMContextService.getGraalsSourceSystem(TENANT));

        Map<String, Object> payload = new HashMap<>();
        payload.put("deviceId", "test-device");
        ProcessingContext<Object> context = ProcessingContext.builder()
                .tenant(TENANT)
                .mapping(mapping)
                .payload(payload)
                .serviceConfiguration(serviceConfiguration)
                .topic("test/topic")
                .build();
        // Direct path: graalContext is set, pooledGraalContext is NOT — this is exactly the
        // distinction AbstractFlowProcessor.process() and ProcessingContext.close() branch on.
        context.setGraalContext(directContext);
        context.setFlowContext(new SmartFunctionContext(
                directContext, TENANT, mock(InventoryEnrichmentClient.class), false));

        CountDownLatch workerStarted = new CountDownLatch(1);
        directContext.getBindings("js").putMember("__testSync", new TestSyncBridge(workerStarted));

        ProcessingResultWrapper<Object> wrapper = ProcessingResultWrapper.<Object>builder()
                .pipelineTimeoutMS(30_000)
                .build();
        context.setProcessingResultWrapper(wrapper);

        TestFlowProcessor processor = new TestFlowProcessor(mock(MappingService.class), graalVMContextService);

        wrapper.markWorkerStarted();
        Future<?> workerFuture = workerExecutor.submit(() -> {
            try {
                processor.process(context);
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                wrapper.markWorkerCompleted();
            }
        });
        wrapper.registerWorkerFuture(workerFuture);

        assertTrue(workerStarted.await(5, TimeUnit.SECONDS),
                "Busy-loop JS should have signalled that it started executing");

        boolean drained = wrapper.cancelAndDrain(10_000);
        assertTrue(drained, "Worker must actually leave the pipeline after cancellation, not hang");

        assertTrue(context.hasError(), "Cancellation should be recorded as an error on the context");
        Exception recorded = context.getErrors().get(context.getErrors().size() - 1);
        Throwable cause = recorded.getCause();
        assertInstanceOf(PolyglotException.class, cause,
                "Recorded error should wrap the PolyglotException from the killed context");
        assertCancellationFlavor((PolyglotException) cause);

        // Direct path has no pool to leak into — the correctness question here is instead
        // "is the Context actually closed", i.e. did close(true) really terminate it. A
        // force-cancelled GraalVM Context permanently rejects re-entry — GraalVM surfaces this as
        // a PolyglotException ("Context execution was cancelled"), not a plain
        // IllegalStateException as a gracefully-closed context would.
        Exception reuseAttempt = assertThrows(Exception.class, () -> directContext.eval("js", "1+1"),
                "Directly-closed context must reject further use");
        assertTrue(reuseAttempt instanceof PolyglotException || reuseAttempt instanceof IllegalStateException,
                "Expected a Polyglot/IllegalState exception on reuse, got: " + reuseAttempt);
    }

    /**
     * cancelAndDrain() both interrupts the worker thread and force-closes the context, and
     * whichever lands first decides the flavour GraalVM reports: Context.close(true) yields
     * isCancelled(), while a Thread.interrupt() that wins the race yields isInterrupted()
     * ("Thread was interrupted"). Both are legitimate outcomes of a cancellation; only a
     * generic guest/internal failure would indicate a real problem.
     */
    private static void assertCancellationFlavor(PolyglotException e) {
        assertTrue(e.isCancelled() || e.isInterrupted(),
                "PolyglotException should be a cancellation/interrupt, not a generic failure: " + e);
    }
}
