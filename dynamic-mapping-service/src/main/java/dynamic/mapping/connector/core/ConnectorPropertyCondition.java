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

package dynamic.mapping.connector.core;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.ToString;

import jakarta.validation.constraints.NotNull;

@Data
@ToString()
@AllArgsConstructor
public class ConnectorPropertyCondition implements Cloneable {

    // @NotNull
    // @JsonSetter(nulls = Nulls.SKIP)
    // public Integer order;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The key of the property", example = "protocol")
    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public String key;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The values the property should have", example = "{ AConnectorClient.MQTT_PROTOCOL_MQTTS, AConnectorClient.MQTT_PROTOCOL_WSS }")
    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public String[] anyOf;

    public Object clone() {
        try {
            return super.clone();
        } catch (CloneNotSupportedException e) {
            return null;
        }
    }
}
