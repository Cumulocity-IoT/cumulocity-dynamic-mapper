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
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Managed-object envelope for a {@link MappingVersion}, persisted in the
 * inventory under type {@value #MAPPING_VERSION_TYPE}. Parallel to
 * {@link MappingRepresentation}.
 */
@Slf4j
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MappingVersionRepresentation implements Serializable {

    public static final String MAPPING_VERSION_TYPE = "d11r_mapping_version";
    public static final String MAPPING_VERSION_FRAGMENT = "d11r_mapping_version";

    @JsonProperty("id")
    private String id;

    @JsonProperty("type")
    private String type;

    @JsonProperty(value = "name")
    private String name;

    @JsonProperty(value = "description")
    private String description;

    @JsonProperty(value = MAPPING_VERSION_FRAGMENT)
    private MappingVersion mappingVersion;
}
