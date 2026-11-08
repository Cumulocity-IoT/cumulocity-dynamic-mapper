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
import { MappingTokens } from './processor/processor.constants';
import { API, Direction, Mapping, Substitution, TransformationType } from './mapping.model';

export function getDeviceIdentifiers(mapping: Mapping): Substitution[] {
  return mapping.substitutions
    .filter(sub => definesDeviceIdentifier(mapping, sub));
}

export function getPathTargetForDeviceIdentifiers(mapping: Mapping): string[] {
  let pss;
  if (isSubstitutionsAsCode(mapping)) {
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
  sub: Substitution
): Substitution {
  return {
    pathSource: sub.pathSource,
    pathTarget: sub.pathTarget,
    repairStrategy: sub.repairStrategy,
    expandArray: sub.expandArray,
  };
}
function referencesIdentityToken(text: string, token: string): boolean {
  if (!text) {
    return false;
  }
  const escaped = token.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  return new RegExp(`(^|[^\\w])${escaped}(?![\\w])`).test(text);
}

export function definesDeviceIdentifier(
  mapping: Mapping,
  sub: Substitution
): boolean {
  // A substitution defines the device identifier if it references either
  // _IDENTITY_.externalId or _IDENTITY_.c8ySourceId, be it as the whole
  // path or embedded in a larger expression (e.g. a concatenation).
  const path = mapping.direction === Direction.INBOUND
    ? sub?.pathTarget
    : sub?.pathSource;
  return (
    referencesIdentityToken(path, `${MappingTokens.IDENTITY}.externalId`) ||
    referencesIdentityToken(path, `${MappingTokens.IDENTITY}.c8ySourceId`)
  );
}
export function isSubstitutionValid(mapping: Mapping): boolean {
  const count = mapping.substitutions
    .filter((sub) => definesDeviceIdentifier(mapping, sub)).length;
  return (
    (mapping.direction !== Direction.OUTBOUND && count === 1) ||
    mapping.direction === Direction.OUTBOUND
  );
}

export function countDeviceIdentifiers(mapping: Mapping): number {
  const n = mapping.substitutions.filter((sub) => definesDeviceIdentifier(mapping, sub)
  ).length;
  return n;
}
export function getGenericDeviceIdentifier(mapping: Mapping): string {
  if (mapping.useExternalId && mapping.externalIdType !== '') {
    return `${MappingTokens.IDENTITY}.externalId`;
  } else {
    return `${MappingTokens.IDENTITY}.c8ySourceId`;
  }
}

export function isSubstitutionsAsCode(mapping: Mapping): boolean {
  // eslint-disable-next-line @typescript-eslint/no-deprecated
  return mapping.transformationType === TransformationType.SUBSTITUTION_AS_CODE ||
    mapping.transformationType === TransformationType.SMART_FUNCTION;
}
