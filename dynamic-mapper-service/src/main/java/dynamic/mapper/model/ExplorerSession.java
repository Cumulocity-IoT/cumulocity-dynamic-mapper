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

import lombok.Builder;
import lombok.Data;

import java.util.concurrent.ConcurrentLinkedDeque;

@Data
@Builder
public class ExplorerSession {

    private String sessionId;
    private String connectorIdentifier;
    private String connectorName;
    private String topic;
    private String tenant;

    /** Direction of messages to capture: "INBOUND" or "OUTBOUND". */
    private String direction;

    /** C8Y managed object ID (device or group) to filter outbound notifications (OUTBOUND only; null = required). */
    private String sourceId;

    /** Maximum number of messages to retain (oldest are dropped once limit is reached). */
    private int maxMessages;

    /** Epoch millis of the last GET /messages call — used for TTL calculation. */
    private volatile long lastPolledAt;

    /** Bounded message store; thread-safe. */
    private ConcurrentLinkedDeque<ExplorerMessage> messages;
}
