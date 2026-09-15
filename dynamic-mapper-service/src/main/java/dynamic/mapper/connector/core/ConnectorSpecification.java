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

package dynamic.mapper.connector.core;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

import dynamic.mapper.connector.core.client.ConnectorType;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Qos;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Data
@ToString()
@NoArgsConstructor
@AllArgsConstructor
public class ConnectorSpecification implements Cloneable {

	@Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The name of the connector", example = "MQTT Connector")
	@NotNull
	@JsonSetter(nulls = Nulls.SKIP)
	public String name;

	@Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "A description of the connector", example = "This is the MQTT Connector with the following features...")
	@NotNull
	@JsonSetter(nulls = Nulls.SKIP)
	public String description;

	@Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The type of the Connector", example = "MQTT")
	@NotNull
	@JsonSetter(nulls = Nulls.SKIP)
	public ConnectorType connectorType;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Is Connector singleton", example = "true")
	@NotNull
	@JsonSetter(nulls = Nulls.SKIP)
	public boolean singleton;

	@Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "A map of properties the connector needs to establish a connection. The key is the property name and the value is the property specification", example = "{ \"protocol\": { \"description\": \"The protocol to use\", \"required\": true, \"order\": 1, \"type\": \"STRING\", \"readonly\": false } }")
	@NotNull
	@JsonSetter(nulls = Nulls.SKIP)
	public Map<String, ConnectorProperty> properties;

	@Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "A flag to define if the connector supports message context. If true, the connector can handle additional metadata in messages.", example = "true")
	@NotNull
	@JsonSetter(nulls = Nulls.SKIP)
	public boolean supportsMessageContext;

	@Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "A List to define if the connector support INBOUND and OUTBOUND mappings or both.", example = "[ \"INBOUND\", \"OUTBOUND\"] ")
	@NotNull
	@JsonSetter(nulls = Nulls.SKIP)
    public List<Direction> supportedDirections;

	@Schema(description = "The QoS levels this connector can actually honour. A mapping configured with a stronger level is clamped to the strongest level in this list when it runs on this connector.", example = "[ \"AT_MOST_ONCE\", \"AT_LEAST_ONCE\"] ")
	@JsonSetter(nulls = Nulls.SKIP)
	public List<Qos> supportedQos;

	/**
	 * Convenience constructor for connectors that do not declare a QoS capability themselves —
	 * {@code supportedQos} is then stamped from the client instance
	 * ({@code AConnectorClient.getConnectorSpecification()}).
	 */
	public ConnectorSpecification(String name, String description, ConnectorType connectorType, boolean singleton,
			Map<String, ConnectorProperty> properties, boolean supportsMessageContext,
			List<Direction> supportedDirections) {
		this(name, description, connectorType, singleton, properties, supportsMessageContext, supportedDirections,
				null);
	}

	public boolean isPropertySensitive(String property) {
		if (properties == null || property == null) {
			log.warn("{} - Cannot check property sensitivity: properties={}, property={}",
					name, properties, property);
			return false;
		}

		ConnectorProperty propertyType = properties.get(property);
		if (propertyType == null || propertyType.type == null) {
			return false;
		}

		return ConnectorPropertyType.SENSITIVE_STRING_PROPERTY == propertyType.type
				|| ConnectorPropertyType.SENSITIVE_STRING_LARGE_PROPERTY == propertyType.type;
	}

	@Override
	public ConnectorSpecification clone() {
		try {
			ConnectorSpecification cloned = (ConnectorSpecification) super.clone();
			// Deep clone mutable collections
			if (this.properties != null) {
				cloned.properties = new java.util.LinkedHashMap<>();
				for (Map.Entry<String, ConnectorProperty> entry : this.properties.entrySet()) {
					cloned.properties.put(entry.getKey(),
							entry.getValue() != null ? entry.getValue().clone() : null);
				}
			}
			if (this.supportedDirections != null) {
				cloned.supportedDirections = new java.util.ArrayList<>(this.supportedDirections);
			}
			if (this.supportedQos != null) {
				cloned.supportedQos = new java.util.ArrayList<>(this.supportedQos);
			}
			return cloned;
		} catch (CloneNotSupportedException e) {
			throw new AssertionError("Cloning failed for ConnectorSpecification", e);
		}
	}
}
