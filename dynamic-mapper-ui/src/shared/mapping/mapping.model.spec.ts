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

import { definesDeviceIdentifier } from './mapping.model';
import { Direction, Mapping, RepairStrategy, Substitution } from '../../shared';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function makeMapping(direction: Direction, useExternalId = false): Mapping {
  return {
    direction,
    useExternalId
  } as any as Mapping;
}

function makeSubstitution(pathSource: string, pathTarget: string): Substitution {
  return {
    pathSource,
    pathTarget,
    repairStrategy: RepairStrategy.DEFAULT,
    expandArray: false
  } as any as Substitution;
}

// ---------------------------------------------------------------------------
// definesDeviceIdentifier
// ---------------------------------------------------------------------------

describe('definesDeviceIdentifier', () => {
  it('should return true for outbound mapping with pathSource exactly _IDENTITY_.externalId', () => {
    const mapping = makeMapping(Direction.OUTBOUND);
    const sub = makeSubstitution('_IDENTITY_.externalId', '$.someTarget');
    expect(definesDeviceIdentifier(mapping, sub)).toBe(true);
  });

  it('should return true for outbound mapping with pathSource exactly _IDENTITY_.c8ySourceId, even when useExternalId is true', () => {
    const mapping = makeMapping(Direction.OUTBOUND, true);
    const sub = makeSubstitution('_IDENTITY_.c8ySourceId', '$.someTarget');
    expect(definesDeviceIdentifier(mapping, sub)).toBe(true);
  });

  it('should return true for outbound mapping with pathSource as a compound expression referencing _IDENTITY_.externalId', () => {
    const mapping = makeMapping(Direction.OUTBOUND);
    const sub = makeSubstitution('"externalId_." & _IDENTITY_.externalId', '$.someTarget');
    expect(definesDeviceIdentifier(mapping, sub)).toBe(true);
  });

  it('should return false for outbound mapping with pathSource referencing an unrelated field', () => {
    const mapping = makeMapping(Direction.OUTBOUND);
    const sub = makeSubstitution('temperature', '$.someTarget');
    expect(definesDeviceIdentifier(mapping, sub)).toBe(false);
  });

  it('should return true for inbound mapping with pathTarget exactly _IDENTITY_.c8ySourceId', () => {
    const mapping = makeMapping(Direction.INBOUND);
    const sub = makeSubstitution('$.someSource', '_IDENTITY_.c8ySourceId');
    expect(definesDeviceIdentifier(mapping, sub)).toBe(true);
  });

  it('should return false for inbound mapping with pathTarget referencing an unrelated field', () => {
    const mapping = makeMapping(Direction.INBOUND);
    const sub = makeSubstitution('$.someSource', 'c8y_Temperature.T.value');
    expect(definesDeviceIdentifier(mapping, sub)).toBe(false);
  });
});
