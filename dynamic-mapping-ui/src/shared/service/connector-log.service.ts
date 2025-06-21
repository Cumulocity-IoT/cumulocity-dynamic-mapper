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
import { inject, Injectable, OnDestroy } from '@angular/core';
import { EventService } from '@c8y/client';
import {
  CONNECTOR_FRAGMENT,
  ConnectorStatus,
  ConnectorStatusEvent,
  LoggingEventType,
  LoggingEventTypeMap,
  SharedService,
} from '..';

import { BehaviorSubject, combineLatest, firstValueFrom, from, merge, Observable, of, ReplaySubject, Subscription } from 'rxjs';
import { catchError, filter, map, scan, shareReplay, switchMap, tap } from 'rxjs/operators';
import {
  EventRealtimeService,
  RealtimeSubjectService
} from '@c8y/ngx-components';

@Injectable({
  providedIn: 'root'
})
export class ConnectorLogService implements OnDestroy {

  private readonly eventService = inject(EventService);
  private readonly sharedService = inject(SharedService);
  private readonly realtimeSubjectService = inject(RealtimeSubjectService);

  constructor() {
    this.eventRealtimeService = new EventRealtimeService(
      this.realtimeSubjectService
    );
  }

  private subscriptions = new Subscription();
  private eventRealtimeService: EventRealtimeService;

  private agentId$: Observable<string> = from(
    this.sharedService.getDynamicMappingServiceAgent()
  ).pipe(
    shareReplay(1) // Cache the result
  );

  private readonly RESET = {
    connectorIdentifier: 'ALL',
    connectorName: 'EMPTY',
    status: ConnectorStatus.UNKNOWN,
    type: LoggingEventTypeMap[LoggingEventType.ALL].type,
    message: '_RESET_'
  };

  private readonly CONFIG = {
    PAGE_SIZE: 1000,
    MAX_LOG_ENTRIES: 20
  } as const;



  private filterStatusLog = this.RESET;
  private isInitialized = false;
  private triggerLogs$: BehaviorSubject<ConnectorStatusEvent> =
    new BehaviorSubject(this.RESET);
  private statusLogs$ = new ReplaySubject<ConnectorStatusEvent[]>(1);

  ngOnDestroy(): void {
    this.subscriptions.unsubscribe();
    this.eventRealtimeService.stop();
    this.statusLogs$.complete();
    this.triggerLogs$.complete();
  }

  getStatusLogs(): Observable<ConnectorStatusEvent[]> {
    return this.statusLogs$.asObservable();
  }

  async startConnectorStatusLogs(): Promise<void> {
    if (this.isInitialized) {
      console.warn('Connector logs already initialized');
      return;
    }

    try {
      await this.initConnectorLogsRealtime();
      this.isInitialized = true;
    } catch (error) {
      console.error('Failed to initialize connector logs:', error);
      throw error;
    }
  }

  stopConnectorStatusLogs(): void {
    this.subscriptions.unsubscribe();
    this.subscriptions = new Subscription(); // Reset for potential restart
    this.eventRealtimeService.stop();
    this.isInitialized = false;

    // Optionally reset state
    this.statusLogs$.next([]);
  }

  updateStatusLogs(filter: {
    connectorIdentifier: string,
    type: LoggingEventType,
  }): void {
    if (!filter) {
      throw new Error('Filter is required');
    }

    if (!filter.connectorIdentifier) {
      throw new Error('Connector identifier is required');
    }

    if (!LoggingEventTypeMap[filter.type]) {
      throw new Error(`Invalid logging event type: ${filter.type}`);
    }
    const updatedFilter = {
      ...this.RESET,
      type: LoggingEventTypeMap[filter.type].type,
      connectorIdentifier: filter.connectorIdentifier
    };

    this.filterStatusLog = updatedFilter;
    this.triggerLogs$.next(updatedFilter);
  }


  private createResetEvents$(): Observable<ConnectorStatusEvent[]> {
    return this.triggerLogs$.pipe(
      filter(filter => filter.message === '_RESET_'),
      map(ev => [ev])
    );
  }

  private async initConnectorLogsRealtime(): Promise<void> {
    const subscription = this.combineEventStreams().subscribe(events => {
      this.statusLogs$.next(events);
    });

    this.subscriptions.add(subscription);
  }

