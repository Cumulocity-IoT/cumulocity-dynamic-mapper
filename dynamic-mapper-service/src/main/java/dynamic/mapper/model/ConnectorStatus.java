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

public enum ConnectorStatus {
    UNKNOWN,
    CONFIGURED,
    ENABLED,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    DISCONNECTING,
    FAILED;

    /**
     * Severity to use for a logging event reporting this status, so a status change's severity
     * reflects the actual outcome rather than a value fixed per event type. DISCONNECTED/
     * DISCONNECTING are not treated as warning/error here since they're also reached via normal,
     * user-initiated shutdown flows, not only failures.
     */
    public String toSeverity() {
        return switch (this) {
            case FAILED -> "error";
            case UNKNOWN -> "warning";
            default -> "info";
        };
    }
}
