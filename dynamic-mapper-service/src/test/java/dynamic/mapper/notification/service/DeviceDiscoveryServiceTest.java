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

package dynamic.mapper.notification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.inventory.ManagedObjectReferenceCollectionRepresentation;
import com.cumulocity.rest.representation.inventory.ManagedObjectReferenceRepresentation;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;

import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.model.Device;

@ExtendWith(MockitoExtension.class)
class DeviceDiscoveryServiceTest {

    @Mock
    private ConfigurationRegistry configurationRegistry;

    @Mock
    private C8YAgent c8yAgent;

    private DeviceDiscoveryService deviceDiscoveryService;

    private static final String TENANT_A = "tenantA";
    private static final String TENANT_B = "tenantB";
    private static final String GROUP_ID = "group-1";
    private static final String DEVICE_ID_1 = "device-1";
    private static final String DEVICE_ID_2 = "device-2";

    @BeforeEach
    void setUp() {
        // configurationRegistry.getC8yAgent() is the only collaborator the service
        // dereferences; mark lenient since not every test path hits it.
        lenient().when(configurationRegistry.getC8yAgent()).thenReturn(c8yAgent);
        deviceDiscoveryService = new DeviceDiscoveryService(configurationRegistry);
    }

    // === Helpers ===

    private ManagedObjectRepresentation buildDevice(String id) {
        ManagedObjectRepresentation mo = new ManagedObjectRepresentation();
        mo.setId(new GId(id));
        mo.setName("name-" + id);
        mo.setType("c8y_TestDevice");
        mo.setProperty("c8y_IsDevice", new java.util.HashMap<>());
        return mo;
    }

    private ManagedObjectReferenceRepresentation buildAssetRef(String childId) {
        ManagedObjectRepresentation child = new ManagedObjectRepresentation();
        child.setId(new GId(childId));
        ManagedObjectReferenceRepresentation ref = new ManagedObjectReferenceRepresentation();
        ref.setManagedObject(child);
        return ref;
    }

    /**
     * Builds a device-group MO whose childAssets references point to the given
     * child device IDs. The references only carry the child id; the full child MO
     * is resolved lazily via C8YAgent.getManagedObjectForId().
     */
    private ManagedObjectRepresentation buildGroup(String groupId, String... childIds) {
        ManagedObjectRepresentation group = new ManagedObjectRepresentation();
        group.setId(new GId(groupId));
        group.setName("group-" + groupId);
        group.setProperty("c8y_IsDeviceGroup", new java.util.HashMap<>());

        ManagedObjectReferenceCollectionRepresentation childAssets = new ManagedObjectReferenceCollectionRepresentation();
        List<ManagedObjectReferenceRepresentation> refs = new ArrayList<>();
        for (String childId : childIds) {
            refs.add(buildAssetRef(childId));
        }
        childAssets.setReferences(refs);
        group.setChildAssets(childAssets);
        return group;
    }

    @SuppressWarnings("unchecked")
    private Set<String> readProcessingDevices() throws Exception {
        Field field = DeviceDiscoveryService.class.getDeclaredField("processingDevices");
        field.setAccessible(true);
        return (Set<String>) field.get(deviceDiscoveryService);
    }

    // === Tests ===

    @Test
    void findAllRelatedDevices_simpleGroup_returnsChildren() {
        // Arrange: a group with two child device references; the C8YAgent resolves
        // each child reference to a full device MO.
        ManagedObjectRepresentation group = buildGroup(GROUP_ID, DEVICE_ID_1, DEVICE_ID_2);

        when(c8yAgent.getManagedObjectForId(eq(TENANT_A), eq(DEVICE_ID_1), eq(false)))
                .thenReturn(buildDevice(DEVICE_ID_1));
        when(c8yAgent.getManagedObjectForId(eq(TENANT_A), eq(DEVICE_ID_2), eq(false)))
                .thenReturn(buildDevice(DEVICE_ID_2));

        // Act
        List<Device> result = deviceDiscoveryService.findAllRelatedDevicesByMO(
                TENANT_A, group, new ArrayList<>(), false);

        // Assert
        assertEquals(2, result.size());
        Set<String> ids = result.stream().map(Device::getId).collect(Collectors.toSet());
        assertTrue(ids.contains(DEVICE_ID_1), "Result must contain " + DEVICE_ID_1);
        assertTrue(ids.contains(DEVICE_ID_2), "Result must contain " + DEVICE_ID_2);
    }

