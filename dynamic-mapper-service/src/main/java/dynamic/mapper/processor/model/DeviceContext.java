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

package dynamic.mapper.processor.model;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable context holding device identification and metadata information.
 *
 * Thread-safe by design - all fields are final and collections are immutable after construction.
 * Can be safely shared across multiple threads and processing operations.
 *
 * This context separates device concerns from other processing aspects,
 * making it clear which operations need device information.
 */
@Value
@Builder(toBuilder = true)
public class DeviceContext {
    /**
     * The Cumulocity internal device ID (managed object ID).
     * This is the numeric ID assigned by Cumulocity platform.
     */
    String sourceId;

    /**
     * The external device identifier used for device lookup.
     * This is the external ID value (e.g., serial number, IMEI).
     */
    String externalId;

    /**
     * The name of the device for display purposes.
     */
    String deviceName;

    /**
     * The type of the device (e.g., "c8y_Device", "c8y_Gateway").
     */
    String deviceType;

    /**
     * Additional managed object fragments to set on the implicitly created device.
     * Each key is a fragment name (e.g., "c8y_transportationProtocols") and the
     * value is the fragment content.
     */
    Map<String, Object> deviceFragments;

    /**
     * Names of device groups the implicitly created device should be assigned to
     * as a child asset (e.g., ["line 1", "line 2"]).
     * Groups are looked up by name; missing groups are created automatically.
     */
    List<String> deviceGroups;

    /**
     * Set of alarm types that have been raised for this device during processing.
     * Thread-safe: Immutable set created during build.
     *
     * Use @Singular to allow adding alarms one at a time during build:
     * DeviceContext.builder().alarm("c8y_TemperatureAlarm").alarm("c8y_BatteryLowAlarm").build()
     */
    @Singular
    Set<String> alarms;

    /**
     * Creates a copy of this context with an additional alarm.
     * Returns a new immutable DeviceContext with the alarm added.
     *
     * @param alarm the alarm type to add
     * @return a new DeviceContext with the alarm added
     */
    public DeviceContext withAlarm(String alarm) {
        Set<String> newAlarms = new HashSet<>(this.alarms != null ? this.alarms : Collections.emptySet());
        newAlarms.add(alarm);
        return this.toBuilder()
            .clearAlarms()  // Clear existing alarms in builder
            .alarms(Collections.unmodifiableSet(newAlarms))  // Add new immutable set
            .build();
    }

    /**
     * Creates a copy of this context with a different source ID.
     *
     * @param newSourceId the new source ID
     * @return a new DeviceContext with the updated source ID
     */
    public DeviceContext withSourceId(String newSourceId) {
        return this.toBuilder()
            .sourceId(newSourceId)
            .build();
    }

    /**
     * Creates a copy of this context with a different external ID.
     *
     * @param newExternalId the new external ID
     * @return a new DeviceContext with the updated external ID
     */
    public DeviceContext withExternalId(String newExternalId) {
        return this.toBuilder()
            .externalId(newExternalId)
            .build();
    }

    /**
     * Creates a copy of this context with a different device name.
     *
     * @param newDeviceName the new device name
     * @return a new DeviceContext with the updated device name
     */
    public DeviceContext withDeviceName(String newDeviceName) {
        return this.toBuilder()
            .deviceName(newDeviceName)
            .build();
    }

    /**
     * Creates a copy of this context with a different device type.
     *
     * @param newDeviceType the new device type
     * @return a new DeviceContext with the updated device type
     */
    public DeviceContext withDeviceType(String newDeviceType) {
        return this.toBuilder()
            .deviceType(newDeviceType)
            .build();
    }

    /**
     * Creates a copy of this context with device fragments.
     *
     * @param newDeviceFragments map of fragment name → fragment content
     * @return a new DeviceContext with the device fragments set
     */
    public DeviceContext withDeviceFragments(Map<String, Object> newDeviceFragments) {
        return this.toBuilder()
            .deviceFragments(newDeviceFragments)
            .build();
    }

    /**
     * Creates a copy of this context with device group names.
     *
     * @param newDeviceGroups list of group names to assign the device to
     * @return a new DeviceContext with the device groups set
     */
    public DeviceContext withDeviceGroups(List<String> newDeviceGroups) {
        return this.toBuilder()
            .deviceGroups(newDeviceGroups)
            .build();
    }

    /**
     * Checks if this device context has alarm information.
     *
     * @return true if at least one alarm is present
     */
    public boolean hasAlarms() {
        return alarms != null && !alarms.isEmpty();
    }

    /**
     * Checks if the device context has complete identification (both source and external IDs).
     *
     * @return true if both sourceId and externalId are present
     */
    public boolean hasCompleteIdentification() {
        return sourceId != null && !sourceId.isEmpty() &&
               externalId != null && !externalId.isEmpty();
    }

    /**
     * Gets an immutable view of the alarms set.
     * Safe to use even if null.
     *
     * @return immutable set of alarms, or empty set if null
     */
    public Set<String> getAlarms() {
        return alarms != null ? Collections.unmodifiableSet(alarms) : Collections.emptySet();
    }
}
