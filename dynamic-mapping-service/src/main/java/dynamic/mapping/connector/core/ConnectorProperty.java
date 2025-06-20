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

import java.util.Map;

import jakarta.validation.constraints.NotNull;

@Data
@ToString()
@AllArgsConstructor
public class ConnectorProperty implements Cloneable {

    @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, description = "Property description", example = "MQTT Connector")
    @NotNull
	@JsonSetter(nulls = Nulls.SKIP)
	public String description;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Flag if the property is required or not", example = "true")
    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public Boolean required;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The order number starting from 0", example = "1")
    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public Integer order;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The type of the property", example = "ConnectorPropertyType.STRING")
    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public ConnectorPropertyType type;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Flag if the property is readonly", example = "true")
    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public Boolean readonly;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Flag if the property is hidden", example = "false")
    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public Boolean hidden;

    @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, description = "Default Value of the property", example = "2883")
    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public Object defaultValue;

    @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, description = "A map of additional options for a selection box", example = "Map.ofEntries(\n" +
            "                                new AbstractMap.SimpleEntry<String, String>(AConnectorClient.MQTT_PROTOCOL_MQTT,\n" +
            "                                        AConnectorClient.MQTT_PROTOCOL_MQTT),\n" +
            "                                new AbstractMap.SimpleEntry<String, String>(AConnectorClient.MQTT_PROTOCOL_MQTTS,\n" +
            "                                        AConnectorClient.MQTT_PROTOCOL_MQTTS),\n" +
            "                                new AbstractMap.SimpleEntry<String, String>(AConnectorClient.MQTT_PROTOCOL_WS,\n" +
            "                                        AConnectorClient.MQTT_PROTOCOL_WS),\n" +
            "                                new AbstractMap.SimpleEntry<String, String>(AConnectorClient.MQTT_PROTOCOL_WSS,\n" +
            "                                        AConnectorClient.MQTT_PROTOCOL_WSS)),")
    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public Map<String, String> options;

    @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, description = "A condition when the selected property should be enabled or not")
    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public ConnectorPropertyCondition condition;

    public Object clone() {
        try {
            return super.clone();
        } catch (CloneNotSupportedException e) {
            return null;
        }
    }
}
