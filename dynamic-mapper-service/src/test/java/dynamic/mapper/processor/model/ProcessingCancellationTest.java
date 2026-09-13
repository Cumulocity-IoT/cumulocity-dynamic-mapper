/*
 * Copyright (c) 2022-2025 Cumulocity GmbH.
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

package dynamic.mapper.processor.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Proves that a misbehaving mapping is actually stopped and leaves no thread behind.
 *
 * <p>These cover the mechanism every broker callback relies on when its pipeline timeout
 * fires: {@link ProcessingResultWrapper#cancelAndDrain(long)} must (a) run the registered
 * cancel actions — the only thing that stops CPU-bound JavaScript — and (b) report truthfully
 * whether the worker thread actually left, so a stuck pipeline is visible instead of silently
 * leaking a thread.
 */
class ProcessingCancellationTest {

    private ExecutorService pool;

    @AfterEach
    void tearDown() {
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    /**
     * Mirrors what the dispatchers do: submit the pipeline and let it register itself as the
     * worker, so a cancelling caller can observe it leaving.
     */
    private ProcessingResultWrapper<Object> submitWorker(Runnable body) throws Exception {
        ProcessingResultWrapper<Object> wrapper = ProcessingResultWrapper.<Object>builder().build();
        CountDownLatch started = new CountDownLatch(1);
        Future<List<ProcessingContext<Object>>> future = pool.submit(() -> {
            wrapper.markWorkerStarted();
            started.countDown();
            try {
                body.run();
                return List.<ProcessingContext<Object>>of();
            } finally {
                wrapper.markWorkerCompleted();
            }
        });
        wrapper.setProcessingResult(future);
        assertTrue(started.await(5, TimeUnit.SECONDS), "worker must have started");
        return wrapper;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("cancelAndDrain reports success once the worker has really finished")
    @Timeout(15)
    void drainReportsSuccessWhenWorkerExits() throws Exception {
        pool = Executors.newSingleThreadExecutor();
        // Interruptible work, i.e. the well-behaved case: Future.cancel(true) is enough.
        ProcessingResultWrapper<Object> wrapper = submitWorker(() -> sleepQuietly(60_000));

        assertTrue(wrapper.cancelAndDrain(5_000),
                "an interruptible worker must be gone well within the drain window");
    }

    @Test
    @DisplayName("cancelAndDrain reports failure — it does not pretend — when the worker will not die")
    @Timeout(20)
    void drainReportsFailureForStuckWorker() throws Exception {
        pool = Executors.newSingleThreadExecutor();
        AtomicBoolean release = new AtomicBoolean(false);
        // Uninterruptible: models a thread stuck mid-flight in an HTTP call. This is the case a
        // drain loop polling Future.isDone() could never detect — cancel(true) flips isDone()
        // to true immediately while this thread keeps spinning.
        ProcessingResultWrapper<Object> wrapper = submitWorker(() -> {
            while (!release.get()) {
                Thread.onSpinWait();
            }
        });

        try {
            assertTrue(wrapper.getProcessingResult().cancel(true));
            assertTrue(wrapper.getProcessingResult().isDone(),
                    "precondition: Future.isDone() lies about a still-running worker");

            assertFalse(wrapper.cancelAndDrain(1_000),
                    "a worker that ignores interruption must be reported as NOT drained");
        } finally {
            release.set(true);
        }
    }

    @Test
    @DisplayName("cancelAndDrain runs every registered cancel action")
    @Timeout(15)
    void cancelActionsAreInvoked() throws Exception {
        pool = Executors.newSingleThreadExecutor();
        ProcessingResultWrapper<Object> wrapper = submitWorker(() -> sleepQuietly(60_000));

        AtomicBoolean first = new AtomicBoolean(false);
        AtomicBoolean second = new AtomicBoolean(false);
        AtomicBoolean removed = new AtomicBoolean(false);
        Runnable removedAction = () -> removed.set(true);
        wrapper.addCancelAction(() -> first.set(true));
        wrapper.addCancelAction(() -> second.set(true));
        wrapper.addCancelAction(removedAction);
        wrapper.removeCancelAction(removedAction);

        wrapper.cancelAndDrain(5_000);

        assertTrue(first.get(), "1st cancel action must run");
        assertTrue(second.get(), "2nd cancel action must run");
        assertFalse(removed.get(), "a de-registered action must not run");
        assertTrue(wrapper.getCancellationRequested().get(),
                "processors poll this flag to skip post-JS Cumulocity calls");
    }

    @Test
    @DisplayName("a cancel action that throws does not stop the remaining ones")
    @Timeout(15)
    void throwingCancelActionDoesNotAbortTheRest() throws Exception {
        pool = Executors.newSingleThreadExecutor();
        ProcessingResultWrapper<Object> wrapper = submitWorker(() -> sleepQuietly(60_000));

        AtomicBoolean afterThrow = new AtomicBoolean(false);
        wrapper.addCancelAction(() -> {
            throw new IllegalStateException("context already closed");
        });
        wrapper.addCancelAction(() -> afterThrow.set(true));

        wrapper.cancelAndDrain(5_000);

        assertTrue(afterThrow.get(),
                "one failing cancel action must not leave the other resources uncancelled");
    }

    @Test
    @DisplayName("a runaway JavaScript loop is killed and its thread terminates")
    @Timeout(30)
    void runawayJavaScriptIsStoppedAndLeavesNoThread() throws Exception {
        // This is the case the CPU budget exists for: CPU-bound JavaScript ignores Java thread
        // interruption, so only Context.close(cancelIfExecuting=true) — registered as a cancel
        // action, exactly as AbstractFlowProcessor does — can stop it.
        Context graalContext = Context.newBuilder("js").allowAllAccess(false).build();
        AtomicBoolean jsReturned = new AtomicBoolean(false);

        pool = Executors.newSingleThreadExecutor();
        ProcessingResultWrapper<Object> wrapper = submitWorker(() -> {
            try {
                graalContext.eval("js", "while(true){}");
            } catch (Exception expected) {
                // PolyglotException(isCancelled) once the context is closed under it
            } finally {
                jsReturned.set(true);
            }
        });
        Thread.sleep(300); // let the loop spin up so the kill really has to abort it

        // Exactly what AbstractFlowProcessor registers as its cancel action.
        wrapper.addCancelAction(() -> graalContext.close(true));

        boolean drained = wrapper.cancelAndDrain(10_000);

        assertTrue(drained, "the worker running the infinite loop must be gone after cancellation");
        assertTrue(jsReturned.get(), "JavaScript execution must have been aborted");

        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS),
                "no thread from the misbehaving mapping may linger");
    }

    @Test
    @DisplayName("cancelAndDrain tolerates a wrapper whose future was never set")
    void toleratesMissingFuture() {
        ProcessingResultWrapper<Object> wrapper = ProcessingResultWrapper.<Object>builder().build();
        assertTrue(wrapper.cancelAndDrain(100),
                "an early-exit path that never started a pipeline counts as drained");
        assertEquals(true, wrapper.getCancellationRequested().get());
    }
}
