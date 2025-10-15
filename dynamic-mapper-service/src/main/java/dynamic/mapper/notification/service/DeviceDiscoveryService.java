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

import com.cumulocity.rest.representation.inventory.ManagedObjectReferenceRepresentation;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.model.Device;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.util.*;

/**
 * Service for discovering devices and their relationships.
 * Handles device hierarchies (devices, child devices, groups).
 */
@Slf4j
@Service
public class DeviceDiscoveryService {

    private static final int MAX_RECURSION_DEPTH = 10;

    private ConfigurationRegistry configurationRegistry;

    @Autowired
    public void setConfigurationRegistry(@Lazy ConfigurationRegistry configurationRegistry) {
        this.configurationRegistry = configurationRegistry;
    }

    // Circuit breaker for preventing infinite recursion
    private final Set<String> processingDevices = Collections.synchronizedSet(new HashSet<>());
    private final ThreadLocal<Integer> recursionDepth = ThreadLocal.withInitial(() -> 0);

    // === Public API ===

    /**
     * Find all related devices starting from a ManagedObject.
     * Handles devices, child devices, and device groups.
     *
     * @param tenant       The tenant
     * @param mor          The starting ManagedObject
     * @param devices      Accumulator list (can be null)
     * @param isChildDevice Whether this is a child device call
     * @return List of discovered devices
     */
    public List<Device> findAllRelatedDevicesByMO(String tenant, ManagedObjectRepresentation mor,
            List<Device> devices, boolean isChildDevice) {

        if (mor == null) {
            return devices != null ? devices : new ArrayList<>();
        }

        // Check recursion depth to prevent stack overflow
        Integer depth = recursionDepth.get();
        if (depth >= MAX_RECURSION_DEPTH) {
            log.warn("{} - Maximum recursion depth reached at device {}", tenant, mor.getId().getValue());
            return devices != null ? devices : new ArrayList<>();
        }

        recursionDepth.set(depth + 1);

        try {
            if (devices == null) {
                devices = new ArrayList<>();
            }

            String deviceId = mor.getId().getValue();

            // Prevent infinite recursion with circular references
            if (processingDevices.contains(deviceId)) {
                log.debug("{} - Circular reference detected for device {}, skipping", tenant, deviceId);
                return devices;
            }

            processingDevices.add(deviceId);

            try {
                if (isDevice(mor, isChildDevice)) {
                    addDeviceIfNotExists(devices, mor);
                    processChildDevices(tenant, mor, devices);
                } else if (isDeviceGroup(mor)) {
                    processGroupAssets(tenant, mor, devices);
                } else {
                    log.debug("{} - ManagedObject {} is neither device nor group, skipping", tenant, deviceId);
                }
            } finally {
                processingDevices.remove(deviceId);
            }
        } finally {
            recursionDepth.set(depth);
        }

        return devices;
    }

    /**
     * Find all devices in a device group
     */
    public List<Device> findDevicesInGroup(String tenant, ManagedObjectRepresentation groupMO) {
        if (groupMO == null || !isDeviceGroup(groupMO)) {
            log.warn("{} - Invalid device group", tenant);
            return new ArrayList<>();
        }

        return findAllRelatedDevicesByMO(tenant, groupMO, new ArrayList<>(), false);
    }

    /**
     * Find child devices of a device
     */
    public List<Device> findChildDevices(String tenant, ManagedObjectRepresentation deviceMO) {
        if (deviceMO == null || !isDevice(deviceMO, false)) {
            log.warn("{} - Invalid device", tenant);
            return new ArrayList<>();
        }

        List<Device> devices = new ArrayList<>();
        processChildDevices(tenant, deviceMO, devices);
        return devices;
    }

    // === Private Helper Methods ===

    private boolean isDevice(ManagedObjectRepresentation mor, boolean isChildDevice) {
        return mor.hasProperty("c8y_IsDevice") || isChildDevice;
    }

    private boolean isDeviceGroup(ManagedObjectRepresentation mor) {
        return mor.hasProperty("c8y_IsDeviceGroup");
    }

    private void addDeviceIfNotExists(List<Device> devices, ManagedObjectRepresentation mor) {
        Device device = createDevice(mor);

        if (!devices.contains(device)) {
            devices.add(device);
            log.debug("Added device {} to discovery list", device.getId());
        }
    }

    private Device createDevice(ManagedObjectRepresentation mor) {
        Device device = new Device();
        device.setId(mor.getId().getValue());
        device.setName(mor.getName());
        device.setType(mor.getType());
        return device;
    }

    private void processChildDevices(String tenant, ManagedObjectRepresentation mor, List<Device> devices) {
        if (mor.getChildDevices() == null || mor.getChildDevices().getReferences() == null) {
            return;
        }

        for (ManagedObjectReferenceRepresentation childRef : mor.getChildDevices().getReferences()) {
            try {
                if (isValidManagedObjectRef(childRef)) {
                    ManagedObjectRepresentation child = configurationRegistry.getC8yAgent()
                            .getManagedObjectForId(tenant, childRef.getManagedObject().getId().getValue(), false);
                    if (child != null) {
                        findAllRelatedDevicesByMO(tenant, child, devices, true);
                    } else {
                        log.debug("{} - Child device {} not found", tenant, 
                                childRef.getManagedObject().getId().getValue());
                    }
                }
            } catch (Exception e) {
                String deviceId = extractDeviceId(childRef);
                log.warn("{} - Error processing child device {}: {}", tenant, deviceId, e.getMessage());
            }
        }
    }

    private void processGroupAssets(String tenant, ManagedObjectRepresentation mor, List<Device> devices) {
        if (mor.getChildAssets() == null || mor.getChildAssets().getReferences() == null) {
            return;
        }

        for (ManagedObjectReferenceRepresentation assetRef : mor.getChildAssets().getReferences()) {
            try {
                if (isValidManagedObjectRef(assetRef)) {
                    ManagedObjectRepresentation asset = configurationRegistry.getC8yAgent()
                            .getManagedObjectForId(tenant, assetRef.getManagedObject().getId().getValue(), false);
                    if (asset != null) {
                        findAllRelatedDevicesByMO(tenant, asset, devices, false);
                    } else {
                        log.debug("{} - Group asset {} not found", tenant,
                                assetRef.getManagedObject().getId().getValue());
                    }
                }
            } catch (Exception e) {
                String assetId = extractDeviceId(assetRef);
                log.warn("{} - Error processing group asset {}: {}", tenant, assetId, e.getMessage());
            }
        }
    }

    private boolean isValidManagedObjectRef(ManagedObjectReferenceRepresentation ref) {
        return ref != null &&
                ref.getManagedObject() != null &&
                ref.getManagedObject().getId() != null;
    }

    private String extractDeviceId(ManagedObjectReferenceRepresentation ref) {
        if (isValidManagedObjectRef(ref)) {
            return ref.getManagedObject().getId().getValue();
        }
        return "unknown";
    }

    @PreDestroy
    public void cleanup() {
        log.info("Cleaning up DeviceDiscoveryService");
        processingDevices.clear();
        recursionDepth.remove();
        log.info("DeviceDiscoveryService cleanup completed");
    }
}
