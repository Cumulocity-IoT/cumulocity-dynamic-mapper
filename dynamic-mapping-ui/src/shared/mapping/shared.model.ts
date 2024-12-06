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

import { EditorMode } from '../../mapping/shared/stepper-model';
import { ConnectorConfiguration } from '../connector-configuration/connector.model';

export interface MappingSubstitution {
  [x: string]: any;
  pathSource: string;
  pathTarget: string;
  repairStrategy: RepairStrategy;
  expandArray: boolean;
  resolve2ExternalId: boolean;
}

export interface DeploymentMapEntry {
  ident: string;
  connectors: string[];
  connectorsDetailed?: ConnectorConfiguration[];
}

export interface DeploymentMap {
  [x: string]: DeploymentMapEntry;
}

export interface Mapping {
  [x: string]: any;
  name: string;
  id: string;
  ident: string;
  publishTopic?: string;
  publishTopicSample?: string;
  mappingTopic?: string;
  mappingTopicSample?: string;
  targetAPI: string;
  sourceTemplate: string;
  targetTemplate: string;
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
  filterMapping?: string;
  autoAckOperation?: boolean;
  debug?: boolean;
  supportsMessageContext?: boolean;
  lastUpdate: number;
}

export interface MappingEnriched {
  id: string;
  mapping: Mapping;
  connectors?: ConnectorConfiguration[];
  snoopSupported?: boolean;
}

export interface DeploymentMapEntryDetailed {
  ident: string;
  connectors?: ConnectorConfiguration[];
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
  extensionName: string;
  eventName: string;
  fqnClassName?: string;
  loaded?: boolean;
  message?: string;
  extensionType: ExtensionType
}

export enum ExtensionType {
  PROCESSOR_EXTENSION_SOURCE = 'PROCESSOR_EXTENSION_SOURCE',
  PROCESSOR_EXTENSION_SOURCE_TARGET = 'PROCESSOR_EXTENSION_SOURCE_TARGET',
}

export enum QOS {
  AT_MOST_ONCE = 'AT_MOST_ONCE',
  AT_LEAST_ONCE = 'AT_LEAST_ONCE',
  EXACTLY_ONCE = 'EXACTLY_ONCE'
}

export interface StepperConfiguration {
  showEditorSource?: boolean;
  showEditorTarget?: boolean;
  showProcessorExtensionsSource?: boolean;
  showProcessorExtensionsSourceTarget?: boolean;
  editorMode?: EditorMode;
  allowNoDefinedIdentifier?: boolean;
  allowDefiningSubstitutions?: boolean;
  allowTestTransformation?: boolean;
  allowTestSending?: boolean;
  direction?: Direction;
  advanceFromStepToEndStep?: number;
}

export enum MappingType {
  JSON = 'JSON',
  FLAT_FILE = 'FLAT_FILE',
  GENERIC_BINARY = 'GENERIC_BINARY',
  PROTOBUF_STATIC = 'PROTOBUF_STATIC',
  PROCESSOR_EXTENSION_SOURCE = 'PROCESSOR_EXTENSION_SOURCE',
  PROCESSOR_EXTENSION_SOURCE_TARGET = 'PROCESSOR_EXTENSION_SOURCE_TARGET'
}

export interface MappingTypeProperties {
  snoopSupported: boolean;
  directionSupported: boolean;
}

export interface MappingTypeDescriptionInterface {
  key: MappingType;
  description: string;
  properties: Record<Direction, MappingTypeProperties>;
  stepperConfiguration: StepperConfiguration;
}

export const MAPPING_TYPE_DESCRIPTION: Record<
  MappingType,
  MappingTypeDescriptionInterface
