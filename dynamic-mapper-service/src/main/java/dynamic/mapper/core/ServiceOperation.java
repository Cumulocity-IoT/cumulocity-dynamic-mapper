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

package dynamic.mapper.core;

import java.util.HashMap;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Service operation request containing the operation type and parameters")
public class ServiceOperation {

    @Schema(description = "Tenant identifier (automatically set from context)", example = "t12345")
    private String tenant;
    
    @Schema(
        requiredMode = Schema.RequiredMode.REQUIRED, 
        description = "Type of operation to execute", 
        implementation = Operation.class,
        example = "RELOAD_MAPPINGS"
    )
    @NotNull
    private Operation operation;

    @Schema(
        description = "Parameters for the operation (varies by operation type)",
        example = "{\"connectorIdentifier\": \"jrr12x\", \"active\": \"true\"}"
    )
    private Map<String, String> parameter;

    public static ServiceOperation reloadMappings(String tenant) {
        return new ServiceOperation(tenant, Operation.RELOAD_MAPPINGS, null);
    }   
    
    public static ServiceOperation connect(String tenant, String connectorIdentifier) {
        HashMap<String, String> params = new HashMap<>();
        params.put("connectorIdentifier", connectorIdentifier);
        return new ServiceOperation(tenant, Operation.CONNECT, params);
    }
    
    public static ServiceOperation reloadExtensions(String tenant) {
        return new ServiceOperation(tenant, Operation.RELOAD_EXTENSIONS, null);
    } 
    
    public static ServiceOperation refreshNotificationSubscription(String tenant) {
        return new ServiceOperation(tenant, Operation.REFRESH_NOTIFICATIONS_SUBSCRIPTIONS, null);
    }
}
