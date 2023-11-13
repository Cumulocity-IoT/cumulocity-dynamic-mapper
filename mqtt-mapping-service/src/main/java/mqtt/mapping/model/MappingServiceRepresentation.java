/*
 * Copyright (c) 2022 Software AG, Darmstadt, Germany and/or Software AG USA Inc., Reston, VA, USA,
 * and/or its subsidiaries and/or its affiliates and/or their licensors.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @authors Christof Strack, Stefan Witschel
 */

package mqtt.mapping.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import mqtt.mapping.core.ConnectorStatus;

import java.io.Serializable;
import java.util.ArrayList;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class  MappingServiceRepresentation implements Serializable  {

  public static final String CONNECTOR_STATUS_FRAGMENT = "d11r_connectorStatus";
  public static final String MAPPING_STATUS_FRAGMENT = "d11r_mappingStatus";
  public static final String AGENT_ID = "d11r_mappingService";
  public static final String AGENT_NAME = "Dynamic Mapping Service";

  @JsonProperty("id")
  private String id;

  @JsonProperty("type")
  private String type;

  @JsonProperty(value = "name")
  private String name;

  @JsonProperty(value = "description")
  private String description;

  @JsonProperty(value = MAPPING_STATUS_FRAGMENT)
  private ArrayList<MappingStatus> mappingStatus;

  @JsonProperty(value = CONNECTOR_STATUS_FRAGMENT)
  private ConnectorStatus connectorStatus;

}
