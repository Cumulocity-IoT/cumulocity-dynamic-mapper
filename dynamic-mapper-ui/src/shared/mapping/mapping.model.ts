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

import { ProcessingContext, TOKEN_IDENTITY } from './../../mapping/core/processor/processor.model';
import { EditorMode } from '../../mapping/shared/stepper.model';
import { ConnectorConfiguration } from '../connector-configuration/connector.model';

export interface MappingSubstitution {
  [x: string]: any;
  pathSource: string;
  pathTarget: string;
  repairStrategy: RepairStrategy;
  expandArray: boolean;
}

export interface DeploymentMapEntry {
  identifier: string;
  connectors: string[];
  connectorsDetailed?: ConnectorConfiguration[];
}

export interface DeploymentMap {
  [x: string]: DeploymentMapEntry;
}

export interface Mapping {
  [x: string]: any;
  id: string;
  identifier: string;
  name: string;
  publishTopic?: string;
  publishTopicSample?: string;
  mappingTopic?: string;
  mappingTopicSample?: string;
  targetAPI: string;
  direction: Direction;
  sourceTemplate: string;
  targetTemplate: string;
  mappingType: MappingType;
  substitutions?: MappingSubstitution[];
  filterMapping?: string;
  filterInventory?: string;
  maxFailureCount?: number;
  active: boolean;
  debug: boolean;
  tested: boolean;
  supportsMessageContext?: boolean;
  eventWithAttachment?: boolean;
  createNonExistingDevice: boolean;
  updateExistingDevice: boolean;
  autoAckOperation?: boolean;
  useExternalId: boolean;
  externalIdType: string;
  snoopStatus: SnoopStatus;
  snoopedTemplates?: string[];
  extension?: ExtensionEntry;
  qos: Qos;
  code?: string;
  lastUpdate: number;
}

export interface MappingEnriched {
  id: string;
  mapping: Mapping;
  connectors?: ConnectorConfiguration[];
  snoopSupported?: boolean;
}

export interface DeploymentMapEntryDetailed {
  identifier: string;
  connectors?: ConnectorConfiguration[];
}

export enum RepairStrategy {
  DEFAULT = 'DEFAULT',
  USE_FIRST_VALUE_OF_ARRAY = 'USE_FIRST_VALUE_OF_ARRAY',
  USE_LAST_VALUE_OF_ARRAY = 'USE_LAST_VALUE_OF_ARRAY',
  IGNORE = 'IGNORE',
  REMOVE_IF_MISSING_OR_NULL = 'REMOVE_IF_MISSING_OR_NULL',
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
  EXTENSION_SOURCE = 'EXTENSION_SOURCE',
  EXTENSION_SOURCE_TARGET = 'EXTENSION_SOURCE_TARGET',
}

export enum Qos {
  AT_MOST_ONCE = 'AT_MOST_ONCE',
  AT_LEAST_ONCE = 'AT_LEAST_ONCE',
  EXACTLY_ONCE = 'EXACTLY_ONCE'
}

