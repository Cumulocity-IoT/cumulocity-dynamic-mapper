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

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;
import dynamic.mapper.processor.extension.ProcessorExtensionInbound;
import dynamic.mapper.processor.extension.ProcessorExtensionOutbound;

import jakarta.validation.constraints.NotNull;
import java.io.Serializable;

@Getter
@Setter
@NoArgsConstructor
@ToString
@Builder
@AllArgsConstructor
public class ExtensionEntry implements Serializable {

    @NotNull
    private String extensionName;

    @NotNull
    private String eventName;
    
    @NotNull
    private String fqnClassName;
    
    @NotNull
    private Boolean loaded;
    
    @NotNull
    private String message;

    @NotNull
    private ExtensionType extensionType;

    @NotNull
    @Builder.Default
    private Direction direction = Direction.UNSPECIFIED;

    /**
     * Substitution-based extension (InboundExtension or OutboundExtension).
     * For substitution-based processing only.
     */
    @JsonIgnore
    @Builder.Default
    private Object extensionImplSource = null;

    /**
     * Complete inbound processing extension (ProcessorExtensionInbound).
     * For complete processing with C8YAgent access.
     */
    @NotNull
    @JsonIgnore
    @Builder.Default
    private ProcessorExtensionInbound<?> extensionImplInbound = null;

    /**
     * Complete outbound processing extension (ProcessorExtensionOutbound).
     * For complete processing with request preparation.
     */
    @NotNull
    @JsonIgnore
    @Builder.Default
    private ProcessorExtensionOutbound<?> extensionImplOutbound = null;

}
