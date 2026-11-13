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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dynamic.mapper.mapping.MappingService;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.status.MappingStatus;
import dynamic.mapper.processor.runtime.ProcessingContext;

/** A test run must never change a mapping's runtime status — including an inbound Live Test. */
class ConsolidationProcessorTestRunTest {

    private MappingService mappingService;
    private ConsolidationProcessor processor;
    private Mapping mapping;

    @BeforeEach
    void setUp() {
        mappingService = mock(MappingService.class);
        when(mappingService.getMappingStatus(any(), any(Mapping.class))).thenReturn(mock(MappingStatus.class));
        processor = new ConsolidationProcessor(mappingService);
        mapping = Mapping.builder().identifier("m").build();
    }

    private ProcessingContext<Object> context(boolean testing, boolean testRun) {
        return ProcessingContext.<Object>builder().tenant("t1").mapping(mapping).testing(testing).testRun(testRun).build();
    }

    @Test
    void realRun_resetsTheFailureStreakAndRecordsTheOutcome() throws Exception {
        processor.process(context(false, false));

        verify(mappingService).resetFailureCountOnSuccess("t1", mapping);
        verify(mappingService).getMappingStatus("t1", mapping);
    }

    @Test
    void liveTest_realServicesButStillATestRun_touchesNothing() throws Exception {
        processor.process(context(false, true));

        verify(mappingService, never()).resetFailureCountOnSuccess(any(), any());
        verify(mappingService, never()).getMappingStatus(any(), any(Mapping.class));
    }

    @Test
    void dryRun_touchesNothing() throws Exception {
        processor.process(context(true, true));

        verify(mappingService, never()).resetFailureCountOnSuccess(any(), any());
        verify(mappingService, never()).getMappingStatus(any(), any(Mapping.class));
    }
}
