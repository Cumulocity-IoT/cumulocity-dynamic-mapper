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
 * Covers the duplicate-outbound-filter check in {@link MappingValidator}. This was previously
 * dead code (its call was commented out in {@code validate()}) so nothing on the server
 * defended against two outbound mappings sharing the same {@code filterMapping} expression —
 * this test locks in the re-enabled behavior.
 *
 * <p>Note: a companion duplicate-<em>mappingTopic</em> check was considered but deliberately
 * NOT added — see the mapping-validation review notes. The resolver
 * ({@code MappingTreeNode.resolveTopicPath}) already matches per MQTT topic-level/wildcard
 * semantics and is designed to let multiple mappings match one incoming topic (fan-out), so a
 * plain string-prefix "uniqueness" check would reject legitimate, unrelated topics (e.g.
 * {@code "device/berlin"} vs {@code "device/berlin/temp"} never actually overlap under real
 * MQTT subscription semantics) without defending against any genuine conflict.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MappingValidatorFilterUniquenessTest {

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

    @Test
    void validate_flagsDuplicateOutboundFilter() {
        Mapping existing = makeOutboundMapping("existing-1", "$.temp > 10");
        when(mappingRepository.findAll(eq(TENANT), eq(Direction.OUTBOUND), any())).thenReturn(List.of(existing));

        Mapping candidate = makeOutboundMapping("id", "$.temp > 10");

        List<ValidationError> errors = validator.validate(TENANT, candidate, null);

        assertTrue(errors.contains(ValidationError.FilterOutbound_Must_Be_Unique));
    }

    @Test
    void validate_allowsDistinctOutboundFilters() {
        Mapping existing = makeOutboundMapping("existing-1", "$.temp > 10");
        when(mappingRepository.findAll(eq(TENANT), eq(Direction.OUTBOUND), any())).thenReturn(List.of(existing));

        Mapping candidate = makeOutboundMapping("id", "$.temp > 50");

        List<ValidationError> errors = validator.validate(TENANT, candidate, null);

        assertFalse(errors.contains(ValidationError.FilterOutbound_Must_Be_Unique));
    }

    @Test
    void validate_excludesOwnMappingIdOnUpdate() {
        Mapping self = makeOutboundMapping("m1", "$.temp > 10");
        when(mappingRepository.findAll(eq(TENANT), eq(Direction.OUTBOUND), any())).thenReturn(List.of(self));

        // Re-validating the same mapping (unchanged filter) during an update must not
        // flag itself as a duplicate of itself.
        List<ValidationError> errors = validator.validate(TENANT, self, "m1");

        assertFalse(errors.contains(ValidationError.FilterOutbound_Must_Be_Unique));
    }
}
