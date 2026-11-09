/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
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
import * as _ from 'lodash';
import { API, Direction, Mapping, TransformationType } from './mapping.model';
import { MappingTokens } from './processor/processor.constants';
import { randomIdAsString } from './random-id';
import { CONTEXT_DATA_KEY_NAME, CONTEXT_DATA_METHOD_NAME, CONTEXT_DATA_PUBLISH_TOPIC, CONTEXT_DATA_RETAIN } from './util-constants';

// ─── TransformationType predicates ───────────────────────────────────────────

export function checkTransformationType(transformationType: TransformationType, template: any): boolean {
  // Check if template is JSONArray - in this case transformationType must be TransformationType.SMART_FUNCTION

  if (isJSONArray(template)) {
    return transformationType === TransformationType.SMART_FUNCTION;
  }

  return true;
}

function isJSONArray(value: any): boolean {
  // Check if already a parsed array
  if (Array.isArray(value)) {
    return true;
  }

  // Check if it's a string representation of a JSON array
  if (typeof value === 'string') {
    const trimmed = value.trim();

    if (!trimmed || !trimmed.startsWith('[')) {
      return false;
    }

    try {
      const parsed = JSON.parse(trimmed);
      return Array.isArray(parsed);
    } catch (e) {
      return false;
    }
  }

  return false;
}

export function isCodeOrExtensionTransformation(transformationType: TransformationType): boolean {
  return transformationType === TransformationType.SMART_FUNCTION
    || transformationType === TransformationType.EXTENSION_JAVA;
}

// ─── Template expansion ───────────────────────────────────────────────────────

export function expandExternalTemplate(
  template: object,
  mapping: Mapping,
  levels: string[]
): object {
  if (Array.isArray(template) || isCodeOrExtensionTransformation(mapping.transformationType)) {
    return template;
  } else {
    // Define the context data with specific values. A key already present in the template (e.g.
    // seeded from a real message captured in the Message Explorer) is preferred over the generic
    // placeholder, so the sample mirrors what the mapping actually receives at runtime.
    const existingKey = template?.[MappingTokens.CONTEXT_DATA]?.[CONTEXT_DATA_KEY_NAME];
    let contextData;
    if (mapping.direction == Direction.INBOUND) {
      contextData = {
        [CONTEXT_DATA_KEY_NAME]: existingKey ?? `${CONTEXT_DATA_KEY_NAME}-sample`,
        // [CONTEXT_DATA_METHOD_NAME]: "POST"
        // [CONTEXT_DATA_RETAIN]: false,
      };
    } else {
      contextData = {
        [CONTEXT_DATA_KEY_NAME]: `${CONTEXT_DATA_KEY_NAME}-sample`,
        [CONTEXT_DATA_METHOD_NAME]: "POST", // Set to "POST" instead of a generated value
        [CONTEXT_DATA_RETAIN]: false,
        [CONTEXT_DATA_PUBLISH_TOPIC]: mapping.publishTopic,
      }
    };
    return {
      ...template,
      _TOPIC_LEVEL_: levels,
      _CONTEXT_DATA_: contextData
    };

  }
}

export function expandC8YTemplate(template: object, mapping: Mapping): object {
  if (isCodeOrExtensionTransformation(mapping.transformationType)) {
    return template;
  }
  let result;
  if (mapping.useExternalId) {
    result = {
      ...template,
      _IDENTITY_: {
        // externalIdType: mapping.externalIdType,
        externalId: 'any_SerialNumber',
        // c8ySourceId: '909090'
      }
    };
  } else {
    result = {
      ...template,
      [MappingTokens.IDENTITY]: {
        c8ySourceId: '909090'
      }
    };
  }
  if (mapping.direction == Direction.INBOUND) {
    result = {
      ...result,
      [MappingTokens.CONTEXT_DATA]: {
        'api': mapping.targetAPI,
        'processingMode': 'PERSISTENT'
      }
    };

    if (mapping.createNonExistingDevice) {
      result = {
        ...result,
        [MappingTokens.CONTEXT_DATA]: {
          ...result[MappingTokens.CONTEXT_DATA], // Spread existing properties
          'deviceName': 'generatedDevice',
          'deviceType': 'c8y_GeneratedDeviceType'
        }
      };
    }

    // Handle attachment properties independently
    if (mapping.eventWithAttachment) {
      // Initialize [MappingTokens.CONTEXT_DATA] if it doesn't exist yet
      if (!result[MappingTokens.CONTEXT_DATA]) {
        result[MappingTokens.CONTEXT_DATA] = {};
      }

      // Add attachment properties
      result[MappingTokens.CONTEXT_DATA].attachmentName = 'TestImage.jpeg';
      result[MappingTokens.CONTEXT_DATA].attachmentType = 'image/jpeg';
      result[MappingTokens.CONTEXT_DATA].attachmentData = '';
    }
  }

  if (mapping.direction == Direction.OUTBOUND) {
    result[MappingTokens.IDENTITY].c8ySourceId = '909090';
  }

  return result;
}

// ─── Template testing ─────────────────────────────────────────────────────────

export function patchC8YTemplateForTesting(template: object, mapping: Mapping) {
  const identifier = randomIdAsString();
  _.set(template, API[mapping.targetAPI].identifier, identifier);
  // For SMART_FUNCTION and EXTENSION_JAVA the source template must stay clean (allowSourceExpansion=false):
  // no MappingTokens should appear in source or target templates
  if (!isCodeOrExtensionTransformation(mapping.transformationType)) {
    _.set(template, `${MappingTokens.IDENTITY}.c8ySourceId`, identifier);
    _.set(template, `${MappingTokens.CONTEXT_DATA}.publishTopic`, mapping.publishTopic);
  }
}

// ─── Template reduction ───────────────────────────────────────────────────────

export function reduceSourceTemplate(
  template: object,
  returnPatched: boolean
): string {
  if (!returnPatched) {
    delete template[MappingTokens.IDENTITY];
    delete template[MappingTokens.CONTEXT_DATA];
    delete template[MappingTokens.TOPIC_LEVEL];
  }
  const tt = JSON.stringify(template);
  return tt;
}

export function reduceTargetTemplate(
  template: object,
  patched: boolean
): string {
  if (template && !patched) {
    delete template[MappingTokens.IDENTITY];
    delete template[MappingTokens.CONTEXT_DATA];
    delete template[MappingTokens.TOPIC_LEVEL];
  }
  const tt = JSON.stringify(template);
  return tt;
}
