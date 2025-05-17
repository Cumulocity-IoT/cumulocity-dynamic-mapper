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

import dynamic.mapping.connector.core.client.ConnectorType;
import dynamic.mapping.model.Direction;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

@Slf4j
@Data
@ToString()
@AllArgsConstructor
public class ConnectorSpecification implements Cloneable {

	@NotNull
	@JsonSetter(nulls = Nulls.SKIP)
	public String name;

	@NotNull
	@JsonSetter(nulls = Nulls.SKIP)
	public String description;

	@NotNull
	@JsonSetter(nulls = Nulls.SKIP)
	public ConnectorType connectorType;

	@NotNull
	@JsonSetter(nulls = Nulls.SKIP)
	public Map<String, ConnectorProperty> properties;

	@NotNull
	@JsonSetter(nulls = Nulls.SKIP)
	public boolean supportsMessageContext;

	@NotNull
	@JsonSetter(nulls = Nulls.SKIP)
    public List<Direction> supportedDirections;

	public boolean isPropertySensitive(String property) {
		try {
			ConnectorProperty propertyType = properties.get(property);
			if (propertyType != null) {
				return ConnectorPropertyType.SENSITIVE_STRING_PROPERTY == propertyType.type;
			} else {
				return false;
			}
		} catch (NullPointerException e) {
			log.error("NullPointerException occurred: ({}: {})",
					name,
					connectorType, e);
			return false;
		}
	}

	public Object clone() {
		try {
			return super.clone();
		} catch (CloneNotSupportedException e) {
			return null;
		}
	}
}
