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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;

class MdcPropagatingMappingTest {

    @Test
    void aTaskKeepsTheMappingOfItsSubmitterAndAPooledThreadNeverKeepsItAfterwards() throws Exception {
        ExecutorService pool = new MdcPropagatingExecutorService(Executors.newSingleThreadExecutor());
        try {
            String inside;
            try (MappingLogContext scope = MappingLogContext.open("aaa")) {
                inside = pool.submit(MappingLogContext::current).get();
            }
            String outside = pool.submit(MappingLogContext::current).get();

            assertEquals("aaa", inside);
            assertNull(outside);
        } finally {
            pool.shutdownNow();
        }
    }
}
