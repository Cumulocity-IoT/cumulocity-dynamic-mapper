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

package dynamic.mapping.configuration;

import javax.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.ToString;

@Data
@ToString()
@AllArgsConstructor
public class ServiceConfiguration implements Cloneable {
    public ServiceConfiguration() {
        this.logPayload = false;
        this.logSubstitution = false;
        this.logConnectorErrorInBackend = false;
        this.sendConnectorLifecycle = false;
        this.sendMappingStatus = false;
        this.sendSubscriptionEvents = false;
        this.externalExtensionEnabled = true;
    }

    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public boolean logPayload;

    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public boolean logSubstitution;

    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public boolean logConnectorErrorInBackend;

    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public boolean sendConnectorLifecycle;

    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public boolean sendMappingStatus;

    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public boolean sendSubscriptionEvents;

    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public boolean externalExtensionEnabled;
}