export interface StepperConfiguration {
  showEditorSource?: boolean;
  showEditorTarget?: boolean;
  showProcessorExtensionsSource?: boolean;
  showProcessorExtensionsSourceTarget?: boolean;
  showProcessorExtensionsInternal?: boolean;
  showCodeEditor?: boolean;
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
  HEX = 'HEX',
  PROTOBUF_INTERNAL = 'PROTOBUF_INTERNAL',
  EXTENSION_SOURCE = 'EXTENSION_SOURCE',
  EXTENSION_SOURCE_TARGET = 'EXTENSION_SOURCE_TARGET',
  CODE_BASED = 'CODE_BASED'
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

export const MappingTypeDescriptionMap: Record<
  MappingType,
  MappingTypeDescriptionInterface
> = {
  [MappingType.JSON]: {
    key: MappingType.JSON,
    description: 'Mapping handles payloads in JSON format.',
    properties: {
      [Direction.INBOUND]: { snoopSupported: true, directionSupported: true },
      [Direction.OUTBOUND]: { snoopSupported: true, directionSupported: true }
    },
    stepperConfiguration: {
      showCodeEditor: false,
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
      showCodeEditor: false,
      showEditorSource: true,
      showEditorTarget: true,
      allowNoDefinedIdentifier: false,
      allowDefiningSubstitutions: true,
      showProcessorExtensionsSource: false,
      allowTestTransformation: true,
      allowTestSending: true
    }
  },
  [MappingType.HEX]: {
    key: MappingType.HEX,
    description: `Mapping handles payloads in hex format. In the mapper the incoming hexadecimal payload is decoded as hexadecimal string with a leading "0x". 
Use the JSONata function "$number() to parse an hexadecimal string as a number, e.g. $number("0x5a75") returns 23157.`,
    properties: {
      [Direction.INBOUND]: { snoopSupported: true, directionSupported: true },
      [Direction.OUTBOUND]: { snoopSupported: false, directionSupported: false }
    },
    stepperConfiguration: {
      showCodeEditor: false,
      showEditorSource: true,
      showEditorTarget: true,
      allowNoDefinedIdentifier: false,
      allowDefiningSubstitutions: true,
      showProcessorExtensionsSource: false,
      allowTestTransformation: true,
      allowTestSending: true
    }
  },
  [MappingType.PROTOBUF_INTERNAL]: {
    key: MappingType.PROTOBUF_INTERNAL,
    description: 'Mapping handles payloads in protobuf format.',
    properties: {
      [Direction.INBOUND]: { snoopSupported: false, directionSupported: true },
      [Direction.OUTBOUND]: { snoopSupported: false, directionSupported: false }
    },
    stepperConfiguration: {
      showProcessorExtensionsSource: false,
      showProcessorExtensionsInternal: true,
      allowDefiningSubstitutions: false,
      showCodeEditor: false,
      showEditorSource: false,
      showEditorTarget: true,
      allowNoDefinedIdentifier: true,
      allowTestTransformation: false,
      allowTestSending: false
    }
  },
  [MappingType.EXTENSION_SOURCE]: {
    key: MappingType.EXTENSION_SOURCE,
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
      showCodeEditor: false,
      showEditorSource: false,
      showEditorTarget: true,
      allowNoDefinedIdentifier: true,
      allowTestTransformation: false,
      allowTestSending: false,
      advanceFromStepToEndStep: 2
    }
  },
  [MappingType.EXTENSION_SOURCE_TARGET]: {
    key: MappingType.EXTENSION_SOURCE_TARGET,
    description:
      'Mapping handles payloads in custom format. In contrast to the EXTENSION_SOURCE the completed processing of the payload: extract values from the incoming payload and then transform this to a Cumulocity API call. This requires that a custom processor extension in Java is implemented and uploaded through the "Processor extension" tab.',
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
  },
  [MappingType.CODE_BASED]: {
    key: MappingType.CODE_BASED,
    description: 'Mapping handles payloads in JSON format and defines substitutions as code.',
    properties: {
      [Direction.INBOUND]: { snoopSupported: true, directionSupported: false },
      [Direction.OUTBOUND]: { snoopSupported: true, directionSupported: true }
    },
    stepperConfiguration: {
      showEditorSource: true,
      showEditorTarget: true,
      showCodeEditor: true,
      allowNoDefinedIdentifier: false,
      allowDefiningSubstitutions: true,
      showProcessorExtensionsSource: false,
      allowTestTransformation: true,
      allowTestSending: false
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
  identifier: string;
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
}export function getDeviceIdentifiers(mapping: Mapping): MappingSubstitution[] {
  const mp: MappingSubstitution[] = mapping.substitutions
    .filter(sub => definesDeviceIdentifier(mapping, sub));
  return mp;
}

export function getPathTargetForDeviceIdentifiers(context: ProcessingContext): string[] {
  const { mapping } = context;
  let pss;
  if (mapping.mappingType === MappingType.CODE_BASED) {
     pss = [getGenericDeviceIdentifier(mapping)];
  } else {
    pss = mapping.substitutions
      .filter(sub => definesDeviceIdentifier(mapping, sub))
      .map(sub => sub.pathTarget);
  }
  return pss;
}

export function transformGenericPath2C8YPath(mapping: Mapping, originalPath: string): string {
  // "_IDENTITY_.externalId" => source.id
  if (getGenericDeviceIdentifier(mapping) === originalPath) {
    return API[mapping.targetAPI].identifier;
  } else {
    return originalPath;
  }
}

export function transformC8YPath2GenericPath(mapping: Mapping, originalPath: string): string {
  // source.id => "_IDENTITY_.externalId" source.id
  if (API[mapping.targetAPI].identifier === originalPath) {
    return getGenericDeviceIdentifier(mapping);
  } else {
    return originalPath;
  }
}
export function cloneSubstitution(
  sub: MappingSubstitution
): MappingSubstitution {
  return {
    pathSource: sub.pathSource,
    pathTarget: sub.pathTarget,
    repairStrategy: sub.repairStrategy,
    expandArray: sub.expandArray,
  };
}
export function definesDeviceIdentifier(
  mapping: Mapping,
  sub: MappingSubstitution
): boolean {
  if (mapping.direction == Direction.INBOUND) {
    if (mapping.useExternalId) {
      return sub?.pathTarget == `${TOKEN_IDENTITY}.externalId`;
    } else {
      return sub?.pathTarget == `${TOKEN_IDENTITY}.c8ySourceId`;
    }
  } else {
    if (mapping.useExternalId) {
      return sub?.pathSource == `${TOKEN_IDENTITY}.externalId`;
    } else {
      return sub?.pathSource == `${TOKEN_IDENTITY}.c8ySourceId`;
    }
  }
}
export function isSubstitutionValid(mapping: Mapping): boolean {
  const count = mapping.substitutions
    .filter((sub) => definesDeviceIdentifier(mapping, sub)
    )
    .map(() => 1)
    .reduce((previousValue: number, currentValue: number) => {
      return previousValue + currentValue;
    }, 0);
  return (
    (mapping.direction != Direction.OUTBOUND && count == 1) ||
    mapping.direction == Direction.OUTBOUND
  );
}

export function countDeviceIdentifiers(mapping: Mapping): number {
  const n = mapping.substitutions.filter((sub) => definesDeviceIdentifier(mapping, sub)
  ).length;
  return n;
}
export function getGenericDeviceIdentifier(mapping: Mapping): string {
  if (mapping.useExternalId && mapping.externalIdType !== '') {
    return `${TOKEN_IDENTITY}.externalId`;
  } else {
    return `${TOKEN_IDENTITY}.c8ySourceId`;
  }
}

