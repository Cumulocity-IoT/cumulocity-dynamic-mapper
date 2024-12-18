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

export interface ConnectorStatusEvent {
  connectorIdent: string;
  connectorName: string;
  date?: string;
  status: ConnectorStatus;
  message: string;
  type: StatusEventTypes;
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
export enum StatusEventTypes {
  STATUS_CONNECTOR_EVENT_TYPE = 'd11r_connectorStatusEvent',
  STATUS_MAPPING_CHANGED_EVENT_TYPE = 'd11r_mappingChangedEvent',
  STATUS_SUBSCRIPTION_EVENT_TYPE = 'd11r_subscriptionEvent',
  STATUS_NOTIFICATION_EVENT_TYPE = 'd11r_notificationStatusEvent',
  STATUS_MAPPING_ACTIVATION_ERROR_EVENT_TYPE = 'STATUS_MAPPING_ACTIVATION_ERROR_EVENT_TYPE',
  ALL = 'ALL'
}
