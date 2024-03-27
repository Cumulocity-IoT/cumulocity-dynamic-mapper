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
 * @authors Christof Strack
 */
export enum ConnectorPropertyType {
  STRING_PROPERTY = 'STRING_PROPERTY',
  SENSITIVE_STRING_PROPERTY = 'SENSITIVE_STRING_PROPERTY',
  NUMERIC_PROPERTY = 'NUMERIC_PROPERTY',
  BOOLEAN_PROPERTY = 'BOOLEAN_PROPERTY'
}

export interface ConnectorProperty {
  required: boolean;
  order: number;
  editable: boolean;
  defaultValue?: any;
  type: ConnectorPropertyType;
}

export interface ConnectorConfiguration {
  ident: string;
  connectorType: string;
  enabled: boolean;
  status?: any;
  status$?: any;
  name: string;
  properties: { [name: string]: any };
}

export interface ConnectorSpecification {
  connectorType: string;
  supportsWildcardInTopic: boolean;
  properties: { [name: string]: ConnectorProperty };
}

export interface ServiceConfiguration {
  logPayload: boolean;
  logSubstitution: boolean;
  logConnectorErrorInBackend: boolean;
  sendConnectorLifecycle: boolean;
  sendMappingStatus: boolean;
  sendSubscriptionEvents: boolean;
  sendNotificationLifecycle: boolean;
  externalExtensionEnabled?: boolean;
}

export interface ConnectorStatusEvent {
  status: ConnectorStatus;
  message: string;
}

export interface Feature {
  outputMappingEnabled: boolean;
  externalExtensionsEnabled: boolean;
  userHasMappingCreateRole: boolean;
  userHasMappingAdminRole: boolean;
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

export enum Operation {
  ACTIVATE_MAPPING = 'ACTIVATE_MAPPING',
  CONNECT = 'CONNECT',
  DISCONNECT = 'DISCONNECT',
  REFRESH_STATUS_MAPPING = 'REFRESH_STATUS_MAPPING',
  RELOAD_EXTENSIONS = 'RELOAD_EXTENSIONS',
  RELOAD_MAPPINGS = 'RELOAD_MAPPINGS',
  RESET_STATUS_MAPPING = 'RESET_STATUS_MAPPING',
  REFRESH_NOTIFICATIONS_SUBSCRIPTIONS = 'REFRESH_NOTIFICATIONS_SUBSCRIPTIONS'
}

export enum StatusEventTypes {
  STATUS_CONNECTOR_EVENT_TYPE = 'd11r_connectorStatusEvent',
  STATUS_SUBSCRIPTION_EVENT_TYPE = 'd11r_subscriptionEvent',
  STATUS_NOTIFICATION_EVENT_TYPE = 'd11r_notificationStatusEvent',
  ALL = 'ALL'
}
