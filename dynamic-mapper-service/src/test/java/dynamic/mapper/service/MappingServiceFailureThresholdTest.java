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

import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;

import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.core.facade.InventoryFacade;
import dynamic.mapper.model.API;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.processor.model.MappingType;
import dynamic.mapper.processor.model.TransformationType;
import dynamic.mapper.service.cache.FlowStateStore;
import dynamic.mapper.service.cache.MappingCacheManager;
import dynamic.mapper.service.deployment.DeploymentMapService;
import dynamic.mapper.service.resolver.MappingResolverService;
import dynamic.mapper.service.status.MappingStatusService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Covers the {@code maxFailureCount} contract promised by the mapping editor: "if this is
 * exceeded the mapping is automatically deactivated".
 */
@ExtendWith(MockitoExtension.class)
class MappingServiceFailureThresholdTest {

    private static final String TENANT = "t1";
    private static final String MO_ID = "mo-1";
    private static final String IDENTIFIER = "abc";

    @Mock private InventoryFacade inventoryApi;
    @Mock private MappingRepository mappingRepository;
    @Mock private MappingCacheManager cacheManager;
    @Mock private MappingStatusService statusService;
    @Mock private MappingResolverService resolverService;
    @Mock private DeploymentMapService deploymentMapService;
    @Mock private DeviceToClientMapService deviceToClientMapService;
    @Mock private ConfigurationRegistry configurationRegistry;
    @Mock private MicroserviceSubscriptionsService subscriptionsService;
    @Mock private MappingValidator mappingValidator;
    @Mock private FlowStateStore flowStateStore;
    @Mock private MappingVersionService mappingVersionService;
    @Mock private ConnectorRegistry connectorRegistry;
    @Mock private AConnectorClient connectorClient;

    private ExecutorService pool;
    private MappingService service;

    @BeforeEach
    void setUp() throws Exception {
        pool = Executors.newSingleThreadExecutor();
        MappingService real = new MappingService(inventoryApi, mappingRepository, cacheManager, statusService,
                resolverService, deploymentMapService, deviceToClientMapService, configurationRegistry,
                subscriptionsService, mappingValidator, flowStateStore, mappingVersionService);
        service = spy(real);
        lenient().when(configurationRegistry.getVirtualThreadPool()).thenReturn(pool);
        lenient().when(configurationRegistry.getConnectorRegistry()).thenReturn(connectorRegistry);
        lenient().when(connectorRegistry.getClientForTenant(eq(TENANT), anyString())).thenReturn(connectorClient);
        lenient().when(deploymentMapService.getDeployedConnectors(TENANT, IDENTIFIER))
                .thenReturn(java.util.List.of("connector-1"));
        // The deactivation itself is exercised by MappingServiceActivationTest; here we only
        // care about whether — and how often — it is triggered.
        lenient().doReturn(null).when(service).setActivationMapping(anyString(), anyString(), anyBoolean(), any());
    }

    private Mapping mapping(long maxFailureCount) {
        return Mapping.builder()
                .id(MO_ID)
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

    private void awaitPool() throws Exception {
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("a failure below the threshold does not deactivate the mapping")
    void belowThresholdKeepsMappingActive() throws Exception {
        Mapping mapping = mapping(3);
        MappingStatus status = new MappingStatus();
        when(statusService.incrementFailureCount(TENANT, mapping, status)).thenReturn(false);

        service.increaseAndHandleFailureCount(TENANT, mapping, status);

        awaitPool();
        verify(service, never()).setActivationMapping(anyString(), anyString(), anyBoolean(), any());
    }

    @Test
    @DisplayName("reaching the threshold actually deactivates the mapping")
    void thresholdDeactivatesMapping() throws Exception {
        Mapping mapping = mapping(3);
        MappingStatus status = new MappingStatus();
        when(statusService.incrementFailureCount(TENANT, mapping, status)).thenReturn(true);

        service.increaseAndHandleFailureCount(TENANT, mapping, status);

        awaitPool();
        verify(service).setActivationMapping(TENANT, MO_ID, false, null);
    }

    @Test
    @DisplayName("a burst of failures for the same mapping triggers only one deactivation")
    void concurrentFailuresDeactivateOnlyOnce() throws Exception {
        Mapping mapping = mapping(3);
        MappingStatus status = new MappingStatus();
        when(statusService.incrementFailureCount(TENANT, mapping, status)).thenReturn(true);
        // Hold the deactivation open so every follow-up failure arrives while the first is
        // still in flight — the situation this guard exists for.
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        doAnswer(inv -> {
            release.await(5, TimeUnit.SECONDS);
            return null;
        }).when(service).setActivationMapping(TENANT, MO_ID, false, null);

        for (int i = 0; i < 5; i++) {
            service.increaseAndHandleFailureCount(TENANT, mapping, status);
        }
        release.countDown();

        awaitPool();
        verify(service, times(1)).setActivationMapping(TENANT, MO_ID, false, null);
    }

    @Test
    @DisplayName("a successful message clears the failure streak")
    void successResetsStreak() {
        Mapping mapping = mapping(3);

        service.resetFailureCountOnSuccess(TENANT, mapping);

        verify(statusService).resetFailureCountOnSuccess(TENANT, mapping);
    }

    @Test
    @DisplayName("deactivation also makes the deployed connectors drop the mapping")
    void deactivationUnsubscribesDeployedConnectors() throws Exception {
        Mapping mapping = mapping(3);
        MappingStatus status = new MappingStatus();
        when(statusService.incrementFailureCount(TENANT, mapping, status)).thenReturn(true);
        doReturn(mapping).when(service).setActivationMapping(TENANT, MO_ID, false, null);

        service.increaseAndHandleFailureCount(TENANT, mapping, status);

        awaitPool();
        // Otherwise the mapping would be flagged inactive while its topic stayed subscribed.
        verify(connectorClient).updateSubscriptionForInbound(mapping, false, true);
    }
}
