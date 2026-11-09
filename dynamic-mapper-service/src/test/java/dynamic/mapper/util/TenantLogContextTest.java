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

package dynamic.mapper.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class TenantLogContextTest {

    @AfterEach
    void clean() {
        MDC.clear();
    }

    @Test
    void sdkKeyWinsInsideAnSdkScopeAndOursSurvivesItsExit() {
        try (var a = TenantLogContext.open("tenantA")) {
            MDC.put(TenantLogContext.SDK_KEY, "tenantB");
            assertEquals("tenantB", TenantLogContext.current());
            // what ContextServiceImpl.leaveContext() does
            MDC.remove(TenantLogContext.SDK_KEY);
            assertEquals("tenantA", TenantLogContext.current());
        }
    }

    @Test
    void openSetsAndCloseRestores() {
        assertNull(MDC.get(TenantLogContext.KEY));
        try (var a = TenantLogContext.open("t1")) {
            assertEquals("t1", MDC.get(TenantLogContext.KEY));
            try (var b = TenantLogContext.open("t2")) {
                assertEquals("t2", MDC.get(TenantLogContext.KEY));
            }
            assertEquals("t1", MDC.get(TenantLogContext.KEY));
        }
        assertNull(MDC.get(TenantLogContext.KEY));
    }

    @Test
    void blankTenantClearsInsteadOfKeepingTheOuterOne() {
        try (var a = TenantLogContext.open("t1")) {
            try (var b = TenantLogContext.open(null)) {
                assertNull(MDC.get(TenantLogContext.KEY));
            }
            assertEquals("t1", MDC.get(TenantLogContext.KEY));
        }
    }

    @Test
    void wrapRestoresEvenWhenTheTaskThrows() {
        try {
            TenantLogContext.wrap("t1", () -> {
                throw new IllegalStateException("boom");
            }).call();
        } catch (Exception expected) {
            // expected
        }
        assertNull(MDC.get(TenantLogContext.KEY));
    }

    @Test
    void workerRunsUnderTheSubmittersTenantAndLeavesNothingBehind() throws Exception {
        ExecutorService pool = new MdcPropagatingExecutorService(
                Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory()));
        try {
            List<Future<String>> seen = new ArrayList<>();
            for (String tenant : List.of("tenantA", "tenantB", "tenantA", "tenantC")) {
                try (var scope = TenantLogContext.open(tenant)) {
                    seen.add(pool.submit(() -> MDC.get(TenantLogContext.KEY)));
                }
            }
            assertEquals("tenantA", seen.get(0).get());
            assertEquals("tenantB", seen.get(1).get());
            assertEquals("tenantA", seen.get(2).get());
            assertEquals("tenantC", seen.get(3).get());

            // Submitted outside any scope: the worker must not inherit a stale tenant.
            assertNull(pool.submit(() -> MDC.get(TenantLogContext.KEY)).get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void submitStillReturnsAnInterruptibleFuture() throws Exception {
        ExecutorService pool = new MdcPropagatingExecutorService(
                Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory()));
        try {
            Future<?> f = pool.submit(() -> {
                try {
                    Thread.sleep(60_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            f.cancel(true);
            assertEquals(true, f.isCancelled());
        } finally {
            pool.shutdownNow();
        }
    }
}
