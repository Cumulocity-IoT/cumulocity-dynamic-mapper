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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * An immutable snapshot of a {@link Mapping}'s full configuration at a point in
 * time, plus version metadata. Every published version of a mapping line is
 * stored as one of these (managed object type {@code d11r_mapping_version}),
 * including the currently active one. The single mutable working copy of a
 * mapping line is also stored as a {@code MappingVersion} with {@link #isDraft}
 * set to {@code true}.
 *
 * <p>See {@code docs/feature/REQUIREMENTS-VERSION-MAPPING.md} (D-1, D-7).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Immutable snapshot of a mapping configuration together with its version metadata")
public class MappingVersion implements Serializable {

    @Schema(description = "Managed-object id of this version record, assigned by Cumulocity Core", example = "34573838974")
    private String id;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Functional identifier of the owning mapping line", example = "l19zjk")
    @NotNull
    private String identifier;

    @Schema(description = "Semantic version (MAJOR.MINOR.PATCH), unique within the mapping line; null for the draft", example = "1.2.0")
    private String version;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Immutable copy of the full mapping configuration for this version")
    @NotNull
    private Mapping snapshot;

    @Builder.Default
    @Schema(description = "Whether this record is the mutable draft (true) or a published immutable version (false)", example = "false")
    private boolean isDraft = false;

    @Schema(description = "Timestamp the version was published (epoch millis)", example = "1640995200000")
    private long createdAt;

    @Schema(description = "User who published the version", example = "admin")
    private String createdBy;

    @Schema(description = "Optional free-text change note", example = "Initial version")
    private String note;
}
