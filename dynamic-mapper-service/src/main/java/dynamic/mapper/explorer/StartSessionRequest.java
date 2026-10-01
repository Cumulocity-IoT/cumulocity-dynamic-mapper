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

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request body for starting an explorer session.
 */
@Data
public class StartSessionRequest {
    @Schema(description = "Identifier of the inbound connector to listen on (required for INBOUND, ignored for OUTBOUND)", example = "mqtt-broker-01")
    private String connectorIdentifier;

    @NotBlank
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Topic to subscribe to (MQTT wildcards supported)", example = "sensors/#")
    private String topic;

    @Schema(description = "Maximum number of messages to buffer (1–500). Defaults to 50.", example = "50")
    private int maxMessages = 50;

    @Schema(description = "Direction to capture: INBOUND (broker → C8Y) or OUTBOUND (C8Y → broker). Defaults to INBOUND.",
            allowableValues = {"INBOUND", "OUTBOUND"}, example = "INBOUND")
    private String direction = "INBOUND";

    @Schema(description = "C8Y managed object ID (device or group) for outbound notifications (OUTBOUND only; required — without a source ID no Notification 2.0 subscription is created and no events will be captured).", example = "12345")
    private String sourceId;

    @Schema(description = "C8Y device type filter (OUTBOUND only; optional). When set, only messages from devices whose type matches this value are captured.", example = "c8y_MQTTDevice")
    private String deviceType;

    @Schema(description = "Session TTL in minutes. Overrides the tenant-wide default. Sessions expire when not polled for longer than this value.", example = "10")
    private Integer sessionTTLMinutes;
}
