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
package dynamic.mapper.processor.flow;

import java.time.Instant;
import java.util.Map;

import lombok.Getter;
import lombok.Setter;

/**
 * A (Pulsar) message received from a device or sent to a device
 */
@Setter
@Getter
public class DeviceMessage {

    /**
     * Cloud IDP and first step of tedge always gets an ArrayBuffer, but might be a
     * JS object if passing intermediate messages between steps in thin-edge
     */
    private Object payload;

    /** External ID to lookup (and optionally create) */
    private Object externalSource; // ExternalSource[] | ExternalSource

    /** Identifier for the source/dest transport e.g. "mqtt", "opc-ua" etc. */
    private String transportId;

    /** The topic on the transport (e.g. MQTT topic) */
    private String topic;

    /** Transport/MQTT client ID */
    private String clientId;

    /** Set retain flag */
    private Boolean retain;

    /** Dictionary of transport/MQTT-specific fields/properties/headers */
    private Map<String, String> transportFields;

    /** Timestamp of incoming Pulsar message; does nothing when sending */
    private Instant time;

}