    /**
     * H3 — processingDevices is a single Set shared across all tenants. When two
     * tenants discover the same group (same group id, same child device id)
     * concurrently, both calls must still complete with non-empty results and the
     * circuit-breaker set must be empty afterwards (the finally block removes the
     * correct key, deviceId, not a tenant-scoped composite key).
     */
    @Test
    void findAllRelatedDevices_sameIdDifferentTenants_bothCompleteAndSetEmpty() throws Exception {
        // Both tenants discover the SAME group with the SAME single child device id,
        // so they contend on the same entries in the shared processingDevices set.
        ManagedObjectRepresentation group = buildGroup(GROUP_ID, DEVICE_ID_1);

        // C8YAgent resolves the child for either tenant. Use lenient stubbing keyed
        // on tenant so each thread gets its own device MO instance.
        lenient().when(c8yAgent.getManagedObjectForId(anyString(), eq(DEVICE_ID_1), eq(false)))
                .thenAnswer(invocation -> buildDevice(DEVICE_ID_1));

        CyclicBarrier startBarrier = new CyclicBarrier(2);
        CountDownLatch doneLatch = new CountDownLatch(2);
        List<Device> resultA = new ArrayList<>();
        List<Device> resultB = new ArrayList<>();
        List<Throwable> failures = new java.util.concurrent.CopyOnWriteArrayList<>();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            executor.submit(() -> {
                try {
                    startBarrier.await();
                    resultA.addAll(deviceDiscoveryService.findAllRelatedDevicesByMO(
                            TENANT_A, group, new ArrayList<>(), false));
                } catch (Throwable t) {
                    failures.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
            executor.submit(() -> {
                try {
                    startBarrier.await();
                    resultB.addAll(deviceDiscoveryService.findAllRelatedDevicesByMO(
                            TENANT_B, group, new ArrayList<>(), false));
                } catch (Throwable t) {
                    failures.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });

            assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "Both discovery tasks must finish in time");
        } finally {
            executor.shutdownNow();
        }

        assertTrue(failures.isEmpty(), "No task may throw: " + failures);

        // L10 fixed: keys are now "tenant:deviceId" — two tenants with the same device id
        // use DIFFERENT keys so neither blocks the other. Both must find their device.
        boolean aHasDevice = resultA.stream().anyMatch(d -> DEVICE_ID_1.equals(d.getId()));
        boolean bHasDevice = resultB.stream().anyMatch(d -> DEVICE_ID_1.equals(d.getId()));
        assertTrue(aHasDevice, "L10 fixed: tenantA must find device-1 (no cross-tenant collision)");
        assertTrue(bHasDevice, "L10 fixed: tenantB must find device-1 (no cross-tenant collision)");

        // The circuit-breaker set must be fully drained after both tasks complete.
        Set<String> processing = readProcessingDevices();
        assertTrue(processing.isEmpty(),
                "processingDevices must be empty after both tasks complete, was: " + processing);
    }

    /**
     * L10 fixed: processingDevices keys are now "tenant:deviceId". Two tenants
     * sharing the same device id must NOT interfere — both calls must complete
     * and find their device, and the set must be empty afterwards.
     */
    @Test
    void findAllRelatedDevices_sameDeviceIdDifferentTenants_noFalseCollision() throws Exception {
        // A simple device (not a group) — no child traversal needed
        ManagedObjectRepresentation device = buildDevice(DEVICE_ID_1);

        // Sequential calls to isolate from concurrency noise
        List<Device> resultA = deviceDiscoveryService.findAllRelatedDevicesByMO(
                TENANT_A, device, new ArrayList<>(), true);
        List<Device> resultB = deviceDiscoveryService.findAllRelatedDevicesByMO(
                TENANT_B, device, new ArrayList<>(), true);

        assertTrue(resultA.stream().anyMatch(d -> DEVICE_ID_1.equals(d.getId())),
                "L10 fixed: tenantA must find device-1");
        assertTrue(resultB.stream().anyMatch(d -> DEVICE_ID_1.equals(d.getId())),
                "L10 fixed: tenantB must find device-1 independently");

        // Set must be drained — no stale composite keys
        assertTrue(readProcessingDevices().isEmpty(),
                "processingDevices must be empty after sequential calls");
    }

    @Test
    void findAllRelatedDevices_nullMo_returnsAccumulator() {
        // Sanity: a null MO returns the (possibly null) accumulator without NPE.
        List<Device> seed = new ArrayList<>(Arrays.asList(buildSimpleDevice(DEVICE_ID_1)));
        List<Device> result = deviceDiscoveryService.findAllRelatedDevicesByMO(TENANT_A, null, seed, false);
        assertEquals(1, result.size());
        assertFalse(result.isEmpty());
    }

    private Device buildSimpleDevice(String id) {
        Device device = new Device();
        device.setId(id);
        return device;
    }
}
