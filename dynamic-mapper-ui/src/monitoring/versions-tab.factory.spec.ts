/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *  @authors Christof Strack, Stefan Witschel
 *
 */

import { Router } from '@angular/router';
import { VersionsTabFactory } from './versions-tab.factory';
import { NODE2 } from '../shared/mapping/mapping.constants';

/**
 * `VersionsTabFactory` implements c8y's `TabFactory` interface: `get()` returns the
 * Inbound/Outbound sub-tabs for the "Versions" monitoring page, but only when the
 * current URL is actually under `.../monitoring/versions` — otherwise it must return an
 * empty tab list (it is registered globally, so it also gets asked on unrelated pages).
 * Plain unit test against a stubbed Router; no TestBed/component involved.
 */
describe('VersionsTabFactory', () => {
  function makeFactory(url: string): VersionsTabFactory {
    return new VersionsTabFactory({ url } as Router);
  }

  it('returns no tabs when the current URL is not the versions monitoring page', async () => {
    const factory = makeFactory('c8y-pkg-dynamic-mapper/node2/monitoring/mappings');
    const tabs = await factory.get();
    expect(tabs).toEqual([]);
  });

  it('returns Inbound and Outbound tabs when on the versions monitoring page', async () => {
    const factory = makeFactory(`c8y-pkg-dynamic-mapper/${NODE2}/monitoring/versions/inbound`);
    const tabs = await factory.get();

    expect(tabs.length).toBe(2);

    const inbound = tabs.find(t => t.label === 'Inbound');
    expect(inbound).toBeTruthy();
    expect(inbound?.path).toBe(`c8y-pkg-dynamic-mapper/${NODE2}/monitoring/versions/inbound`);
    expect(inbound?.icon).toBe('swipe-right');
    expect(inbound?.orientation).toBe('horizontal');
    expect(inbound?.priority).toBe(930);

    const outbound = tabs.find(t => t.label === 'Outbound');
    expect(outbound).toBeTruthy();
    expect(outbound?.path).toBe(`c8y-pkg-dynamic-mapper/${NODE2}/monitoring/versions/outbound`);
    expect(outbound?.icon).toBe('swipe-left');
    expect(outbound?.orientation).toBe('horizontal');
    expect(outbound?.priority).toBe(920);
  });

  it('ranks the Inbound tab ahead of the Outbound tab by priority', async () => {
    const factory = makeFactory(`c8y-pkg-dynamic-mapper/${NODE2}/monitoring/versions/outbound`);
    const [first, second] = await factory.get();
    expect(first.priority).toBeGreaterThan(second.priority);
  });
});
