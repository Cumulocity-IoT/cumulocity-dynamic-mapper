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

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import dynamic.mapper.configuration.ConnectorConfiguration;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.io.Serializable;
import java.util.ArrayList;

@Getter
@Setter
@NoArgsConstructor
@Schema(description = "Deployment entry showing which connectors a mapping is deployed to")
public class DeploymentMapEntry implements Serializable {
	
	public DeploymentMapEntry(String identifier) {
		this.identifier = identifier;
		this.connectors = new ArrayList<>();
	}

	@Schema(
		requiredMode = Schema.RequiredMode.REQUIRED,
		description = "Generated identifier for the mapping",
		example = "l19zjk"
	)
	@NotNull
	public String identifier;

	@Schema(
		requiredMode = Schema.RequiredMode.REQUIRED,
		description = "List of connector configurations where this mapping is deployed"
	)
	@NotNull
	public ArrayList<ConnectorConfiguration> connectors;
}