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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import dynamic.mapper.model.Qos;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Getter
@Setter
@Builder
@Slf4j
public class ProcessingResultWrapper<O> {
    private Future<List<ProcessingContext<O>>> processingResult;
    private Qos consolidatedQos;
    private int maxCPUTimeMS;
    private Exception error;
    @SuppressWarnings("rawtypes")
    private Future future;

    /**
     * Flag to indicate that processing cancellation has been requested.
     * Set to true when cancelProcessing() is called, can be checked by processing code
     * to detect that they should stop executing.
     */
    @Getter
    private final AtomicBoolean cancellationRequested = new AtomicBoolean(false);

    /**
     * Cancel actions registered by in-flight processors (e.g. GraalVM context closures).
     * Using CopyOnWriteArrayList for thread-safe iteration without locking.
     * Excluded from the Lombok @Builder to allow safe lazy initialisation.
     */
    @Builder.Default
    private final CopyOnWriteArrayList<Runnable> cancelActions = new CopyOnWriteArrayList<>();

    /**
     * Register a cancel action that will be invoked by {@link #cancelProcessing()}.
     * Idempotent — adding the same instance twice is harmless.
     *
     * @param action the action to run on cancellation (e.g. {@code () -> graalCtx.close(true)})
     */
    public void addCancelAction(Runnable action) {
        if (action != null) {
            cancelActions.add(action);
        }
    }

    /**
     * Remove a previously registered cancel action (called after the guarded section exits).
     *
     * @param action the action to remove
     */
    public void removeCancelAction(Runnable action) {
        cancelActions.remove(action);
    }

    /**
     * Cancel the ongoing processing:
     * <ol>
     *   <li>Set the {@link #cancellationRequested} flag to true so processing code can detect
     *       the cancellation request and exit early</li>
     *   <li>Interrupt the virtual thread via {@link Future#cancel(boolean) cancel(true)} on the
     *       underlying future — effective for threads blocked in IO (C8Y SDK HTTP calls).</li>
     *   <li>Invoke every registered cancel action — required for CPU-bound GraalVM JavaScript
     *       execution which ignores Java thread interruption; the registered action calls
     *       {@code Context.close(cancelIfExecuting=true)} on the active GraalVM context.</li>
     * </ol>
     *
     * @return {@code true} if the future cancellation succeeded (same semantics as
     *         {@link Future#cancel(boolean)})
     */
    public boolean cancelProcessing() {
        log.debug("Cancelling processing on thread: {}", Thread.currentThread().getName());

        // 1. Set the cancellation flag so processing code can check it
        cancellationRequested.set(true);

        // 2. Interrupt the processing thread (effective for blocking IO)
        boolean cancelled = processingResult != null && processingResult.cancel(true);
        log.debug("Future.cancel(true) returned: {}", cancelled);

        // 3. Run all registered cancel actions (effective for GraalVM JS execution)
        log.debug("Invoking {} cancel action(s)", cancelActions.size());
        int actionCount = 0;
        for (Runnable action : cancelActions) {
            actionCount++;
            log.debug("Calling cancel action #{} of {}", actionCount, cancelActions.size());
            try {
                action.run();
                log.debug("Cancel action #{} completed", actionCount);
            } catch (Exception e) {
                log.debug("Cancel action #{} threw an exception (ignored): {} - {}",
                        actionCount, e.getClass().getSimpleName(), e.getMessage(), e);
            }
        }
        log.debug("All {} cancel action(s) processed. Cancelling finished!", cancelActions.size());
        return cancelled;
    }
}