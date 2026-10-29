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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

	@Schema(description = "True if the connector can verify connectivity/authentication of an (unsaved) configuration via the connector test endpoint.", example = "true")
	public boolean supportsConnectionTest;

	/**
	 * Convenience constructor for connectors that do not declare a QoS capability themselves —
	 * {@code supportedQos} is then stamped from the client instance
	 * ({@code AConnectorClient.getConnectorSpecification()}).
	 */
	public ConnectorSpecification(String name, String description, ConnectorType connectorType, boolean singleton,
			Map<String, ConnectorProperty> properties, boolean supportsMessageContext,
			List<Direction> supportedDirections) {
		this(name, description, connectorType, singleton, properties, supportsMessageContext, supportedDirections,
				null, false);
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

	/**
	 * The value a property effectively has: the configured value, or the property's default when the configuration
	 * has none. A blank string counts as "no value".
	 */
	private Object effectiveValue(String key, Map<String, Object> configured) {
		Object value = configured == null ? null : configured.get(key);
		if (value instanceof String string && string.isBlank()) {
			value = null;
		}
		if (value == null) {
			ConnectorProperty property = properties == null ? null : properties.get(key);
			value = property == null ? null : property.getDefaultValue();
		}
		return value;
	}

	/**
	 * Whether a property applies to the given configuration. A property without a condition always applies. A
	 * property with a condition applies when the property it depends on applies as well (so a field behind a hidden
	 * field is hidden too) and that property's effective value is one of {@code anyOf}. The value {@code "*"} in
	 * {@code anyOf} stands for "any non-empty value". Booleans compare as the strings {@code "true"} and
	 * {@code "false"}. The UI evaluates the same rule (see {@code isPropertyActive} in connector.model.ts).
	 */
	public boolean isPropertyActive(String key, Map<String, Object> configured) {
		return isPropertyActive(key, configured, new HashSet<>());
	}

	private boolean isPropertyActive(String key, Map<String, Object> configured, Set<String> visiting) {
		ConnectorProperty property = properties == null ? null : properties.get(key);
		if (property == null || property.getCondition() == null || property.getCondition().getAnyOf() == null) {
			return true;
		}
		ConnectorPropertyCondition condition = property.getCondition();
		if (!visiting.add(key)) {
			// A condition cycle can never be satisfied; the consistency test rejects such specifications.
			return false;
		}
		try {
			if (!isPropertyActive(condition.getKey(), configured, visiting)) {
				return false;
			}
			Object value = effectiveValue(condition.getKey(), configured);
			for (String candidate : condition.getAnyOf()) {
				if ("*".equals(candidate) ? value != null : value != null && candidate.equalsIgnoreCase(String.valueOf(value))) {
					return true;
				}
			}
			return false;
		} finally {
			visiting.remove(key);
		}
	}

	/**
	 * Names of the required properties that have neither a configured value nor a default. Properties that do not
	 * apply (see {@link #isPropertyActive}) are not required.
	 */
	public List<String> findMissingRequired(Map<String, Object> configured) {
		List<String> missing = new ArrayList<>();
		if (properties == null) {
			return missing;
		}
		properties.forEach((key, property) -> {
			if (Boolean.TRUE.equals(property.getRequired()) && isPropertyActive(key, configured)
					&& effectiveValue(key, configured) == null) {
				missing.add(key);
			}
		});
		return missing;
	}

	/**
	 * Removes the values of properties that do not apply to the configuration, e.g. a certificate trust flag that
	 * is still set after the protocol changed to one without TLS. The UI hides such a property, so the user cannot
	 * see or clear a stale value that would otherwise stay active in the connector.
	 *
	 * @return the keys that were removed
	 */
	public List<String> removeInactiveProperties(Map<String, Object> configured) {
		List<String> removed = new ArrayList<>();
		if (properties == null || configured == null) {
			return removed;
		}
		// decide on a snapshot, so removing one value does not change the outcome for the others
		Map<String, Object> snapshot = new java.util.HashMap<>(configured);
		for (String key : new ArrayList<>(configured.keySet())) {
			if (!isPropertyActive(key, snapshot)) {
				configured.remove(key);
				removed.add(key);
			}
		}
		return removed;
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
