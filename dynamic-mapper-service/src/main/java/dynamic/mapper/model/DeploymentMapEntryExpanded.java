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

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Human-readable view of a deployment: mapping and connector names next to their identifiers.
 * Returned by the deployment endpoints when {@code expand=true}. Carries no connector
 * properties, so it cannot leak credentials.
 */
@Schema(description = "Deployment entry with mapping and connector names resolved")
public record DeploymentMapEntryExpanded(
		@Schema(description = "Generated identifier for the mapping", example = "l19zjk") String identifier,
		@Schema(description = "Mapping name, null if the mapping no longer exists", example = "Temperature Sensor Data Mapping") String name,
		@Schema(description = "Mapping direction, null if the mapping no longer exists") Direction direction,
		@Schema(description = "Connectors the mapping is deployed to") List<ConnectorRef> connectors) {

	@Schema(description = "Connector reference")
	public record ConnectorRef(
			@Schema(example = "as9zjk") String identifier,
			@Schema(description = "Connector name, null if the connector no longer exists", example = "MQTT Production Connector") String name,
			@Schema(example = "MQTT") String connectorType) {
	}
}
