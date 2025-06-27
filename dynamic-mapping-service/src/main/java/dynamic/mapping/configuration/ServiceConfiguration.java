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

package dynamic.mapping.configuration;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
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
        this.sendMappingStatus = true;
        this.sendSubscriptionEvents = false;
        this.sendNotificationLifecycle = false;
        this.externalExtensionEnabled = true;
        this.outboundMappingEnabled = true;
        this.inboundExternalIdCacheSize = 0;
        this.inboundExternalIdCacheRetention = 1;
        this.inventoryCacheSize = 0;
        this.inventoryCacheRetention = 1;
        this.inventoryFragmentsToCache = new ArrayList<String>();
        this.maxCPUTimeMS = 5000;  // 5 seconds
    }

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "A flag to define if payload should be logged", example = "true")
    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public boolean logPayload;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "A flag to define if substitutions should be logged", example = "true")
    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public boolean logSubstitution;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "A flag to define if stack traces of connectors should logged", example = "true")
    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public boolean logConnectorErrorInBackend;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "A flag to define if the connector lifecycle should be sent as an event", example = "true")
    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public boolean sendConnectorLifecycle;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "A flag to define if the mapping status should be sent as an event", example = "true")
    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public boolean sendMappingStatus;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "A flag to define if the mapping subscriptions to connectors should be sent as an event", example = "true")
    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public boolean sendSubscriptionEvents;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "A flag to define if the notification lifecycle of devices should be sent as an event", example = "true")
    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public boolean sendNotificationLifecycle;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "A flag to define if external extensions are enabled or not", example = "true")
    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public boolean externalExtensionEnabled;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "A flag to define if outbound mapping is enabled or not", example = "true")
    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public boolean outboundMappingEnabled;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The size of the Inbound External ID Cache", example = "true")
    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public Integer inboundExternalIdCacheSize;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The retention in days", example = "true")
    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public Integer inboundExternalIdCacheRetention;

    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public Integer inventoryCacheSize;

    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public Integer inventoryCacheRetention;

    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public List<String> inventoryFragmentsToCache;

    @JsonProperty("codeTemplates")
    public Map<String, CodeTemplate> codeTemplates;

    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public Integer maxCPUTimeMS;
}
