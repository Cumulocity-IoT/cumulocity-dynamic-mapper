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
    private Future future;

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
        // 1. Interrupt the processing thread (effective for blocking IO)
        boolean cancelled = processingResult != null && processingResult.cancel(true);

        // 2. Run all registered cancel actions (effective for GraalVM JS execution)
        for (Runnable action : cancelActions) {
            try {
                action.run();
            } catch (Exception e) {
                log.warn("Cancel action threw an exception (ignored): {}", e.getMessage());
            }
        }
        return cancelled;
    }
}