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

package dynamic.mapping.model;

import lombok.Getter;

@Getter
public enum LoggingEventType {
    STATUS_SUBSCRIPTION_EVENT_TYPE("STATUS_SUBSCRIPTION_EVENT_TYPE", "d11r_subscriptionEvent", "d11r_connector"),
    STATUS_CONNECTOR_EVENT_TYPE("STATUS_CONNECTOR_EVENT_TYPE", "d11r_connectorStatusEvent", "d11r_connector"),
    MAPPING_LOADING_ERROR_EVENT_TYPE("MAPPING_LOADING_ERROR_EVENT_TYPE", "d11r_mappingLoadingErrorEvent", "d11r_mapping"),
    STATUS_MAPPING_ACTIVATION_ERROR_EVENT_TYPE("STATUS_MAPPING_ACTIVATION_ERROR_EVENT_TYPE", "d11r_mappingActivationErrorEvent", "d11r_mapping"),
    STATUS_MAPPING_CHANGED_EVENT_TYPE("STATUS_MAPPING_CHANGED_EVENT_TYPE", "d11r_mappingChangedEvent", "d11r_mapping"),
    STATUS_NOTIFICATION_EVENT_TYPE("STATUS_NOTIFICATION_EVENT_TYPE", "d11r_notificationStatusEvent", "d11r_connector"),
    ALL("ALL", "ALL", "d11r_AnyComponent");

    public final String name;

    public final String type;

    public final String component;

    private LoggingEventType(String name, String type, String component) {
        this.name = name;
        this.type = type;
        this.component = component;
    }
}