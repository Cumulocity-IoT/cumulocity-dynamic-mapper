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

import { clampQos, definesDeviceIdentifier, QOS_DEFAULT, QOS_OPTIONS, qosLabel, qosLevel } from './mapping.model';
import { Direction, Mapping, Qos, RepairStrategy, Substitution } from '../../shared';

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

// ---------------------------------------------------------------------------
// QoS metadata — mirrors dynamic.mapper.model.Qos on the backend
// ---------------------------------------------------------------------------

describe('QoS metadata', () => {
  it('numbers the levels like MQTT and covers every enum value', () => {
    expect(QOS_OPTIONS.map((option) => option.value)).toEqual([
      Qos.AT_MOST_ONCE,
      Qos.AT_LEAST_ONCE,
      Qos.EXACTLY_ONCE
    ]);
    expect(QOS_OPTIONS.map((option) => option.level)).toEqual([0, 1, 2]);
    expect(QOS_DEFAULT).toBe(Qos.AT_LEAST_ONCE);
  });

  it('labels every level and falls back to the raw value', () => {
    expect(qosLabel(Qos.AT_MOST_ONCE)).toBe('At most once');
    expect(qosLabel('SOMETHING_ELSE')).toBe('SOMETHING_ELSE');
    expect(qosLevel('SOMETHING_ELSE')).toBe(-1);
  });

  it('leaves a supported level untouched', () => {
    expect(clampQos(Qos.EXACTLY_ONCE, [Qos.AT_MOST_ONCE, Qos.AT_LEAST_ONCE, Qos.EXACTLY_ONCE]))
      .toBe(Qos.EXACTLY_ONCE);
  });

  it('downgrades to the strongest supported level below the request', () => {
    expect(clampQos(Qos.EXACTLY_ONCE, [Qos.AT_MOST_ONCE, Qos.AT_LEAST_ONCE]))
      .toBe(Qos.AT_LEAST_ONCE);
    expect(clampQos(Qos.EXACTLY_ONCE, [Qos.AT_MOST_ONCE])).toBe(Qos.AT_MOST_ONCE);
  });

  it('upgrades when the connector supports nothing weaker', () => {
    // HTTP/WebHook are always at-least-once
    expect(clampQos(Qos.AT_MOST_ONCE, [Qos.AT_LEAST_ONCE])).toBe(Qos.AT_LEAST_ONCE);
  });

  it('treats a missing capability as no restriction', () => {
    expect(clampQos(Qos.EXACTLY_ONCE, undefined)).toBe(Qos.EXACTLY_ONCE);
    expect(clampQos(Qos.EXACTLY_ONCE, [])).toBe(Qos.EXACTLY_ONCE);
  });
});
