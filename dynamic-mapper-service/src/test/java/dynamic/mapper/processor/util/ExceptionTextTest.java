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

package dynamic.mapper.processor.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.util.ConcurrentModificationException;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class ExceptionTextTest {

    @Test
    void theMessageIsUsedWhenThereIsOne() {
        assertEquals("boom", ExceptionText.of(new IllegalStateException("boom")));
    }

    @Test
    void anExceptionWithoutMessageFallsBackToItsCauseThenToItsClass() {
        assertEquals("root cause",
                ExceptionText.of(new RuntimeException(null, new IllegalArgumentException("root cause"))));
        assertEquals("java.util.ConcurrentModificationException",
                ExceptionText.of(new ConcurrentModificationException()));
        assertEquals("java.util.ConcurrentModificationException",
                ExceptionText.of(new ConcurrentModificationException("  ")));
    }

    @Test
    void nullIsHandled() {
        assertEquals("unknown error", ExceptionText.of(null));
    }

    @Test
    void causeCyclesDoNotHang() {
        RuntimeException a = new RuntimeException();
        RuntimeException b = new RuntimeException();
        a.initCause(b);
        b.initCause(a);

        assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> assertEquals("java.lang.RuntimeException", ExceptionText.of(a)));
    }

    @Test
    void selfCauseDoesNotHang() {
        RuntimeException a = new RuntimeException() {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> ExceptionText.of(a));
    }
}
