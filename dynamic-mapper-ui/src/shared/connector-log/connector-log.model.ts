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
  SUBSCRIPTION_EVENT_TYPE = 'SUBSCRIPTION_EVENT_TYPE',
  CACHE_EVENT_TYPE = 'CACHE_EVENT_TYPE',
  CONNECTOR_EVENT_TYPE = 'CONNECTOR_EVENT_TYPE',
  MAPPING_LOADING_ERROR_EVENT_TYPE = 'MAPPING_LOADING_ERROR_EVENT_TYPE',
  MAPPING_ACTIVATION_ERROR_EVENT_TYPE = 'MAPPING_ACTIVATION_ERROR_EVENT_TYPE',
  MAPPING_CHANGED_EVENT_TYPE = 'MAPPING_CHANGED_EVENT_TYPE',
  MAPPING_FAILURE_EVENT_TYPE = 'MAPPING_FAILURE_EVENT_TYPE',
  NOTIFICATION_EVENT_TYPE = 'NOTIFICATION_EVENT_TYPE',
  ALL = 'ALL'
}

export interface LoggingEventTypeDetails {
  name: string;
  type?: string;
  component: string;
  componentDisplayName?: string;
  severity?: 'info' | 'warning' | 'error';
  description?: string;
}

export interface EventMetadata {
  component: string;
  componentDisplayName: string;
  severity: 'info' | 'warning' | 'error';
  description: string;
}

export const LoggingEventTypeMap: Record<LoggingEventType, LoggingEventTypeDetails> = {
  [LoggingEventType.SUBSCRIPTION_EVENT_TYPE]: {
    name: 'SUBSCRIPTION_EVENT_TYPE',
    type: 'd11r_subscriptionEvent',
    component: 'd11r_connector',
    componentDisplayName: 'Connector',
    severity: 'info',
    description: 'Subscription lifecycle events for connectors'
  },
    [LoggingEventType.CACHE_EVENT_TYPE]: {
    name: 'CACHE_EVENT_TYPE',
    type: 'd11r_cacheEvent',
    component: 'd11r_cache',
    componentDisplayName: 'Cache',
    severity: 'info',
    description: 'Cache event'
  },
  [LoggingEventType.CONNECTOR_EVENT_TYPE]: {
    name: 'CONNECTOR_EVENT_TYPE',
    type: 'd11r_connectorStatusEvent',
    component: 'd11r_connector',
    componentDisplayName: 'Connector',
    severity: 'info',
    description: 'Connector status and connection events'
  },
  [LoggingEventType.MAPPING_LOADING_ERROR_EVENT_TYPE]: {
    name: 'MAPPING_LOADING_ERROR_EVENT_TYPE',
    type: 'd11r_mappingLoadingErrorEvent',
    component: 'd11r_system',
    componentDisplayName: 'System',
    severity: 'error',
    description: 'Errors occurring during mapping configuration loading'
  },
  [LoggingEventType.MAPPING_ACTIVATION_ERROR_EVENT_TYPE]: {
    name: 'MAPPING_ACTIVATION_ERROR_EVENT_TYPE',
    type: 'd11r_mappingActivationErrorEvent',
    component: 'd11r_mapping',
    componentDisplayName: 'Mapping',
    severity: 'error',
    description: 'Errors during mapping activation'
  },
  [LoggingEventType.MAPPING_CHANGED_EVENT_TYPE]: {
    name: 'MAPPING_CHANGED_EVENT_TYPE',
    type: 'd11r_mappingChangedEvent',
    component: 'd11r_mapping',
    componentDisplayName: 'Mapping',
    severity: 'info',
    description: 'Mapping configuration change notifications'
  },
    [LoggingEventType.MAPPING_FAILURE_EVENT_TYPE]: {
    name: 'MAPPING_FAILURE_EVENT_TYPE',
    type: 'd11r_mappingFailureEvent',
    component: 'd11r_mapping',
    componentDisplayName: 'Mapping',
    severity: 'error',
    description: 'Mapping processing failures and errors'
  },
  [LoggingEventType.NOTIFICATION_EVENT_TYPE]: {
    name: 'NOTIFICATION_EVENT_TYPE',
    type: 'd11r_notificationStatusEvent',
    component: 'd11r_connector',
    componentDisplayName: 'Connector',
    severity: 'warning',
    description: 'Notification connector status events'
  },
  [LoggingEventType.ALL]: {
    name: 'ALL',
    type: 'ALL',
    component: 'd11r_AnyComponent',
    componentDisplayName: 'All Components',
    severity: 'info',
    description: 'All event types'
  }
};

// Helper function to get details for a specific event type
export function getLoggingEventTypeDetails(eventType: LoggingEventType): LoggingEventTypeDetails {
  return LoggingEventTypeMap[eventType];
}
