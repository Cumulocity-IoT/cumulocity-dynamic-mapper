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

  private readonly RESET = {
    connectorIdent: 'ALL',
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
    updatedFilter.connectorIdent = filter.connectorIdent;
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
      tap(x => console.log('TriggerLogs In', x, this.filterStatusLog)),
      switchMap(x => this.eventService.list({
        pageSize: 100,
        withTotalPages: false,
        source: this._agentId,
        ...(x.type !== 'ALL' && { type: x.type })
      })),
      map(({ data }) => data),
      map(events => events
        .filter(ev => {
          // console.log('Event has:', ev, ev.hasOwnProperty(CONNECTOR_FRAGMENT));
          return ev.hasOwnProperty(CONNECTOR_FRAGMENT);
        })
        .map(event => ({
          ...event[CONNECTOR_FRAGMENT],
          type: event.type
        }))
      ),
      map(events => events.filter(event => 
        this.filterStatusLog.connectorIdent === 'ALL' || 
        event.connectorIdent === this.filterStatusLog.connectorIdent
      )),
      // tap(x => console.log('TriggerLogs Out', x))
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
    this.eventRealtimeService.start();
    
    return from(this.sharedService.getDynamicMappingServiceAgent()).pipe(
      switchMap(agentId => this.eventRealtimeService.onAll$(agentId)),
      map(({ data }) => data),
      filter(event => event[CONNECTOR_FRAGMENT]),
      map(event => ({
        ...event[CONNECTOR_FRAGMENT],
        type: event['type']
      })),
      filter(event => 
        (this.filterStatusLog.type === 'ALL' || event.type === this.filterStatusLog.type) &&
        (this.filterStatusLog.connectorIdent === 'ALL' || event.connectorIdent === this.filterStatusLog.connectorIdent)
      ),
      map(event => [event]),
      // tap(logs => console.log('StatusLogs:', logs))
    );
  }
}
