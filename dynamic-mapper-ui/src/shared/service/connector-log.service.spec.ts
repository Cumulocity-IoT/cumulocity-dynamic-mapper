/*
 * Copyright (c) 2026 Cumulocity GmbH
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

import { TestBed } from '@angular/core/testing';
import { EventService } from '@c8y/client';
import { RealtimeSubjectService } from '@c8y/ngx-components';
import { ConnectorLogService } from './connector-log.service';
import { ConnectorStatus, ConnectorStatusEvent } from '../connector-details/connector-log.model';
import { SharedService } from '..';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function makeEvent(overrides: Partial<ConnectorStatusEvent> = {}): ConnectorStatusEvent {
  return {
    id: 'ev-1',
    connectorIdentifier: 'conn-1',
    connectorName: 'Test Connector',
    time: '2026-09-20T20:04:30.000Z',
    status: ConnectorStatus.CONNECTED,
    message: '',
    type: 'd11r_connectorStatusEvent',
    ...overrides
  };
}

// ---------------------------------------------------------------------------
// ConnectorLogService.accumulateEvents
//
// The private, stateful merge step behind the "Details & logs" timeline's realtime stream (see
// ConnectorLogService.initializeLogStream): historical events arrive as one sorted batch while
// realtime events push in one at a time, and the SAME event id can be pushed more than once since
// a connection-lifecycle session updates one Cumulocity Event in place as it progresses (see
// ConnectorStatusHistory on the backend). This had no test coverage before the
// connector-log-bundling feature review.
// ---------------------------------------------------------------------------

describe('ConnectorLogService.accumulateEvents', () => {
  let service: ConnectorLogService;

  beforeEach(() => {
    const eventServiceSpy = jasmine.createSpyObj<EventService>('EventService', ['list']);
    const sharedServiceSpy = jasmine.createSpyObj<SharedService>('SharedService', [
      'getDynamicMappingServiceAgent'
    ]);
    sharedServiceSpy.getDynamicMappingServiceAgent.and.resolveTo({ id: 'agent-1' } as any);
    const realtimeSubjectServiceSpy = jasmine.createSpyObj<RealtimeSubjectService>(
      'RealtimeSubjectService',
      ['getObservableForChannel']
    );

    TestBed.configureTestingModule({
      providers: [
        ConnectorLogService,
        { provide: EventService, useValue: eventServiceSpy },
        { provide: SharedService, useValue: sharedServiceSpy },
        { provide: RealtimeSubjectService, useValue: realtimeSubjectServiceSpy }
      ]
    });

    service = TestBed.inject(ConnectorLogService);
  });

  function accumulate(
    accumulated: ConnectorStatusEvent[],
    newEvents: ConnectorStatusEvent[]
  ): ConnectorStatusEvent[] {
    return (service as any).accumulateEvents(accumulated, newEvents);
  }

  it('appends a genuinely new event', () => {
    const existing = [makeEvent({ id: 'a', time: '2026-09-20T20:00:00.000Z' })];
    const result = accumulate(existing, [makeEvent({ id: 'b', time: '2026-09-20T20:01:00.000Z' })]);

    expect(result.map(e => e.id)).toEqual(['b', 'a']);
  });

  it('dedupes a repeated push of the same event id, keeping the newer snapshot', () => {
    const stale = makeEvent({ id: 'a', time: '2026-09-20T20:00:00.000Z', status: ConnectorStatus.CONNECTING });
    const fresh = makeEvent({ id: 'a', time: '2026-09-20T20:00:05.000Z', status: ConnectorStatus.CONNECTED });

    const result = accumulate([stale], [fresh]);

    expect(result.length).toBe(1);
    expect(result[0].status).toBe(ConnectorStatus.CONNECTED);
  });

  it('sorts the merged result by time, newest first, regardless of arrival order', () => {
    const older = makeEvent({ id: 'a', time: '2026-09-20T19:00:00.000Z' });
    const newer = makeEvent({ id: 'b', time: '2026-09-20T21:00:00.000Z' });
    const middle = makeEvent({ id: 'c', time: '2026-09-20T20:00:00.000Z' });

    // Deliberately push the newest event as if it arrived out of order relative to `accumulated`.
    const result = accumulate([older, middle], [newer]);

    expect(result.map(e => e.id)).toEqual(['b', 'c', 'a']);
  });

  it('truncates to MAX_LOG_ENTRIES (20), keeping the most recent', () => {
    const accumulated = Array.from({ length: 20 }, (_, i) =>
      makeEvent({ id: `old-${i}`, time: `2026-09-20T10:${String(i).padStart(2, '0')}:00.000Z` })
    );
    const newest = makeEvent({ id: 'newest', time: '2026-09-20T23:00:00.000Z' });

    const result = accumulate(accumulated, [newest]);

    expect(result.length).toBe(20);
    expect(result[0].id).toBe('newest');
    expect(result.some(e => e.id === 'old-0')).toBe(false, 'the oldest entry must be dropped, not the newest');
  });

  it('falls back to a type+time composite key when an event has no id', () => {
    const withoutId = makeEvent({ id: undefined, type: 'd11r_connectorStatusEvent', time: '2026-09-20T20:00:00.000Z' });

    const result = accumulate([withoutId], [{ ...withoutId }]);

    expect(result.length).toBe(1, 'two id-less events with the same type+time must be treated as the same entry');
  });

  it('keeps id-less events with different times as distinct entries', () => {
    const first = makeEvent({ id: undefined, time: '2026-09-20T20:00:00.000Z' });
    const second = makeEvent({ id: undefined, time: '2026-09-20T20:05:00.000Z' });

    const result = accumulate([first], [second]);

    expect(result.length).toBe(2);
  });
});
