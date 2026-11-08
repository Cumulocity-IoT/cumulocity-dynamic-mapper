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

package dynamic.mapper.processor.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ProcessingContextLiveTestIdTest {

    @Test
    void liveTestId_isUnchangedOutsideALiveTest() {
        ProcessingContext<Object> context = ProcessingContext.<Object>builder().build();

        assertEquals("device-1", context.liveTestId("device-1"));
        assertNull(context.liveTestId(null));
    }

    @Test
    void liveTestId_prefixesTheExternalIdInALiveTest() {
        ProcessingContext<Object> context = ProcessingContext.<Object>builder()
                .testExternalIdPrefix(ProcessingContext.TEST_EXTERNAL_ID_PREFIX).build();

        assertEquals("d11r_liveTest_device-1", context.liveTestId("device-1"));
        assertNull(context.liveTestId(null));
    }

    @Test
    void affectsMappingStatus_isOnlyTrueForARealRun() {
        assertEquals(true, ProcessingContext.<Object>builder().build().affectsMappingStatus());
        // Dry Run: mock services
        assertEquals(false, ProcessingContext.<Object>builder().testing(true).testRun(true).build().affectsMappingStatus());
        // inbound Live Test: real services (testing=false) but still a test run
        assertEquals(false, ProcessingContext.<Object>builder().testing(false).testRun(true).build().affectsMappingStatus());
        // outbound tests always run with testing=true
        assertEquals(false, ProcessingContext.<Object>builder().testing(true).build().affectsMappingStatus());
    }
}
