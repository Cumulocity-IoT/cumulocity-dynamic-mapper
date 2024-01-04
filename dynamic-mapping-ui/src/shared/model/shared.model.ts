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
export interface MappingSubstitution {
  pathSource: string;
  pathTarget: string;
  repairStrategy: RepairStrategy;
  expandArray: boolean;
  resolve2ExternalId: boolean;
}

export interface Mapping {
  name: string;
  id: string;
  ident: string;
  subscriptionTopic?: string;
  publishTopic?: string;
  templateTopic: string;
  templateTopicSample: string;
  targetAPI: string;
  source: string;
  target: string;
  active: boolean;
  tested: boolean;
  qos: QOS;
  substitutions?: MappingSubstitution[];
  mapDeviceIdentifier: boolean;
  createNonExistingDevice: boolean;
  updateExistingDevice: boolean;
  externalIdType: string;
  snoopStatus: SnoopStatus;
  snoopedTemplates?: string[];
  mappingType: MappingType;
  extension?: ExtensionEntry;
  direction?: Direction;
  filterOutbound?: string;
  autoAckOperation?: boolean;
  lastUpdate: number;
}

export enum RepairStrategy {
  DEFAULT = 'DEFAULT',
  USE_FIRST_VALUE_OF_ARRAY = 'USE_FIRST_VALUE_OF_ARRAY',
  USE_LAST_VALUE_OF_ARRAY = 'USE_LAST_VALUE_OF_ARRAY',
  IGNORE = 'IGNORE',
  REMOVE_IF_MISSING = 'REMOVE_IF_MISSING',
  REMOVE_IF_NULL = 'REMOVE_IF_NULL',
  CREATE_IF_MISSING = 'CREATE_IF_MISSING'
}

export enum Direction {
  INBOUND = 'INBOUND',
  OUTBOUND = 'OUTBOUND'
}

export enum SnoopStatus {
  NONE = 'NONE',
  ENABLED = 'ENABLED',
  STARTED = 'STARTED',
  STOPPED = 'STOPPED'
}

export interface ExtensionEntry {
  event: string;
  name: string;
  loaded?: boolean;
  message: string;
}

export enum QOS {
  AT_MOST_ONCE = 'AT_MOST_ONCE',
  AT_LEAST_ONCE = 'AT_LEAST_ONCE',
  EXACTLY_ONCE = 'EXACTLY_ONCE'
}

export enum MappingType {
  JSON = 'JSON',
  FLAT_FILE = 'FLAT_FILE',
  GENERIC_BINARY = 'GENERIC_BINARY',
  PROTOBUF_STATIC = 'PROTOBUF_STATIC',
  PROCESSOR_EXTENSION = 'PROCESSOR_EXTENSION'
}

export interface Extension {
  id?: string;
  name: string;
  extensionEntries: Map<string, ExtensionEntry>;
  loaded: boolean;
  external: boolean;
}

export enum ExtensionStatus {
  COMPLETE = 'COMPLETE',
  PARTIALLY = 'PARTIALLY',
  NOT_LOADED = 'NOT_LOADED',
  UNKNOWN = 'UNKNOWN'
}

export interface MappingStatus {
  id: number;
  name: string;
  ident: string;
  direction: Direction;
  subscriptionTopic: string;
  errors: number;
  messagesReceived: number;
  snoopedTemplatesTotal: number;
  snoopedTemplatesActive: number;
}

export const API = {
  ALARM: {
    name: 'ALARM',
    identifier: 'source.id',
    notificationFilter: 'alarms'
  },
  EVENT: {
    name: 'EVENT',
    identifier: 'source.id',
    notificationFilter: 'events'
  },
  MEASUREMENT: {
    name: 'MEASUREMENT',
    identifier: 'source.id',
    notificationFilter: 'measurements'
  },
  INVENTORY: {
    name: 'INVENTORY',
    identifier: 'id',
    notificationFilter: 'managedObjects'
  },
  OPERATION: {
    name: 'OPERATION',
    identifier: 'deviceId',
    notificationFilter: 'operations'
  },
  ALL: { name: 'ALL', identifier: '*', notificationFilter: '*' }
};
