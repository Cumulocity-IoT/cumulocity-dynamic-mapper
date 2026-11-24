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

package dynamic.mapper.integration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.processor.flow.FlowStateStore;
import dynamic.mapper.processor.inbound.MessageDeduplicationService;
import dynamic.mapper.processor.inbound.processor.ExtensibleInboundProcessor;
import dynamic.mapper.processor.outbound.processor.ExtensibleOutboundProcessor;

/**
 * The extensible processors have a constructor for Spring and one for tests that leaves the new collaborators
 * null. A Java extension silently loses {@code isDuplicate} (and, for outbound, every inventory lookup) if Spring
 * picks the wrong one, and no unit test builds the processors through Spring. Same eager, no-mock context as
 * {@link FullContextStartupTest}, so the application context is shared with it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.main.lazy-initialization=false",
        "spring.main.allow-circular-references=false"
})
@ActiveProfiles("test")
class ExtensibleProcessorWiringTest {

    @Autowired
    private ExtensibleInboundProcessor inbound;
    @Autowired
    private ExtensibleOutboundProcessor outbound;
    @Autowired
    private C8YAgent c8yAgent;
    @Autowired
    private FlowStateStore flowStateStore;
    @Autowired
    private MessageDeduplicationService deduplication;

    @Test
    void theInboundProcessorGetsTheDeduplicationServiceAndTheAgent() {
        assertNotNull(ReflectionTestUtils.getField(inbound, "deduplication"),
                "Spring used the constructor without the deduplication service");
        assertSame(flowStateStore, ReflectionTestUtils.getField(inbound, "flowStateStore"));
        assertNotNull(ReflectionTestUtils.getField(inbound, "c8yAgent"));
    }

    @Test
    void theOutboundProcessorGetsTheAgentAndTheDeduplicationService() {
        assertNotNull(ReflectionTestUtils.getField(outbound, "c8yAgent"),
                "Spring used the constructor without the agent: outbound extensions get no inventory");
        assertNotNull(ReflectionTestUtils.getField(outbound, "deduplication"),
                "Spring used the constructor without the deduplication service");
        assertSame(flowStateStore, ReflectionTestUtils.getField(outbound, "flowStateStore"));
    }

    @Test
    void theInjectedCollaboratorsAreTheApplicationBeans() {
        // @Lazy hands out a proxy; calling through it must reach the real beans without a cycle or a missing bean
        MessageDeduplicationService seen = (MessageDeduplicationService) ReflectionTestUtils.getField(outbound,
                "deduplication");
        assertNotNull(seen.toString());
        assertNotNull(deduplication);
        assertNotNull(c8yAgent);
    }
}
