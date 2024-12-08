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
import { inject, Injectable } from '@angular/core';
import { EventService } from '@c8y/client';
import {
  CONNECTOR_FRAGMENT,
  ConnectorStatus,
  ConnectorStatusEvent,
  LoggingEventType,
  LoggingEventTypeMap,
  SharedService,
} from '../shared';

import { BehaviorSubject, from, merge, Observable, ReplaySubject } from 'rxjs';
import { filter, map, scan, shareReplay, switchMap, tap } from 'rxjs/operators';
import {
  EventRealtimeService,
  RealtimeSubjectService
} from '@c8y/ngx-components';

@Injectable({
  providedIn: 'root'
})
export class ConnectorStatusService {
  constructor(
    private eventService: EventService,
    private sharedService: SharedService
  ) {
    this.eventRealtimeService = new EventRealtimeService(
      inject(RealtimeSubjectService)
    );
    this.startConnectorStatusLogs();
  }
  private eventRealtimeService: EventRealtimeService;

  private _agentId: string;
  private readonly ALL: string = 'ALL';

  private readonly RESET = {
    connectorIdent: this.ALL,
    connectorName: 'EMPTY',
    status: ConnectorStatus.UNKNOWN,
    type: LoggingEventTypeMap[LoggingEventType.ALL].type,
    message: '_RESET_'
  };

  private filterStatusLog = this.RESET;

  private triggerLogs$: BehaviorSubject<ConnectorStatusEvent> =
    new BehaviorSubject(this.RESET);

  private statusLogs$ = new ReplaySubject<ConnectorStatusEvent[]>(1);

  getStatusLogs(): Observable<ConnectorStatusEvent[]> {
    return this.statusLogs$.asObservable();
  }

  async startConnectorStatusLogs() {
    // console.log('Calling: startConnectorStatusLogs');
    if (!this.statusLogs$) {
      await this.initConnectorLogsRealtime();
    }
  }

  updateStatusLogs(filter: {
    connectorIdent: string,
    type: LoggingEventType,
  }) {
    const updatedFilter = this.RESET;
    updatedFilter.type = LoggingEventTypeMap[filter.type].type;
    this.filterStatusLog = updatedFilter;
    this.triggerLogs$.next(updatedFilter);
  }

  async initConnectorLogsRealtime() {
    if (!this._agentId) {
      this._agentId = await this.sharedService.getDynamicMappingServiceAgent();
    }
    // console.log(
    //   'Calling: BrokerConfigurationService.initConnectorLogsRealtime()',
    //   this._agentId
    // );
    const filteredConnectorStatus$ = this.triggerLogs$.pipe(
      tap((x) => console.log('TriggerLogs In', x)),
      switchMap((x) => {
        const filter = {
          pageSize: 100,
          withTotalPages: false,
          source: this._agentId
        };
        if (x?.type !== LoggingEventTypeMap[LoggingEventType.ALL].type) {
          filter['type'] = x?.type;
        }
        return this.eventService.list(filter);
      }),
      map((data) => data.data),
      map((events) =>
        events
          .filter((ev) => {
            console.log('Event has:', ev, ev.hasOwnProperty(CONNECTOR_FRAGMENT));
            return ev.hasOwnProperty(CONNECTOR_FRAGMENT)
          })
          .map((event) => {
            event[CONNECTOR_FRAGMENT].type = event.type;
            return event[CONNECTOR_FRAGMENT];
          })
      ),
      map((events) =>
        events.filter((event) => {
          return this.filterStatusLog.connectorIdent == this.ALL
            ? true
            : event.connectorIdent == this.filterStatusLog.connectorIdent;
        })
      ),
      tap((x) => console.log('TriggerLogs Out', x))
    );

    //  const refreshedConnectorStatus$: Observable<any> =
    merge(
      filteredConnectorStatus$,
      this.getAllConnectorStatusEvents(),
      this.triggerLogs$.pipe(
        filter(cmd => cmd.message === '_RESET_'),
        map(cmd => [cmd])
      )
    ).pipe(
      scan((acc, val) => {
        let sortedAcc;
        if (val[0]?.message === '_RESET_') {
          sortedAcc = [];
        } else {
          sortedAcc = val.concat(acc);
        }
        sortedAcc = sortedAcc?.slice(0, 9);
        return sortedAcc;
      }, []),
      shareReplay(1)
    ).subscribe(this.statusLogs$);
  }

  private getAllConnectorStatusEvents(): Observable<ConnectorStatusEvent[]> {
    // console.log('Started subscriptions:', this._agentId);

    // subscribe to event stream
    this.eventRealtimeService.start();
    return from(this.sharedService.getDynamicMappingServiceAgent()).pipe(
      switchMap((agentId) => {
        return this.eventRealtimeService.onAll$(agentId);
      }),
      map((p) => p['data']),
      filter((e) => e[CONNECTOR_FRAGMENT]),
      map((e) => {
        e[CONNECTOR_FRAGMENT].type = e['type'];
        return e[CONNECTOR_FRAGMENT];
      }),
      filter((e) =>
        this.filterStatusLog.type == this.ALL
          ? true
          : e['type'] == this.filterStatusLog.type
      ),
      filter((e) =>
        this.filterStatusLog.connectorIdent == this.ALL
          ? true
          : e.connectorIdent == this.filterStatusLog.connectorIdent
      ),
      map((e) => [e]),
      tap((l) => console.log('StatusLogs:', l))
    );
  }
}