> = {
  [MappingType.JSON]: {
    key: MappingType.JSON,
    description: 'Mapping handles payloads in JSON format.',
    properties: {
      [Direction.INBOUND]: { snoopSupported: true, directionSupported: true },
      [Direction.OUTBOUND]: { snoopSupported: false, directionSupported: true }
    },
    stepperConfiguration: {
      showEditorSource: true,
      showEditorTarget: true,
      allowNoDefinedIdentifier: false,
      allowDefiningSubstitutions: true,
      showProcessorExtensionsSource: false,
      allowTestTransformation: true,
      allowTestSending: true
    }
  },
  [MappingType.FLAT_FILE]: {
    key: MappingType.FLAT_FILE,
    description: `Mapping handles payloads in CSV format. Any separator can be defined./nUse the following expression to return the fields in an array.\nFor the expression $split(message, /,\\s*/) the result is:
			[
				"165",
				"14.5",
				"2022-08-06T00:14:50.000+02:00",
				"c8y_FuelMeasurement"
			]
			.`,
    properties: {
      [Direction.INBOUND]: { snoopSupported: true, directionSupported: true },
      [Direction.OUTBOUND]: { snoopSupported: false, directionSupported: false }
    },
    stepperConfiguration: {
      showEditorSource: true,
      showEditorTarget: true,
      allowNoDefinedIdentifier: false,
      allowDefiningSubstitutions: true,
      showProcessorExtensionsSource: false,
      allowTestTransformation: true,
      allowTestSending: true
    }
  },
  [MappingType.GENERIC_BINARY]: {
    key: MappingType.GENERIC_BINARY,
    description: `Mapping handles payloads in hex format. In the mapper the incoming hexadecimal payload is decoded as hexadecimal string with a leading "0x". 
Use the JSONata function "$number() to parse an hexadecimal string as a number, e.g. $number("0x5a75") returns 23157.`,
    properties: {
      [Direction.INBOUND]: { snoopSupported: true, directionSupported: true },
      [Direction.OUTBOUND]: { snoopSupported: false, directionSupported: false }
    },
    stepperConfiguration: {
      showEditorSource: true,
      showEditorTarget: true,
      allowNoDefinedIdentifier: false,
      allowDefiningSubstitutions: true,
      showProcessorExtensionsSource: false,
      allowTestTransformation: true,
      allowTestSending: true
    }
  },
  [MappingType.PROTOBUF_STATIC]: {
    key: MappingType.PROTOBUF_STATIC,
    description: 'Mapping handles payloads in protobuf format.',
    properties: {
      [Direction.INBOUND]: { snoopSupported: false, directionSupported: true },
      [Direction.OUTBOUND]: { snoopSupported: false, directionSupported: false }
    },
    stepperConfiguration: {
      showProcessorExtensionsSource: false,
      allowDefiningSubstitutions: false,
      showEditorSource: false,
      showEditorTarget: true,
      allowNoDefinedIdentifier: true,
      allowTestTransformation: false,
      allowTestSending: false
    }
  },
  [MappingType.PROCESSOR_EXTENSION_SOURCE]: {
    key: MappingType.PROCESSOR_EXTENSION_SOURCE,
    description:
      'Mapping handles payloads in custom format. It can be used if you want to process the message yourself. This requires that a custom processor extension in Java is implemented and uploaded through the "Processor extension" tab.',
    properties: {
      [Direction.INBOUND]: { snoopSupported: false, directionSupported: true },
      [Direction.OUTBOUND]: { snoopSupported: false, directionSupported: false }
    },
    stepperConfiguration: {
      showProcessorExtensionsSource: true,
      showProcessorExtensionsSourceTarget: false,
      allowDefiningSubstitutions: false,
      showEditorSource: false,
      showEditorTarget: true,
      allowNoDefinedIdentifier: true,
      allowTestTransformation: false,
      allowTestSending: false,
      advanceFromStepToEndStep: 2
    }
  },
  [MappingType.PROCESSOR_EXTENSION_SOURCE_TARGET]: {
    key: MappingType.PROCESSOR_EXTENSION_SOURCE_TARGET,
    description:
      'Mapping handles payloads in custom format. In contrast to the PROCESSOR_EXTENSION_SOURCE the completed processing of the payload: extract values from the incoming payload and then transform this to a Cumulocity API call. This requires that a custom processor extension in Java is implemented and uploaded through the "Processor extension" tab.',
    properties: {
      [Direction.INBOUND]: { snoopSupported: false, directionSupported: true },
      [Direction.OUTBOUND]: { snoopSupported: false, directionSupported: false }
    },
    stepperConfiguration: {
      showProcessorExtensionsSource: false,
      showProcessorExtensionsSourceTarget: true,
      allowDefiningSubstitutions: false,
      showEditorSource: false,
      showEditorTarget: false,
      allowNoDefinedIdentifier: true,
      allowTestTransformation: false,
      allowTestSending: false,
      advanceFromStepToEndStep: 2
    }
  }
};

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
  mappingTopic: string;
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
export interface Feature {
  outputMappingEnabled: boolean;
  externalExtensionsEnabled: boolean;
  userHasMappingCreateRole: boolean;
  userHasMappingAdminRole: boolean;
}
