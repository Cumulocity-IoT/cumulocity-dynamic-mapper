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
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import dynamic.mapper.core.ConnectorStatusEvent;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MapperServiceRepresentation implements Serializable {

	public static final String AGENT_ID = "d11r_mappingService";
	public static final String AGENT_NAME = "Dynamic Mapper Service";
	public static final String AGENT_TYPE = "d11r_mappingService_type";
    public static final String MAPPING_FRAGMENT = "d11r_mapping";
    public static final String CONNECTOR_FRAGMENT = "d11r_connector";
    public static final String DEPLOYMENT_MAP_FRAGMENT = "d11r_deploymentMap";

	@JsonProperty("id")
	private String id;

	@JsonProperty("type")
	private String type;

	@JsonProperty(value = "name")
	private String name;

	@JsonProperty(value = "description")
	private String description;

	@JsonProperty(value = MapperServiceRepresentation.MAPPING_FRAGMENT)
	private List<MappingStatus> mappingStatus;

	@JsonProperty(value = MapperServiceRepresentation.CONNECTOR_FRAGMENT)
	private ConnectorStatusEvent connectorStatus;

	@JsonProperty(value = MapperServiceRepresentation.DEPLOYMENT_MAP_FRAGMENT)
	private Map<String, List<String>> deploymentMap;
}