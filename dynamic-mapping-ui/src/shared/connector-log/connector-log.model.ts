/*
 * Copyright (c) 2025 Cumulocity GmbH
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
 * @authors Christof Strack
 */

export interface ConnectorStatusEvent {
  connectorIdentifier: string;
  connectorName: string;
  date?: string;
  status: ConnectorStatus;
  message: string;
  type: string;
}

export enum ConnectorStatus {
  UNKNOWN = 'UNKNOWN',
  CONFIGURED = 'CONFIGURED',
  ENABLED = 'ENABLED',
  CONNECTING = 'CONNECTING',
  CONNECTED = 'CONNECTED',
  DISCONNECTED = 'DISCONNECTED',
  DISCONNECTING = 'DISCONNECTING',
  FAILED = 'FAILED'
}

export enum LoggingEventType {
  STATUS_SUBSCRIPTION_EVENT_TYPE = 'STATUS_SUBSCRIPTION_EVENT_TYPE',
  STATUS_CONNECTOR_EVENT_TYPE = 'STATUS_CONNECTOR_EVENT_TYPE',
  MAPPING_LOADING_ERROR_EVENT_TYPE = 'MAPPING_LOADING_ERROR_EVENT_TYPE',
  STATUS_MAPPING_ACTIVATION_ERROR_EVENT_TYPE = 'STATUS_MAPPING_ACTIVATION_ERROR_EVENT_TYPE',
  STATUS_MAPPING_CHANGED_EVENT_TYPE = 'STATUS_MAPPING_CHANGED_EVENT_TYPE',
  STATUS_NOTIFICATION_EVENT_TYPE = 'STATUS_NOTIFICATION_EVENT_TYPE',
  ALL = 'ALL'
}

export interface LoggingEventTypeDetails {
  name: string;
  type?: string;
  component: string;
}

export const LoggingEventTypeMap: Record<LoggingEventType, LoggingEventTypeDetails> = {
  [LoggingEventType.STATUS_SUBSCRIPTION_EVENT_TYPE]: {
    name: 'STATUS_SUBSCRIPTION_EVENT_TYPE',
    type: 'd11r_subscriptionEvent',
    component: 'd11r_connector'
  },
  [LoggingEventType.STATUS_CONNECTOR_EVENT_TYPE]: {
    name: 'STATUS_CONNECTOR_EVENT_TYPE',
    type: 'd11r_connectorStatusEvent',
    component: 'd11r_connector'
  },
  [LoggingEventType.MAPPING_LOADING_ERROR_EVENT_TYPE]: {
    name: 'MAPPING_LOADING_ERROR_EVENT_TYPE',
    type: 'd11r_mappingLoadingErrorEvent',
    component: 'd11r_mapping'
  },
  [LoggingEventType.STATUS_MAPPING_ACTIVATION_ERROR_EVENT_TYPE]: {
    name: 'STATUS_MAPPING_ACTIVATION_ERROR_EVENT_TYPE',
    type: 'd11r_mappingActivationErrorEvent',
    component: 'd11r_mapping'
  },
  [LoggingEventType.STATUS_MAPPING_CHANGED_EVENT_TYPE]: {
    name: 'STATUS_MAPPING_CHANGED_EVENT_TYPE',
    type: 'd11r_mappingChangedEvent',
    component: 'd11r_mapping'
  },
  [LoggingEventType.STATUS_NOTIFICATION_EVENT_TYPE]: {
    name: 'STATUS_NOTIFICATION_EVENT_TYPE',
    type: 'd11r_notificationStatusEvent',
    component: 'd11r_connector'
  },
  [LoggingEventType.ALL]: {
    name: 'ALL',
    type: 'ALL',
    component: 'd11r_AnyComponent'
  }
};

// Helper function to get details for a specific event type
export function getLoggingEventTypeDetails(eventType: LoggingEventType): LoggingEventTypeDetails {
  return LoggingEventTypeMap[eventType];
}
