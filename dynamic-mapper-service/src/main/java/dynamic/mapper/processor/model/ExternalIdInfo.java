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

package dynamic.mapper.processor.model;

import java.util.List;

import lombok.Builder;
import lombok.Value;

/**
 * Value object to hold external ID information extracted from external sources.
 */
@Value
@Builder
public class ExternalIdInfo {
    String externalId;
    String externalType;

    /**
     * Extract external ID information from a list of external sources.
     * Takes the first external source if available.
     *
     * @param externalSources List of external sources
     * @return ExternalIdInfo with extracted information, or empty values if no sources provided
     */
    public static ExternalIdInfo from(List<ExternalId> externalSources) {
        if (externalSources == null || externalSources.isEmpty()) {
            return ExternalIdInfo.builder().build();
        }
        ExternalId source = externalSources.get(0);
        return ExternalIdInfo.builder()
                .externalId(source.getExternalId())
                .externalType(source.getType())
                .build();
    }

    /**
     * Check if external ID information is present
     *
     * @return true if both externalId and externalType are not null
     */
    public boolean isPresent() {
        return externalId != null && externalType != null;
    }
}
