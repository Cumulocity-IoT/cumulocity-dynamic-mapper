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
import { gettext } from '@c8y/ngx-components/gettext';
import { API, Direction, Extension, Mapping, MappingType, StepperConfiguration, Substitution, TransformationType } from './mapping.model';

export const TransformationTypeLabels = {
  [Direction.INBOUND]: {
    // eslint-disable-next-line @typescript-eslint/no-deprecated
    [TransformationType.DEFAULT]: 'Default Transformation (deprecated — use JSONata)',
    [TransformationType.SMART_FUNCTION]: 'Smart Function (JavaScript) to create Cumulocity API calls',
    [TransformationType.JSONATA]: 'Substitution as JSONata Expression',
    [TransformationType.EXTENSION_JAVA]: 'Java Extension (Smart Java Function)'
  },
  [Direction.OUTBOUND]: {
    // eslint-disable-next-line @typescript-eslint/no-deprecated
    [TransformationType.DEFAULT]: 'Default Transformation (deprecated — use JSONata)',
    [TransformationType.SMART_FUNCTION]: 'Smart Function (JavaScript) to create Broker Payload',
    [TransformationType.JSONATA]: 'Substitution as JSONata Expression',
    [TransformationType.EXTENSION_JAVA]: 'Java Extension (Smart Java Function)'
  }
} as const;

export const TransformationTypeDescriptions = {
  // eslint-disable-next-line @typescript-eslint/no-deprecated
  [TransformationType.DEFAULT]: 'Deprecated — uses the legacy substitution logic without custom processing. Use JSONata instead for new mappings.',
  [TransformationType.SMART_FUNCTION]: 'Executes a predefined Smart Function for data transformation and create payload for Cumulocity API calls. Supports setting sourceId to route data to different devices',
  [TransformationType.JSONATA]: 'Uses JSONata query and transformation language for data mapping',
  [TransformationType.EXTENSION_JAVA]: 'Java extension returns domain objects (CumulocityObject[] for inbound, DeviceMessage[] for outbound) using Smart Java Function pattern with builder syntax. Supports setting sourceId to route data to different devices'
} as const;

export const MappingTypeLabels = {
  [MappingType.JSON]: 'JSON Payload',
  [MappingType.FLAT_FILE]: 'Flat File Payload',
  [MappingType.HEX]: 'Hexadecimal Payload',
  // eslint-disable-next-line @typescript-eslint/no-deprecated -- legacy type still rendered for not-yet-migrated mappings
  [MappingType.PROTOBUF_INTERNAL]: 'PROTOBUF Payload (deprecated — use Any Payload)',
  // eslint-disable-next-line @typescript-eslint/no-deprecated
  [MappingType.EXTENSION_JAVA]: 'Payload parsed in Java Extension (deprecated — use Any Payload)',
  [MappingType.ANY_PAYLOAD]: 'Any Payload (e.g. SparkPlugB, Protobuf, XML)',
  [MappingType.SPARKPLUGB]: 'SparkPlug B Payload',
} as const;

export interface MappingTypeDescriptionInterface {
  description: string;
  enabled: boolean;
  /** Transformation types per direction; an empty list means the mapping type is not offered in that direction. */
  transformationTypes: Record<Exclude<Direction, Direction.UNSPECIFIED>, TransformationType[]>;
  stepperConfiguration: StepperConfiguration;
}

/**
 * Base defaults for all stepper configurations.
 * These represent the most common case (JSON inbound mapping).
 * All mapping types inherit from these defaults unless explicitly overridden.
 */
const BASE_STEPPER_CONFIGURATION: StepperConfiguration = {
  showCodeEditor: false,
  showEditorSource: true,
  showEditorTarget: true,
  showFilterExpression: true,
  allowNoDefinedIdentifier: false,
  allowDefiningSubstitutions: true,
  showProcessorExtensionsSource: false,
  showProcessorExtensionsTarget: false,
  showProcessorExtensionsInternal: false,
  allowTestTransformation: true,
  allowTestSending: true,
  allowTemplateExpansion: false,
  advanceFromStepToEndStep: undefined
};

/**
 * Shared by every mapping type whose payload is not edited as source/target JSON (binary or
 * decoded by code): no source editor, no filter, no substitution table, and the stepper jumps
 * straight from the properties step (index 2) to the end step.
 */
const CODE_DRIVEN_STEPPER_CONFIGURATION: Partial<StepperConfiguration> = {
  showEditorSource: false,
  showFilterExpression: false,
  allowDefiningSubstitutions: false,
  allowNoDefinedIdentifier: true,
  advanceFromStepToEndStep: 2
};

/**
 * Helper to create stepper configuration with only differences from base.
 * This ensures consistency and reduces duplication across mapping types.
 *
 * @param overrides - Properties that differ from base configuration
 * @returns Complete stepper configuration with base defaults and overrides applied
 */
function createStepperConfig(overrides: Partial<StepperConfiguration>): StepperConfiguration {
  return { ...BASE_STEPPER_CONFIGURATION, ...overrides };
}

export const MappingTypeDescriptionMap: Record<
  MappingType,
  MappingTypeDescriptionInterface
