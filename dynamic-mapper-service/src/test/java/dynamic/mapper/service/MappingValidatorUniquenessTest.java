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

package dynamic.mapper.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.cumulocity.sdk.client.inventory.ManagedObjectCollection;

import dynamic.mapper.core.facade.InventoryFacade;
import dynamic.mapper.model.API;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.ValidationError;
import dynamic.mapper.processor.model.MappingType;
import dynamic.mapper.processor.model.TransformationType;

/**
 * Covers the duplicate-topic / duplicate-filter checks in {@link MappingValidator}. These were
 * previously dead code (commented out in {@code validate()}) so nothing on the server defended
 * against overlapping mappingTopics or duplicate outbound filters — this test locks in the
 * re-enabled behavior.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MappingValidatorUniquenessTest {

    private static final String TENANT = "testTenant";

    @Mock
    private MicroserviceSubscriptionsService subscriptionsService;

    @Mock
    private MappingRepository mappingRepository;

    @Mock
    private InventoryFacade inventoryApi;

    @Mock
    private ManagedObjectCollection moc;

    private MappingValidator validator;

    @BeforeEach
    void setUp() {
        when(subscriptionsService.callForTenant(eq(TENANT), any())).thenAnswer(invocation -> {
            java.util.concurrent.Callable<?> callable = invocation.getArgument(1);
            return callable.call();
        });
        when(inventoryApi.getManagedObjectsByFilter(any(), eq(false))).thenReturn(moc);

        validator = new MappingValidator(subscriptionsService, mappingRepository, inventoryApi);
    }

    private Mapping makeInboundMapping(String id, String mappingTopic) {
        Mapping mapping = new Mapping();
        mapping.setId(id);
        mapping.setName("mapping-" + id);
        mapping.setDirection(Direction.INBOUND);
        mapping.setTargetAPI(API.MEASUREMENT);
        mapping.setMappingType(MappingType.JSON);
        mapping.setTransformationType(TransformationType.SMART_FUNCTION);
        mapping.setMappingTopic(mappingTopic);
        mapping.setMappingTopicSample(mappingTopic);
        mapping.setSourceTemplate("{}");
        mapping.setFilterMapping("true");
        return mapping;
    }

    private Mapping makeOutboundMapping(String id, String filterMapping) {
        Mapping mapping = new Mapping();
        mapping.setId(id);
        mapping.setName("mapping-" + id);
        mapping.setDirection(Direction.OUTBOUND);
        mapping.setTargetAPI(API.MEASUREMENT);
        mapping.setMappingType(MappingType.JSON);
        mapping.setTransformationType(TransformationType.SMART_FUNCTION);
        mapping.setPublishTopic("device/out");
        mapping.setPublishTopicSample("device/out");
        mapping.setSourceTemplate("{}");
        mapping.setFilterMapping(filterMapping);
        return mapping;
    }

    // ===== validateMappingTopicUniqueness (pure method) =====

    @Test
    void topicUniqueness_flagsExactDuplicate() {
        Mapping existing = makeInboundMapping("existing-1", "device/berlin/temp");
        Mapping candidate = makeInboundMapping("id", "device/berlin/temp");

        List<ValidationError> errors = validator.validateMappingTopicUniqueness(List.of(existing), candidate);

        assertTrue(errors.contains(ValidationError.MappingTopic_Not_Unique));
    }

    @Test
    void topicUniqueness_flagsPrefixOverlapEitherDirection() {
        Mapping broader = makeInboundMapping("existing-1", "device/berlin");
        Mapping narrower = makeInboundMapping("id", "device/berlin/temp");

        assertTrue(validator.validateMappingTopicUniqueness(List.of(broader), narrower)
                .contains(ValidationError.MappingTopic_Not_Unique));
        assertTrue(validator.validateMappingTopicUniqueness(List.of(narrower), broader)
                .contains(ValidationError.MappingTopic_Not_Unique));
    }

    @Test
    void topicUniqueness_allowsDisjointTopics() {
        Mapping existing = makeInboundMapping("existing-1", "device/berlin/temp");
        Mapping candidate = makeInboundMapping("id", "device/hamburg/temp");

        assertTrue(validator.validateMappingTopicUniqueness(List.of(existing), candidate).isEmpty());
    }

    @Test
    void topicUniqueness_ignoresOutboundMappings() {
        Mapping outbound = makeOutboundMapping("existing-1", "true");
        Mapping candidate = makeInboundMapping("id", "device/berlin/temp");

        assertTrue(validator.validateMappingTopicUniqueness(List.of(outbound), candidate).isEmpty());
    }

    // ===== validate() orchestration: existing mappings loaded via InventoryFacade/MappingRepository =====

    @Test
    void validate_flagsDuplicateMappingTopicAgainstPersistedMappings() {
        Mapping existing = makeInboundMapping("existing-1", "device/berlin/temp");
        when(mappingRepository.findAll(eq(TENANT), eq(Direction.INBOUND), any())).thenReturn(List.of(existing));

        Mapping candidate = makeInboundMapping("id", "device/berlin/temp");

        List<ValidationError> errors = validator.validate(TENANT, candidate, null);

        assertTrue(errors.contains(ValidationError.MappingTopic_Not_Unique));
    }

    @Test
    void validate_excludesOwnMappingIdOnUpdate() {
        Mapping self = makeInboundMapping("m1", "device/berlin/temp");
        when(mappingRepository.findAll(eq(TENANT), eq(Direction.INBOUND), any())).thenReturn(List.of(self));

        // Re-validating the same mapping (unchanged topic) during an update must not
        // flag itself as a duplicate of itself.
        List<ValidationError> errors = validator.validate(TENANT, self, "m1");

        assertFalse(errors.contains(ValidationError.MappingTopic_Not_Unique));
    }

    @Test
    void validate_flagsDuplicateOutboundFilter() {
        Mapping existing = makeOutboundMapping("existing-1", "$.temp > 10");
        when(mappingRepository.findAll(eq(TENANT), eq(Direction.OUTBOUND), any())).thenReturn(List.of(existing));

        Mapping candidate = makeOutboundMapping("id", "$.temp > 10");

        List<ValidationError> errors = validator.validate(TENANT, candidate, null);

        assertTrue(errors.contains(ValidationError.FilterOutbound_Must_Be_Unique));
    }
}
