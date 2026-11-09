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

import {
  addedTypes,
  changedDevices,
  changedGroups,
  changedTypes,
  emptySelection,
  hasPendingSubscriptionChanges,
  OutboundSubscriptionSelection
} from './outbound-subscription-selection.model';

describe('outbound subscription selection', () => {
  const baseline = {
    available: true,
    types: ['a', 'b'],
    groups: [{ id: '1', name: 'g1' }],
    devices: [{ id: '10' }, { id: '11' }]
  };
  const make = (over: Partial<OutboundSubscriptionSelection>): OutboundSubscriptionSelection =>
    ({ ...emptySelection(), baseline, ...over });

  it('starts as skip with nothing pending', () => {
    expect(emptySelection().mode).toBe('skip');
    expect(hasPendingSubscriptionChanges(emptySelection())).toBe(false);
    expect(hasPendingSubscriptionChanges(undefined)).toBe(false);
  });

  it('ignores a list that was not touched', () => {
    expect(hasPendingSubscriptionChanges(make({ mode: 'dynamic' }))).toBe(false);
  });

  it('ignores a list that was edited back to what the server has, in any order', () => {
    expect(changedTypes(make({ mode: 'dynamic', types: ['b', 'a'] }))).toBe(false);
    expect(changedDevices(make({ mode: 'static', devices: [{ id: '11' }, { id: '10' }] }))).toBe(false);
  });

  it('detects added and removed entries', () => {
    expect(changedTypes(make({ mode: 'dynamic', types: ['a', 'b', 'c'] }))).toBe(true);
    expect(changedTypes(make({ mode: 'dynamic', types: ['a'] }))).toBe(true);
    expect(changedGroups(make({ mode: 'dynamic', groups: [] }))).toBe(true);
    expect(changedDevices(make({ mode: 'static', devices: [{ id: '10' }] }))).toBe(true);
  });

  it('only counts the lists of the chosen mode', () => {
    expect(changedTypes(make({ mode: 'static', types: ['x'] }))).toBe(false);
    expect(changedDevices(make({ mode: 'dynamic', devices: [] }))).toBe(false);
    expect(hasPendingSubscriptionChanges(make({ mode: 'skip', types: ['x'] }))).toBe(false);
  });

  it('lists only the types the server does not have', () => {
    expect(addedTypes(make({ mode: 'dynamic', types: ['a', 'c'] }))).toEqual(['c']);
    expect(addedTypes(make({ types: null }))).toEqual([]);
  });
});
