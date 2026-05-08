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

package dynamic.mapper.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@Schema(description = "A message captured by an explorer session from an inbound connector")
public class ExplorerMessage {

    @Schema(description = "The topic on which the message was received", example = "sensors/temperature/data")
    private String topic;

    @Schema(description = "Unique identifier of the connector that received the message", example = "mqtt-broker-01")
    private String connectorIdentifier;

    @Schema(description = "Display name of the connector", example = "MQTT Broker")
    private String connectorName;

    @Schema(description = "Epoch milliseconds when the message was received", example = "1715000000000")
    private long receivedAt;

    @Schema(description = "Message payload as UTF-8 string, or Base64-encoded for binary payloads")
    private String payload;

    @Schema(description = "true if the original payload was binary and has been Base64-encoded", example = "false")
    private boolean binary;
}
