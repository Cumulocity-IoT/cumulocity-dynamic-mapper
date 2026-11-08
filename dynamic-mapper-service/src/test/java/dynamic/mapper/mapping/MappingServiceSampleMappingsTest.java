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

package dynamic.mapper.mapping;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;

import dynamic.mapper.core.ServiceRegistry;
import dynamic.mapper.core.facade.InventoryFacade;
import dynamic.mapper.mapping.cache.MappingCacheManager;
import dynamic.mapper.mapping.deployment.DeploymentMapService;
import dynamic.mapper.mapping.resolver.MappingResolverService;
import dynamic.mapper.mapping.status.MappingStatusService;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.flow.FlowStateStore;

/** Adding the shipped sample mappings: only missing ones, and never active. */
class MappingServiceSampleMappingsTest {

    private static final String TENANT = "t1";

    private MappingService service;

    @BeforeEach
    void setUp() {
        service = spy(new MappingService(mock(InventoryFacade.class), mock(MappingRepository.class),
                mock(MappingCacheManager.class), mock(MappingStatusService.class),
                mock(MappingResolverService.class), mock(DeploymentMapService.class),
                mock(DeviceToClientMapService.class), mock(ServiceRegistry.class),
                mock(MicroserviceSubscriptionsService.class), mock(MappingValidator.class),
                mock(FlowStateStore.class), mock(MappingVersionService.class),
                mock(ApplicationEventPublisher.class)));
    }

    private Mapping mapping(String identifier) {
        Mapping m = new Mapping();
        m.setIdentifier(identifier);
        m.setActive(true);
        return m;
    }

    @Test
    void createsOnlyTheSamplesTheTenantDoesNotHaveYet_asInactiveMappings() {
        doReturn(List.of(mapping("have"))).when(service).getMappings(TENANT, Direction.INBOUND);
        doReturn(null).when(service).createMapping(anyString(), any());
        Mapping existing = mapping("have");
        Mapping fresh = mapping("new");

        service.addSampleMappings(TENANT, Direction.INBOUND, List.of(existing, fresh));

        // Mapping.equals ignores the identifier, so compare what was created by identifier
        ArgumentCaptor<Mapping> created = ArgumentCaptor.forClass(Mapping.class);
        verify(service, times(1)).createMapping(eq(TENANT), created.capture());
        assertEquals("new", created.getValue().getIdentifier());
        assertFalse(fresh.getActive(), "a sample must never start processing by itself");
    }
}