  private combineEventStreams(): Observable<ConnectorStatusEvent[]> {
    return merge(
      this.createFilteredConnectorStatus$(),
      this.getAllConnectorStatusEvents(),
      this.createResetEvents$()
    ).pipe(
      scan(this.accumulateEvents, []),
      shareReplay(1)
    );
  }

  private createFilteredConnectorStatus$(): Observable<ConnectorStatusEvent[]> {
    return this.triggerLogs$.pipe(
      switchMap(filter => this.loadEventsForFilter(filter).pipe(
        map(events => this.processRawEvents(events)),
        map(events => this.filterEventsByConnector(events, filter)) // Pass filter as parameter
      )),
      catchError(error => {
        console.error('Failed to create filtered connector status:', error);
        return of([]);
      })
    );
  }

  private loadEventsForFilter(filter: ConnectorStatusEvent): Observable<any[]> {
    return this.agentId$.pipe(
      switchMap(agentId =>
        from(this.eventService.list({
          pageSize: this.CONFIG.PAGE_SIZE,
          withTotalPages: false,
          source: agentId,
          ...(filter.type !== 'ALL' && { type: filter.type })
        })).pipe(
          catchError(error => {
            console.error('Failed to load events from API:', error);
            return of({ data: [] });
          })
        )
      ),
      map(response => response.data || [])
    );
  }

  private processRawEvents(events: any[]): ConnectorStatusEvent[] {
    return events
      .filter(this.hasValidConnectorFragment)
      .map(this.mapToConnectorStatusEvent)
      .filter((event): event is ConnectorStatusEvent => event !== null);
  }

  private hasValidConnectorFragment = (event: any): boolean => {
    return event &&
      typeof event === 'object' &&
      CONNECTOR_FRAGMENT in event &&
      event[CONNECTOR_FRAGMENT] &&
      typeof event[CONNECTOR_FRAGMENT] === 'object';
  };

  private mapToConnectorStatusEvent = (event: any): ConnectorStatusEvent | null => {
    try {
      const fragment = event[CONNECTOR_FRAGMENT];

      if (!fragment || typeof fragment !== 'object') {
        console.warn('Invalid connector fragment:', fragment);
        return null;
      }

      return {
        ...fragment,
        type: event.type
      };
    } catch (error) {
      console.error('Failed to map event to connector status:', error);
      return null;
    }
  };

  private filterEventsByConnector(events: ConnectorStatusEvent[], filter: ConnectorStatusEvent): ConnectorStatusEvent[] {
    if (filter.connectorIdentifier === 'ALL') {
      return events;
    }

    return events.filter(event =>
      event.connectorIdentifier === filter.connectorIdentifier
    );
  }


  private accumulateEvents = (acc: ConnectorStatusEvent[], val: ConnectorStatusEvent[]): ConnectorStatusEvent[] => {
    if (val[0]?.message === '_RESET_') {
      return [];
    }

    const combined = val.concat(acc);
    return combined.slice(0, this.CONFIG.MAX_LOG_ENTRIES);
  };


  private getAllConnectorStatusEvents(): Observable<ConnectorStatusEvent[]> {
    this.eventRealtimeService.start();

    return this.agentId$.pipe(
      switchMap(agentId =>
        this.eventRealtimeService.onAll$(agentId).pipe(
          catchError(error => {
            console.error('Realtime events failed:', error);
            return of({ data: {} });
          })
        )
      ),
      map((response: { data: any }) => response.data || {}),
      filter(event => event && typeof event === 'object' && CONNECTOR_FRAGMENT in event),
      map(event => this.mapToConnectorStatusEvent(event)),
      filter((event): event is ConnectorStatusEvent => event !== null),
      // Apply filtering based on current filter state
      filter(event => this.matchesCurrentFilter(event)),
      map(event => [event])
    );
  }

  private matchesCurrentFilter(event: ConnectorStatusEvent): boolean {
    return (this.filterStatusLog.type === 'ALL' || event.type === this.filterStatusLog.type) &&
      (this.filterStatusLog.connectorIdentifier === 'ALL' || event.connectorIdentifier === this.filterStatusLog.connectorIdentifier);
  }
}
