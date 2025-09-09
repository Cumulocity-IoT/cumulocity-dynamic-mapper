/*
 * Copyright (c) 2025 Cumulocity GmbH.
 * SPDX-License-Identifier: Apache-2.0
 */

package dynamic.mapper.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import io.swagger.v3.oas.annotations.media.Schema;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request object for creating/updating client mappings")
public class ClientRelationRequest {
    
    @NotBlank(message = "Client ID cannot be blank")
    @Size(min = 1, max = 255, message = "Client ID must be between 1 and 255 characters")
    @Schema(description = "The client ID to map to the device", example = "mqtt-client-001", required = true)
    private String clientId;
}
