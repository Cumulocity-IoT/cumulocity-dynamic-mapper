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

package dynamic.mapper.service.status;

import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;

import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.core.facade.InventoryFacade;
import dynamic.mapper.model.API;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.processor.model.MappingType;
import dynamic.mapper.processor.model.TransformationType;
import dynamic.mapper.service.cache.MappingCacheManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;

/**
 * The consecutive-failure counter behind {@code Mapping.maxFailureCount}.
 */
@ExtendWith(MockitoExtension.class)
class MappingStatusServiceFailureCountTest {

    private static final String TENANT = "t1";
    private static final String IDENTIFIER = "abc";

    @Mock private InventoryFacade inventoryApi;
    @Mock private ConfigurationRegistry configurationRegistry;
    @Mock private MappingCacheManager cacheManager;
    @Mock private MicroserviceSubscriptionsService subscriptionsService;
    @Mock private C8YAgent c8yAgent;

    private MappingStatusService service;

    @BeforeEach
    void setUp() {
        service = new MappingStatusService(inventoryApi, configurationRegistry, cacheManager, subscriptionsService);
        lenient().when(configurationRegistry.getC8yAgent()).thenReturn(c8yAgent);
    }

    private Mapping mapping(long maxFailureCount) {
        return Mapping.builder()
                .id("mo-1")
                .identifier(IDENTIFIER)
                .name("Failing")
                .direction(Direction.INBOUND)
                .targetAPI(API.MEASUREMENT)
                .mappingType(MappingType.JSON)
                .transformationType(TransformationType.JSONATA)
                .active(true)
                .debug(false)
                .maxFailureCount(maxFailureCount)
                .sourceTemplate("{}")
                .targetTemplate("{}")
                .build();
    }

    @Test
    @DisplayName("reports the breach only on the failure that reaches the threshold")
    void reportsThresholdOnlyWhenReached() {
        Mapping mapping = mapping(3);
        MappingStatus status = service.getOrCreateStatus(TENANT, mapping);

        assertFalse(service.incrementFailureCount(TENANT, mapping, status), "1st failure");
        assertFalse(service.incrementFailureCount(TENANT, mapping, status), "2nd failure");
        assertTrue(service.incrementFailureCount(TENANT, mapping, status), "3rd failure reaches maxFailureCount");
        assertEquals(3, status.getCurrentFailureCount());
    }

    @Test
    @DisplayName("maxFailureCount 0 disables the check entirely")
    void zeroThresholdNeverTrips() {
        Mapping mapping = mapping(0);
        MappingStatus status = service.getOrCreateStatus(TENANT, mapping);

        for (int i = 0; i < 50; i++) {
            assertFalse(service.incrementFailureCount(TENANT, mapping, status));
        }
        assertEquals(50, status.getCurrentFailureCount());
    }

    @Test
    @DisplayName("the counter is consecutive: a successful message clears the streak")
    void successClearsStreak() {
        Mapping mapping = mapping(3);
        MappingStatus status = service.getOrCreateStatus(TENANT, mapping);

        service.incrementFailureCount(TENANT, mapping, status);
        service.incrementFailureCount(TENANT, mapping, status);
        service.resetFailureCountOnSuccess(TENANT, mapping);
        assertEquals(0, status.getCurrentFailureCount());

        // Two further failures must therefore not trip a threshold of 3.
        assertFalse(service.incrementFailureCount(TENANT, mapping, status));
        assertFalse(service.incrementFailureCount(TENANT, mapping, status));
    }

    @Test
    @DisplayName("resetting on success is a no-op for mappings that did not opt into the check")
    void successResetIsNoOpWhenCheckDisabled() {
        Mapping mapping = mapping(0);
        MappingStatus status = service.getOrCreateStatus(TENANT, mapping);
        service.incrementFailureCount(TENANT, mapping, status);

        service.resetFailureCountOnSuccess(TENANT, mapping);

        // Left untouched: without a threshold the counter is purely informational, and the hot
        // path must not pay for maintaining it.
        assertEquals(1, status.getCurrentFailureCount());
    }

    @Test
    @DisplayName("an unknown mapping does not blow up the success path")
    void successResetToleratesMissingStatus() {
        service.resetFailureCountOnSuccess(TENANT, mapping(3));
        service.resetFailureCountOnSuccess(TENANT, null);
        service.resetFailureCountOnSuccess(null, mapping(3));
    }
}
