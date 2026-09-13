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

package dynamic.mapper.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The two nested processing budgets. See {@code docs/feature/reliability.md}.
 */
class ServiceConfigurationTimeoutTest {

    private ServiceConfiguration config() {
        return new ServiceConfiguration();
    }

    @Test
    @DisplayName("the shipped defaults satisfy the pipeline > CPU invariant")
    void defaultsAreConsistent() {
        ServiceConfiguration config = config();

        assertEquals(ServiceConfiguration.DEFAULT_MAX_CPU_TIME_MS, config.getEffectiveMaxCPUTimeMS());
        assertEquals(ServiceConfiguration.DEFAULT_PIPELINE_TIMEOUT_MS, config.getEffectivePipelineTimeoutMS());
        assertTrue(config.getEffectivePipelineTimeoutMS() > config.getEffectiveMaxCPUTimeMS(),
                "the wall-clock budget must leave room for the C8Y calls after the JS finishes");
    }

    @Test
    @DisplayName("a missing value falls back to the default rather than to 0 or to a per-call-site constant")
    void nullsFallBackToDefaults() {
        ServiceConfiguration config = config();
        config.setMaxCPUTimeMS(null);
        config.setPipelineTimeoutMS(null);

        assertEquals(ServiceConfiguration.DEFAULT_MAX_CPU_TIME_MS, config.getEffectiveMaxCPUTimeMS());
        assertEquals(ServiceConfiguration.DEFAULT_PIPELINE_TIMEOUT_MS, config.getEffectivePipelineTimeoutMS());
    }

    @Test
    @DisplayName("a pipeline timeout below the CPU budget is raised, not honoured as written")
    void invariantIsEnforced() {
        ServiceConfiguration config = config();
        config.setMaxCPUTimeMS(10_000);
        config.setPipelineTimeoutMS(2_000);

        // Honouring 2 s literally would make the 10 s CPU budget unreachable: the callback would
        // always cancel the pipeline before the JS could ever hit its own limit.
        assertTrue(config.getEffectivePipelineTimeoutMS() > 10_000,
                "expected the pipeline budget to be raised above the CPU budget, was: "
                        + config.getEffectivePipelineTimeoutMS());
    }

    @Test
    @DisplayName("equal budgets are also treated as a violation")
    void equalBudgetsAreRaised() {
        ServiceConfiguration config = config();
        config.setMaxCPUTimeMS(5_000);
        config.setPipelineTimeoutMS(5_000);

        assertTrue(config.getEffectivePipelineTimeoutMS() > 5_000);
    }

    @Test
    @DisplayName("a valid configuration is passed through untouched")
    void validConfigurationIsNotModified() {
        ServiceConfiguration config = config();
        config.setMaxCPUTimeMS(1_000);
        config.setPipelineTimeoutMS(60_000);

        assertEquals(1_000, config.getEffectiveMaxCPUTimeMS());
        assertEquals(60_000, config.getEffectivePipelineTimeoutMS());
    }

    @Test
    @DisplayName("disabling the CPU budget leaves the pipeline budget alone")
    void zeroCpuBudgetDisablesTheInvariant() {
        ServiceConfiguration config = config();
        config.setMaxCPUTimeMS(0);
        config.setPipelineTimeoutMS(1_000);

        assertEquals(0, config.getEffectiveMaxCPUTimeMS());
        assertEquals(1_000, config.getEffectivePipelineTimeoutMS());
    }

    @Test
    @DisplayName("the hard ceiling bounds mappings that have no budget of their own")
    void hardCeilingIsSane() {
        // Non-Smart-Function mappings get a per-message timeout of 0; the callbacks fall back to
        // this ceiling so a pipeline blocked in I/O cannot park a worker forever.
        assertTrue(ServiceConfiguration.PROCESSING_HARD_CEILING_MS
                > ServiceConfiguration.DEFAULT_PIPELINE_TIMEOUT_MS,
                "the ceiling must never cut a normally configured pipeline short");
    }
}
