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

package dynamic.mapper.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * Bundles all {@link ConnectorStatusEvent} transitions belonging to one connection-lifecycle
 * "session" (e.g. one connect attempt, possibly including transient retries, up to its terminal
 * outcome) into a single structure. Persisted as a fragment on a single Cumulocity Event that is
 * updated in place (PUT) as the session progresses, instead of creating one independent Event per
 * status transition — mirroring how a Cumulocity Operation accumulates a "history of changes"
 * under one record rather than one record per status update.
 */
@Data
@Schema(description = "Bundled history of ConnectorStatusEvent transitions for one connection-lifecycle session")
public class ConnectorStatusHistory implements Serializable {

    /**
     * Upper bound on {@link #history} size. A session normally has only a handful of entries
     * (see traced sequences in the connect-operation concept doc), but a flapping connection
     * (e.g. CONNECTED/RETRYING alternating for hours) could otherwise grow this unboundedly
     * before ever reaching a status that closes the session. When exceeded, the oldest entry is
     * dropped and {@link #historyTruncated} is set so the record doesn't silently look complete.
     */
    public static final int MAX_HISTORY_ENTRIES = 50;

    @Schema(description = "Display name of the connector", example = "MQTT Broker")
    private String connectorName;

    @Schema(description = "Unique identifier of the connector", example = "mqtt-broker-01")
    private String connectorIdentifier;

    @Schema(description = "Status of the most recent entry in history", implementation = ConnectorStatus.class)
    private ConnectorStatus currentStatus;

    @Schema(description = "True once the session has reached a terminal status (CONNECTED, DISCONNECTED, FAILED). "
            + "Re-evaluated on every append, so a session can re-open (e.g. CONNECTED -> RETRYING) without a new event.")
    private boolean sessionClosed;

    @Schema(description = "True if older entries were dropped to stay within MAX_HISTORY_ENTRIES")
    private boolean historyTruncated;

    @Schema(description = "Number of entries dropped due to the history cap")
    private int historyOmittedCount;

    @Schema(description = "Ordered list of status transitions belonging to this session")
    private List<ConnectorStatusEvent> history = new ArrayList<>();

    @Schema(description = "True if any transition in this session was error-driven (FAILED, RETRYING, "
            + "or carried a non-empty error message, e.g. an unexpected disconnect cause) rather than "
            + "a clean, intentional transition. Sticky: once set, stays true for the rest of the session.")
    private boolean hadError;

    /**
     * Appends a new transition, updating {@link #currentStatus} and applying the history cap.
     */
    public void append(ConnectorStatusEvent event) {
        history.add(event);
        currentStatus = event.getStatus();
        if (event.getStatus() == ConnectorStatus.FAILED || event.getStatus() == ConnectorStatus.RETRYING
                || (event.getMessage() != null && !event.getMessage().isEmpty())) {
            hadError = true;
        }
        if (history.size() > MAX_HISTORY_ENTRIES) {
            history.remove(0);
            historyTruncated = true;
            historyOmittedCount++;
        }
    }
}
