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

package mqtt.mapping.core;

import java.util.HashMap;
import java.util.Map;

import javax.validation.constraints.NotNull;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ServiceOperation {
    private String tenant;
    @NotNull
    private Operation operation;
    @NotNull
    private Map<String, String> parameter;

    public static ServiceOperation reloadMappings(String tenant) {
        return new ServiceOperation(tenant, Operation.RELOAD_MAPPINGS, null);
    }   
    public static ServiceOperation connect(String tenant, String connectorIdent) {
        HashMap<String, String> params = new HashMap<>();
        params.put("connectorIdent", connectorIdent);
        return new ServiceOperation(tenant, Operation.CONNECT, params);
    }
    public static ServiceOperation reloadExtensions(String tenant) {
        return new ServiceOperation(tenant, Operation.RELOAD_EXTENSIONS, null);
    } 
    public static ServiceOperation refreshNotificationSubscription(String tenant) {
        return new ServiceOperation(tenant, Operation.REFRESH_NOTFICATIONS_SUBSCRIPTIONS, null);
    }
}
