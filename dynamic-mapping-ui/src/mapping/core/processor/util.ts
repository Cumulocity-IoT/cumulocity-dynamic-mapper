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
import * as _ from 'lodash';
import { Direction, MappingSubstitution, Mapping, API, MappingType, RepairStrategy } from '../../../shared';
import { SubstituteValue, SubstituteValueType } from './processor.model';
import { getGenericDeviceIdentifier, isTypeOf } from '../../../mapping/shared/util';
import { AlertService } from '@c8y/ngx-components';

export const IDENTITY = '_IDENTITY_';
export const TOKEN_TOPIC_LEVEL = '_TOPIC_LEVEL_';
export const TOKEN_CONTEXT_DATA = '_CONTEXT_DATA_';
export const CONTEXT_DATA_KEY_NAME = 'key';
export const TIME = 'time';

export const TOPIC_WILDCARD_MULTI = '#';
export const TOPIC_WILDCARD_SINGLE = '+';

export function definesDeviceIdentifier(
  api: string,
  externalIdType: string,
  direction: Direction,
  sub: MappingSubstitution,
): boolean {
  if (direction == Direction.INBOUND) {
    if (externalIdType) {
      return sub?.pathTarget == `${IDENTITY}.externalId`;
    } else {
      return sub?.pathTarget == `${IDENTITY}.c8ySourceId`;
    }
  } else {
    if (externalIdType) {
      return sub?.pathSource == `${IDENTITY}.externalId`;
    } else {
      return sub?.pathSource == `${IDENTITY}.c8ySourceId`;
    }
  }
}

export function randomString(){
  return Math.floor(100000 + Math.random() * 900000).toString()
}

export function patchC8YTemplateForTesting(template: object, mapping: Mapping) {
  const identifier = randomString();
  _.set(template, API[mapping.targetAPI].identifier, identifier);
  _.set(template, `${IDENTITY}.c8ySourceId`, identifier);
}

export function getDeviceIdentifiers(mapping: Mapping): MappingSubstitution[] {
  const mp: MappingSubstitution[] = mapping.substitutions
    .filter(sub => definesDeviceIdentifier(mapping.targetAPI, mapping.externalIdType
      , mapping.direction, sub));
  return mp;
}

export function getPathSourceForDeviceIdentifiers(mapping: Mapping): string[] {
  const pss = mapping.substitutions
    .filter(sub => definesDeviceIdentifier(mapping.targetAPI, mapping.externalIdType
      , mapping.direction, sub))
    .map(sub => sub.pathSource);
  return pss;
}

export function getPathTargetForDeviceIdentifiers(mapping: Mapping): string[] {
  const pss = mapping.substitutions
    .filter(sub => definesDeviceIdentifier(mapping.targetAPI, mapping.externalIdType
      , mapping.direction, sub))
    .map(sub => sub.pathTarget);
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

export function getTypedValue(subValue: SubstituteValue): any {
  if (subValue.type == SubstituteValueType.NUMBER) {
    return Number(subValue.value);
  } else if (subValue.type == SubstituteValueType.TEXTUAL) {
    return String(subValue.value);
  } else {
    return subValue.value;
  }
}

export const isNumeric = (num: any) =>
  (typeof num === 'number' || (typeof num === 'string' && num.trim() !== '')) &&
  !isNaN(num as number);


export function processSubstitute(postProcessingCacheEntry: SubstituteValue[], extractedSourceContent: any, substitution: MappingSubstitution, mapping: Mapping) {
  if (isTypeOf(extractedSourceContent) == 'null') {
    postProcessingCacheEntry.push({
      value: extractedSourceContent,
      type: SubstituteValueType.IGNORE,
      repairStrategy: substitution.repairStrategy
    });
    console.error(
      'No substitution for: ',
      substitution.pathSource
    );
  } else if (isTypeOf(extractedSourceContent) == 'String') {
    postProcessingCacheEntry.push({
      value: extractedSourceContent,
      type: SubstituteValueType.TEXTUAL,
      repairStrategy: substitution.repairStrategy
    });
  } else if (isTypeOf(extractedSourceContent) == 'Number') {
    postProcessingCacheEntry.push({
      value: extractedSourceContent,
      type: SubstituteValueType.NUMBER,
      repairStrategy: substitution.repairStrategy
    });
  } else if (isTypeOf(extractedSourceContent) == 'Array') {
    postProcessingCacheEntry.push({
      value: extractedSourceContent,
      type: SubstituteValueType.ARRAY,
      repairStrategy: substitution.repairStrategy
    });
  } else if (isTypeOf(extractedSourceContent) == 'Object') {
    postProcessingCacheEntry.push({
      value: extractedSourceContent,
      type: SubstituteValueType.OBJECT,
      repairStrategy: substitution.repairStrategy
    });
  } else {
    console.warn(
      `Since result is not (number, array, textual, object), it is ignored: ${extractedSourceContent}`
    );
  }
}

export function substituteValueInPayload(
  type: MappingType,
  sub: SubstituteValue,
  jsonObject: JSON,
  keys: string,
  alert: AlertService
) {
  const subValueMissingOrNull: boolean = !sub || sub.value == null;

  if (keys == '$') {
    Object.keys(getTypedValue(sub)).forEach((key) => {
      jsonObject[key] = getTypedValue(sub)[key as keyof unknown];
    });
  } else {
    if (
      (sub.repairStrategy == RepairStrategy.REMOVE_IF_MISSING_OR_NULL &&
        subValueMissingOrNull)
    ) {
      _.unset(jsonObject, keys);
    } else if (sub.repairStrategy == RepairStrategy.CREATE_IF_MISSING) {
      // const pathIsNested: boolean = keys.includes('.') || keys.includes('[');
      // if (pathIsNested) {
      //   throw new Error('Can only create new nodes on the root level!');
      // }
      // jsonObject.put("$", keys, sub.typedValue());
      _.set(jsonObject, keys, getTypedValue(sub));
    } else {
      if (_.has(jsonObject, keys)) {
        _.set(jsonObject, keys, getTypedValue(sub));
      } else {
        alert.warning(`Message could NOT be parsed, ignoring this message: Path: ${keys} not found!`);
        throw new Error(
          `Message could NOT be parsed, ignoring this message: Path: ${keys} not found!`
        );
      }
    }
  }
}