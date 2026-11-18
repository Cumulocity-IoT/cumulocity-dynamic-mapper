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

import { DatePipe } from '@c8y/ngx-components';
import { TestBed } from '@angular/core/testing';
import { provideTranslateStub } from '../../../shared/testing/translate-stub';
import { TypeResyncComponent } from './type-resync.component';

describe('TypeResyncComponent (status polling)', () => {
  let component: TypeResyncComponent;
  let list: jasmine.Spy;
  let nextPage: jasmine.Spy;

  const event = (type: string, extra: Record<string, unknown>, time: string) =>
    ({ time, d11r_subscription: { type, ...extra } });

  const page = (data: unknown[]) => ({ data, paging: { nextPage: 2, next: nextPage } });

  beforeEach(() => {
    nextPage = jasmine.createSpy('next');
    list = jasmine.createSpy('list');
    const eventService = { list } as any;
    const sharedService = { getDynamicMappingServiceAgent: () => Promise.resolve('agent-1') } as any;
    TestBed.configureTestingModule({ providers: [provideTranslateStub(), { provide: DatePipe, useValue: { transform: (v: unknown) => new Date(v as number).toLocaleString() } }] });
    // Built in an injection context (the constructor registers an effect) and never rendered
    component = TestBed.runInInjectionContext(() => new TypeResyncComponent(eventService, sharedService));
    component.typeListInternal = ['a', 'b'];
  });

  it('a quick load reads only the newest page and keeps the last completed run it does not show', async () => {
    // full load: 'a' finished earlier; 'b' never ran, so every page is read
    list.and.resolveTo(page([event('a', { subscribed: '3', failed: '0' }, '2026-11-01T10:00:00Z')]));
    nextPage.and.resolveTo({ data: [], paging: {} });
    await component['loadSyncStates']();
    expect(nextPage).toHaveBeenCalled();

    // a new run of 'a' started: only its start event is on the newest page
    nextPage.calls.reset();
    list.and.resolveTo(page([event('a', {}, '2026-11-17T09:00:00Z')]));
    await component['loadSyncStates'](true);

    expect(nextPage).not.toHaveBeenCalled();
    const state = component['states']['a'];
    expect(state.outcome).toBe('RUNNING');
    expect(state.lastCompletedTime).toBe('2026-11-01T10:00:00Z');
  });

  it('hides the resync action for a type that is running, and offers it again once the run finished', async () => {
    const action = component.actionControls[0];
    list.and.resolveTo(page([event('a', {}, new Date().toISOString())]));
    nextPage.and.resolveTo({ data: [], paging: {} });
    await component['loadSyncStates']();

    expect(action.showIf!({ type: 'a' } as any)).toBeFalse();
    expect(action.showIf!({ type: 'b' } as any)).toBeTrue();

    list.and.resolveTo(page([
      event('a', { subscribed: '1', failed: '0' }, new Date().toISOString()),
      event('a', {}, new Date(Date.now() - 1000).toISOString())
    ]));
    await component['loadSyncStates'](true);

    expect(action.showIf!({ type: 'a' } as any)).toBeTrue();
  });

  it('shows a finished run as OK whatever order the API lists its events in (ids are not monotonic)', async () => {
    // Real data: oldest first, and the result event has a LOWER id than its start event
    const start = { id: '1975577', ...event('a', {}, '2026-11-17T14:12:19.012Z') };
    const end = { id: '1943965', ...event('a', { subscribed: '10', failed: '0' }, '2026-11-17T14:12:19.389Z') };
    nextPage.and.resolveTo({ data: [], paging: {} });
    for (const data of [[start, end], [end, start]]) {
      list.and.resolveTo(page(data));
      await component['loadSyncStates']();
      expect(component['states']['a'].outcome).toBe('FINISHED');
      expect(component.rows[0].status).toBe('OK (10 subscribed, 0 failed)');
    }
  });

  it('does not ask the API for oldest-first order', async () => {
    list.and.resolveTo(page([]));
    await component['loadSyncStates']();
    expect(list.calls.mostRecent().args[0].revert).toBeUndefined();
  });

  it('offers a run whose start event is older than the stale limit (a job lost to a restart)', async () => {
    list.and.resolveTo(page([event('a', {}, new Date(Date.now() - 7 * 60 * 60 * 1000).toISOString())]));
    nextPage.and.resolveTo({ data: [], paging: {} });
    await component['loadSyncStates']();

    expect(component.actionControls[0].showIf!({ type: 'a' } as any)).toBeTrue();
  });

  it('shows the result and the never-synced state as translated row text', async () => {
    list.and.resolveTo(page([event('a', { subscribed: '3', failed: '1' }, '2026-11-01T10:00:00Z')]));
    nextPage.and.resolveTo({ data: [], paging: {} });
    await component['loadSyncStates']();

    const [a, b] = component.rows;
    expect(a.status).toBe('OK (3 subscribed, 1 failed)');
    expect(b.status).toBe('');
    expect(b.lastSyncTime).toBe('Not synced yet');
  });
});
