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
 *  @authors Christof Strack, Stefan Witschel
 *
 */
package dynamic.mapper.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Maps a device ID to a client connector identifier for outbound message routing")
public class MapEntry {
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Cumulocity device managed object ID", example = "12345")
    private String id;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Connector client identifier associated with the device", example = "mqtt-broker-01")
    private String client;

    public MapEntry(String id, String client) {
        this.id = id;
        this.client = client;
    }

    // Getters and setters
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getClient() {
        return client;
    }

    public void setClient(String client) {
        this.client = client;
    }
}
