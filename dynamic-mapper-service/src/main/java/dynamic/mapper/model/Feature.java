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
import lombok.Setter;
import lombok.ToString;

import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;

@Getter
@Setter
@Schema(description = "Feature flags for the dynamic mapping service")
@ToString()
public class Feature {
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Flag to check if outbound mapping is enabled or not", example = "true")
    @NotNull
    public boolean outputMappingEnabled;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Flag to check if external extensions are enabled or not", example = "true")
    @NotNull
    public boolean externalExtensionsEnabled;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Flag to check if the logged in user has the CREATE Role", example = "true")
    @NotNull
    public boolean userHasMappingCreateRole;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Flag to check if the logged in user has the ADMIN Role", example = "true")
    @NotNull
    public boolean userHasMappingAdminRole;
}