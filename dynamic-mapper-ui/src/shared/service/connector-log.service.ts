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
import { inject, Injectable } from '@angular/core';
import { EventService } from '@c8y/client';
import {
  CONNECTOR_FRAGMENT,
  ConnectorStatusEvent,
  getEventMetadata,
  LoggingEventType,
  LoggingEventTypeMap,
  SharedService,
} from '..';

import { BehaviorSubject, from, merge, Observable, of, Subject } from 'rxjs';
import { catchError, filter, map, scan, shareReplay, startWith, switchMap, takeUntil } from 'rxjs/operators';
import {
  EventRealtimeService,
  RealtimeSubjectService
} from '@c8y/ngx-components';

interface LogFilter {
  connectorIdentifier: string;
  type: string;
}

@Injectable({
  providedIn: 'root'
})
export class ConnectorLogService {
  private readonly eventService = inject(EventService);
  private readonly sharedService = inject(SharedService);
  private readonly realtimeSubjectService = inject(RealtimeSubjectService);
  private readonly eventRealtimeService: EventRealtimeService;

  private readonly destroy$ = new Subject<void>();
  private readonly filter$ = new BehaviorSubject<LogFilter>({
    connectorIdentifier: 'ALL',
    type: 'ALL'
  });

  private readonly CONFIG = {
    PAGE_SIZE: 1000,
    MAX_LOG_ENTRIES: 20
  } as const;

  private readonly agentId$ = from(
    this.sharedService.getDynamicMappingServiceAgent()
  ).pipe(
    shareReplay(1)
  );

  private readonly statusLogs$: Observable<ConnectorStatusEvent[]>;

  constructor() {
    this.eventRealtimeService = new EventRealtimeService(this.realtimeSubjectService);
    this.statusLogs$ = this.initializeLogStream();
  }

  getStatusLogs(): Observable<ConnectorStatusEvent[]> {
    return this.statusLogs$;
  }

  async startConnectorStatusLogs(): Promise<void> {
    this.eventRealtimeService.start();
  }

  stopConnectorStatusLogs(): void {
    this.eventRealtimeService.stop();
    this.destroy$.next();
    this.destroy$.complete();
  }

  updateStatusLogs(filter: { connectorIdentifier: string; type: LoggingEventType }): void {
    this.filter$.next({
      connectorIdentifier: filter.connectorIdentifier,
      type: LoggingEventTypeMap[filter.type].type
    });
  }

  private initializeLogStream(): Observable<ConnectorStatusEvent[]> {
    return this.filter$.pipe(
      switchMap(logFilter =>
        merge(
          this.getHistoricalEvents(logFilter),
          this.getRealtimeEvents(logFilter)
        ).pipe(
          scan((acc, events) => this.accumulateEvents(acc, events), []),
          startWith([])
        )
      ),
      shareReplay(1)
    );
  }

  private getHistoricalEvents(logFilter: LogFilter): Observable<ConnectorStatusEvent[]> {
    return this.agentId$.pipe(
      switchMap(agentId =>
        from(this.eventService.list({
          pageSize: this.CONFIG.PAGE_SIZE,
          withTotalPages: false,
          source: agentId,
          ...(logFilter.type !== 'ALL' && { type: logFilter.type })
        }))
      ),
      map(response => this.processEvents(response.data || [], logFilter)),
      catchError(error => {
        console.error('Failed to load historical events:', error);
        return of([]);
      })
    );
  }


  private getRealtimeEvents(logFilter: LogFilter): Observable<ConnectorStatusEvent[]> {
    return this.agentId$.pipe(
      switchMap(agentId =>
        this.eventRealtimeService.onAll$(agentId).pipe(
          map(response => response.data),
          filter(event => this.isValidConnectorEvent(event)),
          map(event => this.toConnectorStatusEvent(event)),
          filter((event): event is ConnectorStatusEvent => event !== null),
          filter(event => this.matchesFilter(event, logFilter)),
          map(event => [event])
        )
      ),
      catchError(error => {
        console.error('Realtime events failed:', error);
        return of([]);
      }),
      takeUntil(this.destroy$)
    );
  }

  private processEvents(events: any[], filter: LogFilter): ConnectorStatusEvent[] {
    return events
      .filter(event => this.isValidConnectorEvent(event))
      .map(event => this.toConnectorStatusEvent(event))
      .filter((event): event is ConnectorStatusEvent => event !== null)
      .filter(event => this.matchesFilter(event, filter));
  }

  private isValidConnectorEvent(event: any): boolean {
    return event?.[CONNECTOR_FRAGMENT] && typeof event[CONNECTOR_FRAGMENT] === 'object';
  }

  private toConnectorStatusEvent(event: any): ConnectorStatusEvent | null {
    try {
      const fragment = event[CONNECTOR_FRAGMENT];
      const metadata = getEventMetadata(event);
      const statusLog = event['d11r_connectorStatusLog'];
      return {
        ...fragment,
        id: event.id,
        type: event.type,
        time: event.time,
        raw: event,
        ...(metadata && {
          severity: metadata.severity,
          component: metadata.component,
          componentDisplayName: metadata.componentDisplayName,
          description: metadata.description
        }),
        ...(statusLog && {
          historyCount: statusLog.history?.length,
          sessionClosed: statusLog.sessionClosed,
          statusLog
        })
      };
    } catch (error) {
      console.error('Failed to map event:', error);
      return null;
    }
  }

  private matchesFilter(event: ConnectorStatusEvent, filter: LogFilter): boolean {
    const typeMatches = filter.type === 'ALL' || event.type === filter.type;
    const connectorMatches = filter.connectorIdentifier === 'ALL' ||
      event.connectorIdentifier === filter.connectorIdentifier;
    return typeMatches && connectorMatches;
  }

  private accumulateEvents(
    accumulated: ConnectorStatusEvent[],
    newEvents: ConnectorStatusEvent[]
  ): ConnectorStatusEvent[] {
    // Historical events arrive as one sorted batch, but realtime events stream in as
    // independent async HTTP calls (see C8YAgent.createLoggingEvent) that can complete
    // out of order — so arrival order does not always match each event's own `time`.
    // Sort explicitly instead of trusting prepend order.
    //
    // Dedupe by event id: a connection-lifecycle session now updates one event in place as it
    // progresses (see ConnectorStatusHistory / C8YAgent.updateConnectorStatusEvent) instead of
    // creating a new event per status change, so the realtime stream can push the SAME id more
    // than once — without this, each push would add another row for what is really one ongoing
    // session. `newEvents` is placed first so its (newer) snapshot wins over any stale copy
    // already in `accumulated` for the same id.
    const byId = new Map<string, ConnectorStatusEvent>();
    for (const event of [...newEvents, ...accumulated]) {
      const key = event.id ?? `${event.type}-${event.time}`;
      if (!byId.has(key)) {
        byId.set(key, event);
      }
    }
    return Array.from(byId.values())
      .sort((a, b) => new Date(b.time).getTime() - new Date(a.time).getTime())
      .slice(0, this.CONFIG.MAX_LOG_ENTRIES);
  }
}