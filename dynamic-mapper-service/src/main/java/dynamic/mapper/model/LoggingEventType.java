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

import lombok.Getter;

@Getter
public enum LoggingEventType {
    SUBSCRIPTION_EVENT_TYPE("SUBSCRIPTION_EVENT_TYPE", "d11r_subscriptionEvent", "d11r_connector",
            "Connector", "info", "Subscription lifecycle events for connectors"),
    CACHE_EVENT_TYPE("CACHE_EVENT_TYPE", "d11r_cacheEvent", "d11r_cache",
            "Cache", "info", "Cache event"),
    CONNECTOR_EVENT_TYPE("CONNECTOR_EVENT_TYPE", "d11r_connectorStatusEvent", "d11r_connector",
            "Connector", "info", "Connector status and connection events"),
    MAPPING_LOADING_ERROR_EVENT_TYPE("MAPPING_LOADING_ERROR_EVENT_TYPE", "d11r_mappingLoadingErrorEvent", "d11r_system",
            "System", "error", "Errors occurring during mapping configuration loading"),
    MAPPING_ACTIVATION_ERROR_EVENT_TYPE("MAPPING_ACTIVATION_ERROR_EVENT_TYPE", "d11r_mappingActivationErrorEvent", "d11r_mapping",
            "Mapping", "error", "Errors during mapping activation"),
    MAPPING_CHANGED_EVENT_TYPE("MAPPING_CHANGED_EVENT_TYPE", "d11r_mappingChangedEvent", "d11r_mapping",
            "Mapping", "info", "Mapping configuration change notifications"),
    MAPPING_MIGRATION_EVENT_TYPE("MAPPING_MIGRATION_EVENT_TYPE", "d11r_mappingMigrationEvent", "d11r_mapping",
            "Mapping", "info", "Automatic mapping migration notifications"),
    MAPPING_FAILURE_EVENT_TYPE("MAPPING_FAILURE_EVENT_TYPE", "d11r_mappingFailureEvent", "d11r_mapping",
            "Mapping", "error", "Mapping processing failures and errors"),
    NOTIFICATION_EVENT_TYPE("NOTIFICATION_EVENT_TYPE", "d11r_notificationStatusEvent", "d11r_connector",
            "Connector", "warning", "Notification connector status events"),
    ALL("ALL", "ALL", "d11r_AnyComponent",
            "All Components", "info", "All event types");

    public final String name;

    public final String type;

    public final String component;

    public final String componentDisplayName;

    public final String severity;

    public final String description;

    private LoggingEventType(String name, String type, String component,
                             String componentDisplayName, String severity, String description) {
        this.name = name;
        this.type = type;
        this.component = component;
        this.componentDisplayName = componentDisplayName;
        this.severity = severity;
        this.description = description;
    }
}