/*
 * Copyright (c) 2025 Cumulocity GmbH.
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
 *  @authors Christof Strack
 *
 */

package dynamic.mapper.explorer;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

@Data
@Builder
public class ExplorerSession {

    private String sessionId;
    private String userId;
    private String connectorIdentifier;
    private String connectorName;
    private String topic;
    private String tenant;

    /** Direction of messages to capture: "INBOUND" or "OUTBOUND". */
    private String direction;

    /** C8Y managed object ID (device or group) to filter outbound notifications (OUTBOUND only; null = required). */
    private String sourceId;

    /** Device type filter (OUTBOUND only; null = no type filter). Messages from devices whose C8Y type
     *  does not match this value are dropped. Ignored when sourceId is set. */
    private String deviceType;

    /** Device IDs subscribed via EXPLORER_DEVICE_SUBSCRIPTION when deviceType filter is used.
     *  A Set, not a List: {@link #isDeviceSubscribed(String)} is called on the per-message hot
     *  path for every captured outbound message, and a group can expand to hundreds of devices. */
    @Builder.Default
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private Set<String> subscribedDeviceIds = ConcurrentHashMap.newKeySet();

    /** Cache of sourceId → resolved C8Y device type to avoid repeated inventory lookups per session. */
    @Builder.Default
    private ConcurrentHashMap<String, String> deviceTypeCache = new ConcurrentHashMap<>();

    /** Maximum number of messages to retain (oldest are dropped once limit is reached). */
    private int maxMessages;

    /** Epoch millis of the last GET /messages call — used for TTL calculation. */
    private volatile long lastPolledAt;

    /** Per-session TTL in milliseconds, set at session start from the drawer input. */
    private long sessionTTLMs;

    /** Bounded message store; thread-safe. */
    private ConcurrentLinkedDeque<ExplorerMessage> messages;

    /** Set when the broker subscribe attempt for this session's topic failed (INBOUND only);
     *  {@code null} on success. Surfaced to the UI so a failed subscribe isn't silently reported
     *  as an "active" session that will never receive messages. */
    private String subscriptionWarning;

    /** Snapshot copy, safe to iterate while the session is running. Not for per-message use —
     *  see {@link #isDeviceSubscribed(String)}. */
    public List<String> getSubscribedDeviceIds() {
        return Collections.unmodifiableList(new ArrayList<>(subscribedDeviceIds));
    }

    public void setSubscribedDeviceIds(List<String> subscribedDeviceIds) {
        Set<String> replacement = ConcurrentHashMap.newKeySet();
        if (subscribedDeviceIds != null) {
            replacement.addAll(subscribedDeviceIds);
        }
        this.subscribedDeviceIds = replacement;
    }

    public void addSubscribedDeviceId(String deviceId) {
        this.subscribedDeviceIds.add(deviceId);
    }

    /** Allocation-free membership check for the per-message filter path. The null guard is
     *  required, not defensive: a captured message can carry no source ID, and the backing
     *  ConcurrentHashMap key set throws on a null lookup (unlike a List). */
    public boolean isDeviceSubscribed(String deviceId) {
        return deviceId != null && subscribedDeviceIds.contains(deviceId);
    }

    public boolean hasSubscribedDevices() {
        return !subscribedDeviceIds.isEmpty();
    }

    public int getSubscribedDeviceCount() {
        return subscribedDeviceIds.size();
    }
}
