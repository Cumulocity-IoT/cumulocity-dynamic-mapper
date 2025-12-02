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
package dynamic.mapper.configuration;

import jakarta.validation.constraints.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.ToString;

@Data
@ToString()
@AllArgsConstructor
@Schema(description = "Service configuration for the dynamic mapping service controlling logging, caching, extensions, and performance settings")
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
        this.deviceIsolationMQTTServiceEnabled = false;
        this.inboundExternalIdCacheSize = 0;
        this.inboundExternalIdCacheRetention = 1;
        this.inventoryCacheSize = 0;
        this.inventoryCacheRetention = 1;
        // Default fragments to cache: type, name, id
        this.inventoryFragmentsToCache = new ArrayList<>(Arrays.asList("type", "name", "id"));
        this.maxCPUTimeMS = 5000; // 5 seconds
        this.jsonataAgent = null;
        this.javaScriptAgent = null;

    }

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Enable logging of message payloads for debugging purposes. Caution: May expose sensitive data in logs.", example = "false")
    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    private Boolean logPayload;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Enable logging of field substitutions during mapping transformation for debugging.", example = "false")
    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    private Boolean logSubstitution;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Enable logging of connector errors in the backend system for monitoring and troubleshooting.", example = "false")
    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    private Boolean logConnectorErrorInBackend;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Enable sending connector lifecycle events (connect/disconnect) to Cumulocity IoT.", example = "false")
    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    private Boolean sendConnectorLifecycle;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Enable sending mapping execution status and statistics to Cumulocity IoT.", example = "true")
    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    private Boolean sendMappingStatus;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Enable sending subscription events when mappings are activated/deactivated.", example = "false")
    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    private Boolean sendSubscriptionEvents;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Enable sending notification lifecycle events for outbound mapping subscriptions.", example = "false")
    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    private Boolean sendNotificationLifecycle;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Enable support for external processor extensions that provide custom transformation capabilities.", example = "true")
    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    private Boolean externalExtensionEnabled;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Enable outbound mapping functionality for sending data from Cumulocity IoT to external systems.", example = "true")
    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    private Boolean outboundMappingEnabled;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Flag to check if the device isolation for messages on over the Cumulocity MQTT Service is enabled", example = "true")
    @NotNull
    private Boolean deviceIsolationMQTTServiceEnabled;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Size of the cache for inbound external ID lookups. Set to 0 to disable caching.", example = "1000", minimum = "0")
    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    private Integer inboundExternalIdCacheSize;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Retention time in hours for inbound external ID cache entries.", example = "24", minimum = "1")
    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    private Integer inboundExternalIdCacheRetention;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Size of the inventory cache for device lookups. Set to 0 to disable caching.", example = "500", minimum = "0")
    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    private Integer inventoryCacheSize;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Retention time in hours for inventory cache entries.", example = "12", minimum = "1")
    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    private Integer inventoryCacheRetention;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "List of inventory fragments to include in cache for better performance. Examples: c8y_IsDevice, c8y_Hardware, c8y_Mobile", example = "[\"c8y_IsDevice\", \"c8y_Hardware\", \"c8y_Mobile\"]")
    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    private List<String> inventoryFragmentsToCache;

    @Schema(description = "Map of code templates used for custom processing logic in mappings")
    @JsonProperty("codeTemplates")
    private Map<String, CodeTemplate> codeTemplates;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Maximum CPU time in milliseconds allowed for code execution in mappings. Prevents infinite loops and excessive processing.", example = "5000", minimum = "100", maximum = "30000")
    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    private Integer maxCPUTimeMS;

    @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, description = "Name of jsonata agent to be used when generating substitutions. The needs to be defined in the AI Agent Manager.", example = "jsonataAgent")
    @JsonSetter(nulls = Nulls.SKIP)
    private String jsonataAgent;

    @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, description = "Name of javaScript agent to be used when generating substitutions as JavaScript code. The needs to be defined in the AI Agent Manager.", example = "javaScriptAgent")
    @JsonSetter(nulls = Nulls.SKIP)
    private String javaScriptAgent;

    @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, description = "Name of javaScript SmartFunction agent to be used when generating Cumulocity API requests as JavaScript code. The needs to be defined in the AI Agent Manager.", example = "smartFunctionAgent")
    @JsonSetter(nulls = Nulls.SKIP)
    private String smartFunctionAgent;
}
