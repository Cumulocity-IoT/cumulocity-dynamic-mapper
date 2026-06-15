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
import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.core.facade.InventoryFacade;
import dynamic.mapper.model.API;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingVersion;
import dynamic.mapper.model.Qos;
import dynamic.mapper.processor.model.MappingType;
import dynamic.mapper.processor.model.TransformationType;
import dynamic.mapper.service.cache.FlowStateStore;
import dynamic.mapper.service.cache.MappingCacheManager;
import dynamic.mapper.service.deployment.DeploymentMapService;
import dynamic.mapper.service.resolver.MappingResolverService;
import dynamic.mapper.service.status.MappingStatusService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for version-aware {@link MappingService#setActivationMapping}. Uses a
 * Mockito spy so the real activation orchestration (lock, version swap, validate-
 * before-persist) runs while the self-invoked persistence calls
 * ({@code getMapping}/{@code updateMapping}) are stubbed.
 */
@ExtendWith(MockitoExtension.class)
class MappingServiceActivationTest {

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
    @Mock private C8YAgent c8yAgent;

    private MappingService service;

    @BeforeEach
    void setUp() {
        MappingService real = new MappingService(inventoryApi, mappingRepository, cacheManager, statusService,
                resolverService, deploymentMapService, deviceToClientMapService, configurationRegistry,
                subscriptionsService, mappingValidator, flowStateStore, mappingVersionService);
        service = spy(real);

        lenient().when(configurationRegistry.getC8yAgent()).thenReturn(c8yAgent);
        lenient().when(configurationRegistry.getObjectMapper()).thenReturn(new ObjectMapper());
        lenient().when(cacheManager.removeMapping(any(), any())).thenReturn(Optional.empty());
        // updateMapping echoes back the mapping it was asked to persist.
        lenient().doAnswer(inv -> inv.getArgument(1)).when(service)
                .updateMapping(eq(TENANT), any(Mapping.class), anyBoolean(), anyBoolean());
    }

    private Mapping runnable() {
        return Mapping.builder()
                .id(MO_ID)
                .identifier(IDENTIFIER)
                .name("Runnable")
                .direction(Direction.INBOUND)
                .targetAPI(API.MEASUREMENT)
                .mappingType(MappingType.JSON)
                .transformationType(TransformationType.JSONATA)
                .active(false)
                .debug(false)
                .qos(Qos.AT_LEAST_ONCE)
                .sourceTemplate("{\"v\":1}")
                .targetTemplate("{}")
                .versionNumber(1)
                .draftDirty(true)
                .build();
    }

    private MappingVersion version(int number, String name, String source) {
        Mapping snapshot = Mapping.builder()
                .id(MO_ID)
                .identifier(IDENTIFIER)
                .name(name)
                .direction(Direction.INBOUND)
                .targetAPI(API.MEASUREMENT)
                .mappingType(MappingType.JSON)
                .transformationType(TransformationType.JSONATA)
                .active(false)
                .debug(false)
                .qos(Qos.AT_LEAST_ONCE)
                .sourceTemplate(source)
                .targetTemplate("{}")
                .versionNumber(number)
                .draftDirty(false)
                .build();
        return MappingVersion.builder()
                .identifier(IDENTIFIER)
                .versionNumber(number)
                .snapshot(snapshot)
                .isDraft(false)
                .label("label-" + number)
                .build();
    }

    @Test
    void activateSpecificVersionCopiesSnapshotIntoRunnable() throws Exception {
        doReturn(runnable()).when(service).getMapping(TENANT, MO_ID);
        when(mappingVersionService.getVersion(TENANT, MO_ID, 2)).thenReturn(version(2, "V2 content", "{\"v\":2}"));

        Mapping result = service.setActivationMapping(TENANT, MO_ID, true, 2);

        ArgumentCaptor<Mapping> captor = ArgumentCaptor.forClass(Mapping.class);
        verify(service).updateMapping(eq(TENANT), captor.capture(), eq(true), eq(true));
        Mapping persisted = captor.getValue();

        assertEquals(MO_ID, persisted.getId(), "line MO id preserved");
        assertEquals(IDENTIFIER, persisted.getIdentifier(), "identifier preserved");
        assertEquals(2, persisted.getVersionNumber());
        assertEquals("V2 content", persisted.getName(), "content taken from the version snapshot");
        assertEquals("{\"v\":2}", persisted.getSourceTemplate());
        assertEquals("label-2", persisted.getVersionLabel());
        assertTrue(persisted.isDraftDirty(), "line-level draft flag preserved from runnable");
        assertTrue(persisted.getActive());

        verify(cacheManager).addMapping(TENANT, persisted);
        verify(statusService).resetFailureCount(TENANT, IDENTIFIER);
        assertEquals(2, result.getVersionNumber());
    }

    @Test
    void validationFailureLeavesRunningVersionUntouched() throws Exception {
        doReturn(runnable()).when(service).getMapping(TENANT, MO_ID);
        when(mappingVersionService.getVersion(TENANT, MO_ID, 2)).thenReturn(version(2, "V2", "{\"v\":2}"));
        // Persistence rejects the activation (e.g. validation) -> must propagate, cache untouched.
        doThrow(new MappingValidationException(java.util.List.of(
                dynamic.mapper.model.ValidationError.Source_Template_Must_Be_Valid_JSON)))
                .when(service).updateMapping(eq(TENANT), any(Mapping.class), anyBoolean(), anyBoolean());

        assertThrows(MappingValidationException.class, () -> service.setActivationMapping(TENANT, MO_ID, true, 2));

        verify(cacheManager, never()).addMapping(any(), any());
        verify(statusService, never()).resetFailureCount(any(), any());
        verify(c8yAgent).createOperationEvent(any(), eq(dynamic.mapper.model.LoggingEventType.MAPPING_ACTIVATION_ERROR_EVENT_TYPE),
                any(), eq(TENANT), any());
    }

    @Test
    void activationBackfillsLegacyVersion() throws Exception {
        Mapping mapping = runnable();
        doReturn(mapping).when(service).getMapping(TENANT, MO_ID);

        service.setActivationMapping(TENANT, MO_ID, true, null);

        verify(mappingVersionService).ensureBackfilled(TENANT, mapping);
    }

    @Test
    void plainActivationKeepsCurrentVersionAndValidates() throws Exception {
        doReturn(runnable()).when(service).getMapping(TENANT, MO_ID);

        service.setActivationMapping(TENANT, MO_ID, true, null);

        // No version lookup, and validation is NOT ignored when activating the current version.
        verify(mappingVersionService, never()).getVersion(any(), any(), anyInt());
        verify(service).updateMapping(eq(TENANT), any(Mapping.class), eq(true), eq(false));
    }

    @Test
    void deactivationIgnoresValidationAndVersion() throws Exception {
        doReturn(runnable()).when(service).getMapping(TENANT, MO_ID);

        service.setActivationMapping(TENANT, MO_ID, false, 2);

        verify(mappingVersionService, never()).getVersion(any(), any(), anyInt());
        ArgumentCaptor<Mapping> captor = ArgumentCaptor.forClass(Mapping.class);
        verify(service).updateMapping(eq(TENANT), captor.capture(), eq(true), eq(true));
        assertFalse(captor.getValue().getActive());
    }

    @Test
    void activatingSameVersionNumberDoesNotSwap() throws Exception {
        doReturn(runnable()).when(service).getMapping(TENANT, MO_ID); // current versionNumber = 1

        service.setActivationMapping(TENANT, MO_ID, true, 1);

        verify(mappingVersionService, never()).getVersion(any(), any(), anyInt());
    }

    @Test
    void concurrentActivationsDoNotInterleaveOrFail() throws Exception {
        doAnswer(inv -> runnable()).when(service).getMapping(TENANT, MO_ID);
        when(mappingVersionService.getVersion(TENANT, MO_ID, 2)).thenReturn(version(2, "V2", "{\"v\":2}"));
        when(mappingVersionService.getVersion(TENANT, MO_ID, 3)).thenReturn(version(3, "V3", "{\"v\":3}"));

        CountDownLatch start = new CountDownLatch(1);
        Runnable activate2 = guarded(start, () -> service.setActivationMapping(TENANT, MO_ID, true, 2));
        Runnable activate3 = guarded(start, () -> service.setActivationMapping(TENANT, MO_ID, true, 3));

        Thread t1 = new Thread(activate2);
        Thread t2 = new Thread(activate3);
        t1.start();
        t2.start();
        start.countDown();
        t1.join(TimeUnit.SECONDS.toMillis(5));
        t2.join(TimeUnit.SECONDS.toMillis(5));

        assertFalse(t1.isAlive(), "activation must not deadlock");
        assertFalse(t2.isAlive(), "activation must not deadlock");
        verify(service, times(2)).updateMapping(eq(TENANT), any(Mapping.class), anyBoolean(), anyBoolean());
    }

    private Runnable guarded(CountDownLatch start, ThrowingRunnable body) {
        return () -> {
            try {
                start.await();
                body.run();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