> = {
  [MappingType.JSON]: {
    enabled: true,
    description: gettext('Mapping handles payloads in JSON format.'),
    transformationTypes: {
      [Direction.INBOUND]: [TransformationType.JSONATA, TransformationType.SMART_FUNCTION],
      [Direction.OUTBOUND]: [TransformationType.JSONATA, TransformationType.SMART_FUNCTION, TransformationType.EXTENSION_JAVA],
    },
    // JSON uses base configuration as-is (no overrides needed)
    stepperConfiguration: createStepperConfig({})
  },
  [MappingType.FLAT_FILE]: {
    enabled: true,
    description: gettext(`Mapping handles payloads in CSV format. Any separator can be defined.\nUse the following expression to return the fields in an array.\nFor the expression $split(payload, /,\\s*/) the result is:
      [
        "165",
        "14.5",
        "2022-08-06T00:14:50.000+02:00",
        "c8y_FuelMeasurement"
      ]
      .`),
    transformationTypes: {
      [Direction.INBOUND]: [TransformationType.JSONATA, TransformationType.SMART_FUNCTION],
      // Direction not supported
      [Direction.OUTBOUND]: [],
    },
    // FLAT_FILE uses same config as JSON (no overrides needed)
    stepperConfiguration: createStepperConfig({})
  },
  [MappingType.HEX]: {
    enabled: true,
    description: gettext(`Mapping handles payloads in hex format. In the mapper the incoming hexadecimal payload is decoded as hexadecimal string with a leading "0x".
Use the JSONata function "$number() to parse an hexadecimal string as a number, e.g. $number("0x5a75") returns 23157.`),
    transformationTypes: {
      [Direction.INBOUND]: [TransformationType.JSONATA, TransformationType.SMART_FUNCTION],
      // Direction not supported
      [Direction.OUTBOUND]: [],
    },
    // HEX uses same config as JSON (no overrides needed)
    stepperConfiguration: createStepperConfig({})
  },
  // eslint-disable-next-line @typescript-eslint/no-deprecated -- legacy type still rendered for not-yet-migrated mappings
  [MappingType.PROTOBUF_INTERNAL]: {
    // Deprecated: not offered when creating a mapping, but still rendered for existing ones.
    enabled: false,
    description: gettext('Mapping parses payloads in PROTOBUF format by an internal extension.'),
    transformationTypes: {
      // Only the deprecated DEFAULT transformation is currently supported for Protobuf (internal) payloads.
      // eslint-disable-next-line @typescript-eslint/no-deprecated
      [Direction.INBOUND]: [TransformationType.DEFAULT],
      // Direction not supported
      [Direction.OUTBOUND]: [],
    },
    // PROTOBUF has significant differences: only specify what changes from base
    stepperConfiguration: createStepperConfig({
      ...CODE_DRIVEN_STEPPER_CONFIGURATION,
      showProcessorExtensionsInternal: true,
      allowTestTransformation: false,
      allowTestSending: false
    })
  },
  // eslint-disable-next-line @typescript-eslint/no-deprecated
  [MappingType.EXTENSION_JAVA]: {
    // Hidden from the creation UI — existing mappings are migrated to ANY_PAYLOAD on load.
    enabled: false,
    description: gettext('Deprecated — use Any Payload with Java Extension transformation type instead.'),
    transformationTypes: {
      [Direction.INBOUND]: [TransformationType.EXTENSION_JAVA],
      // Direction not supported
      [Direction.OUTBOUND]: [],
    },
    stepperConfiguration: createStepperConfig({
      ...CODE_DRIVEN_STEPPER_CONFIGURATION,
      showProcessorExtensionsSource: true
    })
  },
  [MappingType.ANY_PAYLOAD]: {
    enabled: true,
    description: gettext(
      'Payload format is unknown or binary (e.g. SparkPlugB, Protobuf, XML). ' +
      'Use a Smart Function (JavaScript) for in-process decoding, or a Java Extension for server-side processing.'),
    transformationTypes: {
      // Both JS Smart Function and Java Extension are supported for inbound any-payload mappings.
      [Direction.INBOUND]: [TransformationType.SMART_FUNCTION, TransformationType.EXTENSION_JAVA],
      // Direction not supported
      [Direction.OUTBOUND]: [],
    },
    // Code-driven base. The per-transformation overrides in stepper-configuration.strategy.ts
    // add the rest (target editor hidden, testing enabled, processor-extension selector for Java).
    stepperConfiguration: createStepperConfig(CODE_DRIVEN_STEPPER_CONFIGURATION)
  },
  [MappingType.SPARKPLUGB]: {
    enabled: true,
    description: gettext(
      'Payload is in SparkPlug B binary format (NBIRTH, NDATA, DBIRTH, DDATA, …). ' +
      'For inbound mappings the mapper decodes the protobuf payload automatically using the Eclipse Tahu library and ' +
      'passes a rich JSON object to your Smart Function. ' +
      'For outbound NCMD/DCMD mappings the metric object returned by your Smart Function is serialized ' +
      'to SparkPlug B protobuf binary before publishing. ' +
      'NBIRTH / DBIRTH metric definitions are stored on the managed object so that subsequent ' +
      'NDATA / DDATA messages can resolve metric aliases. ' +
      'The aliasMap (metric name → alias) is available in the outbound Smart Function via ' +
      'context.getConfig().aliasMap.'),
    transformationTypes: {
      // Only Smart Function is supported for SparkPlug B — the decoder handles binary parsing.
      [Direction.INBOUND]: [TransformationType.SMART_FUNCTION],
      // Only Smart Function is supported — the serializer handles binary SparkPlug B encoding.
      [Direction.OUTBOUND]: [TransformationType.SMART_FUNCTION],
    },
    // Smart Function is the only transformation; its override hides the target editor and enables testing.
    stepperConfiguration: createStepperConfig(CODE_DRIVEN_STEPPER_CONFIGURATION)
  }
};
