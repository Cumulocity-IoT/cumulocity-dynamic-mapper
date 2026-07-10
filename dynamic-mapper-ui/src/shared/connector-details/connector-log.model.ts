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
  /** @deprecated the backend stopped populating this redundant, per-property date field; use `time` (the event's own timestamp) instead. */
  date?: string;
  time?: string;
  status: ConnectorStatus;
  message: string;
  type: string;
  severity?: 'info' | 'warning' | 'error';
  component?: string;
  componentDisplayName?: string;
  description?: string;
}

export enum ConnectorStatus {
  UNKNOWN = 'UNKNOWN',
  CONFIGURED = 'CONFIGURED',
  ENABLED = 'ENABLED',
  CONNECTING = 'CONNECTING',
  CONNECTED = 'CONNECTED',
  DISCONNECTED = 'DISCONNECTED',
  DISCONNECTING = 'DISCONNECTING',
  FAILED = 'FAILED',
  RETRYING = 'RETRYING'
}

export enum LoggingEventType {
  SUBSCRIPTION_EVENT_TYPE = 'SUBSCRIPTION_EVENT_TYPE',
  CACHE_EVENT_TYPE = 'CACHE_EVENT_TYPE',
  CONNECTOR_EVENT_TYPE = 'CONNECTOR_EVENT_TYPE',
  MAPPING_LOADING_ERROR_EVENT_TYPE = 'MAPPING_LOADING_ERROR_EVENT_TYPE',
  MAPPING_ACTIVATION_ERROR_EVENT_TYPE = 'MAPPING_ACTIVATION_ERROR_EVENT_TYPE',
  MAPPING_CREATED_EVENT_TYPE = 'MAPPING_CREATED_EVENT_TYPE',
  MAPPING_UPDATED_EVENT_TYPE = 'MAPPING_UPDATED_EVENT_TYPE',
  MAPPING_DELETED_EVENT_TYPE = 'MAPPING_DELETED_EVENT_TYPE',
  MAPPING_ACTIVATION_EVENT_TYPE = 'MAPPING_ACTIVATION_EVENT_TYPE',
  MAPPING_CHANGED_EVENT_TYPE = 'MAPPING_CHANGED_EVENT_TYPE',
  MAPPING_MIGRATION_EVENT_TYPE = 'MAPPING_MIGRATION_EVENT_TYPE',
  MAPPING_FAILURE_EVENT_TYPE = 'MAPPING_FAILURE_EVENT_TYPE',
  NOTIFICATION_EVENT_TYPE = 'NOTIFICATION_EVENT_TYPE',
  SUBSCRIPTION_DEDUPLICATION_EVENT_TYPE = 'SUBSCRIPTION_DEDUPLICATION_EVENT_TYPE',
  CODE_TEMPLATE_INIT_EVENT_TYPE = 'CODE_TEMPLATE_INIT_EVENT_TYPE',
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
    component: 'd11r_subscription',
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
  [LoggingEventType.MAPPING_CREATED_EVENT_TYPE]: {
    name: 'MAPPING_CREATED_EVENT_TYPE',
    type: 'd11r_mappingCreatedEvent',
    component: 'd11r_mapping',
    componentDisplayName: 'Mapping',
    severity: 'info',
    description: 'A mapping was created'
  },
  [LoggingEventType.MAPPING_UPDATED_EVENT_TYPE]: {
    name: 'MAPPING_UPDATED_EVENT_TYPE',
    type: 'd11r_mappingUpdatedEvent',
    component: 'd11r_mapping',
    componentDisplayName: 'Mapping',
    severity: 'info',
    description: "A mapping's configuration, filter, code, or debug flag was updated"
  },
  [LoggingEventType.MAPPING_DELETED_EVENT_TYPE]: {
    name: 'MAPPING_DELETED_EVENT_TYPE',
    type: 'd11r_mappingDeletedEvent',
    component: 'd11r_mapping',
    componentDisplayName: 'Mapping',
    severity: 'info',
    description: 'A mapping was deleted'
  },
  [LoggingEventType.MAPPING_ACTIVATION_EVENT_TYPE]: {
    name: 'MAPPING_ACTIVATION_EVENT_TYPE',
    type: 'd11r_mappingActivationEvent',
    component: 'd11r_mapping',
    componentDisplayName: 'Mapping',
    severity: 'info',
    description: 'A mapping was activated or deactivated'
  },
  [LoggingEventType.MAPPING_CHANGED_EVENT_TYPE]: {
    name: 'MAPPING_CHANGED_EVENT_TYPE',
    type: 'd11r_mappingChangedEvent',
    component: 'd11r_mapping',
    componentDisplayName: 'Mapping',
    severity: 'info',
    description: 'Bulk/batch mapping configuration change notifications not tied to a single mapping'
  },
  [LoggingEventType.MAPPING_MIGRATION_EVENT_TYPE]: {
    name: 'MAPPING_MIGRATION_EVENT_TYPE',
    type: 'd11r_mappingMigrationEvent',
    component: 'd11r_mapping',
    componentDisplayName: 'Mapping',
    severity: 'info',
    description: 'Automatic mapping migration notifications'
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
    component: 'd11r_notification',
    componentDisplayName: 'Connector',
    severity: 'warning',
    description: 'Notification connector status events'
  },
  [LoggingEventType.SUBSCRIPTION_DEDUPLICATION_EVENT_TYPE]: {
    name: 'SUBSCRIPTION_DEDUPLICATION_EVENT_TYPE',
    type: 'd11r_subscriptionDeduplicationEvent',
    component: 'd11r_subscriptionDeduplication',
    componentDisplayName: 'Connector',
    severity: 'info',
    description: 'Duplicate subscription removed to prevent multiply processed messages'
  },
  [LoggingEventType.CODE_TEMPLATE_INIT_EVENT_TYPE]: {
    name: 'CODE_TEMPLATE_INIT_EVENT_TYPE',
    type: 'd11r_codeTemplateInitEvent',
    component: 'd11r_system',
    componentDisplayName: 'System',
    severity: 'info',
    description: 'System code templates have been re-initialized'
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

/**
 * Resolves the `d11r_metadata` fragment for a raw Cumulocity event (component,
 * componentDisplayName, severity, description). Falls back to `LoggingEventTypeMap`
 * for legacy events created before `d11r_metadata` was added.
 */
export function getEventMetadata(event: { type?: string; [key: string]: any }): EventMetadata | null {
  const metadata = event?.['d11r_metadata'];
  if (metadata) {
    return metadata as EventMetadata;
  }

  const entry = Object.entries(LoggingEventTypeMap).find(
    ([, details]) => details.type === event?.type
  );
  if (entry && entry[1]) {
    return {
      component: entry[1].component || '',
      componentDisplayName: entry[1].componentDisplayName || 'Unknown',
      severity: entry[1].severity || 'info',
      description: entry[1].description || ''
    };
  }
  return null;
}

/** Bootstrap label class for a given event severity, shared across all views rendering these events. */
export function getSeverityBadgeClass(severity: string): string {
  switch (severity) {
    case 'error': return 'label-danger';
    case 'warning': return 'label-warning';
    case 'info':
    default: return 'label-primary';
  }
}
