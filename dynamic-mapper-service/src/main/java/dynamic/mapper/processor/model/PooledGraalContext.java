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
package dynamic.mapper.processor.model;

import java.util.concurrent.atomic.AtomicBoolean;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Value;

import lombok.extern.slf4j.Slf4j;

/**
 * A GraalVM {@link Context} together with the pre-loaded {@code onMessage}
 * function that was evaluated once at context-creation time.
 *
 * <p>Instances are managed by {@code GraalVMContextService}'s context pool.
 * A {@code PooledGraalContext} is <em>borrowed</em> before JavaScript execution
 * starts and <em>returned</em> to the pool (or discarded) afterwards via
 * {@code GraalVMContextService.returnContext()}.
 *
 * <h2>Thread safety</h2>
 * GraalVM {@link Context} is <strong>not thread-safe</strong>.  Only one thread
 * may use a {@code PooledGraalContext} at a time (enforced by the pool's
 * borrow/return contract).  {@link #kill()} is the single exception: it is safe
 * to call from any thread (e.g. a CPU-timeout scheduler thread) while the
 * executing thread is running JavaScript.
 */
@Slf4j
public class PooledGraalContext {

    private final Context graalContext;
    private final Value onMessageFunction;
    private final Engine engine;
    private final AtomicBoolean killed = new AtomicBoolean(false);

    public PooledGraalContext(Context graalContext, Value onMessageFunction, Engine engine) {
        this.graalContext = graalContext;
        this.onMessageFunction = onMessageFunction;
        this.engine = engine;
    }

    public Context getGraalContext() {
        return graalContext;
    }

    /**
     * The pre-loaded {@code onMessage} function.  Valid as long as this context
     * has not been killed or closed.
     */
    public Value getOnMessageFunction() {
        return onMessageFunction;
    }

    public Engine getEngine() {
        return engine;
    }

    /**
     * Forcibly terminates any running JavaScript and marks this context as killed.
     * A killed context is <em>never</em> returned to the pool.
     * Safe to call from any thread.
     */
    public void kill() {
        if (killed.compareAndSet(false, true)) {
            try {
                graalContext.close(true);
            } catch (Exception e) {
                log.debug("Exception while killing pooled GraalVM context: {}", e.getMessage());
            }
        }
    }

    /** Returns {@code true} if {@link #kill()} has been called. */
    public boolean isKilled() {
        return killed.get();
    }

    /**
     * Closes the underlying context gracefully (no JavaScript executing).
     * Idempotent; a context that was already {@link #kill()}ed is not closed again.
     */
    public void closeQuietly() {
        if (killed.compareAndSet(false, true)) {
            try {
                graalContext.close();
            } catch (Exception e) {
                log.debug("Exception while closing pooled GraalVM context: {}", e.getMessage());
            }
        }
    }
}
