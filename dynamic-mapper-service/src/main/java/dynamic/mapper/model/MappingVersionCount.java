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

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Lightweight projection returned by the bulk version-count endpoint.
 * Contains only the mapping MO id and the number of published versions,
 * avoiding the cost of loading full version records per mapping line.
 */
@Schema(description = "Mapping id with its published version count")
public record MappingVersionCount(

        @Schema(description = "Managed-object id of the mapping", example = "34573838974")
        String id,

        @Schema(description = "Number of published (non-draft) versions", example = "3")
        int versionCount) {
}
