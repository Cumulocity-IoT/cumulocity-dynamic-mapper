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
import { EventService, FetchClient } from '@c8y/client';
import {
  CONNECTOR_FRAGMENT,
  ConnectorStatus,
  ConnectorStatusEvent,
  SharedService,
  StatusEventTypes
} from '../shared';

import { BehaviorSubject, from, merge, Observable } from 'rxjs';
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
    private client: FetchClient,
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
    type: StatusEventTypes.ALL,
    message: '_RESET_'
  };

  private filterStatusLog = this.RESET;

  private triggerLogs$: BehaviorSubject<ConnectorStatusEvent[]> =
    new BehaviorSubject([this.RESET]);

  private statusLogs$: Observable<ConnectorStatusEvent[]>;

  getStatusLogs(): Observable<ConnectorStatusEvent[]> {
    console.log('Calling: getStatusLogs');
    return this.statusLogs$;
  }

  async startConnectorStatusLogs() {
    console.log('Calling: startConnectorStatusLogs');
    if (!this.statusLogs$) {
      await this.initConnectorLogsRealtime();
    }
  }

  updateStatusLogs(filter: any) {
    const updatedFilter = {
      ...this.RESET,
      ...filter
    };
    this.filterStatusLog = updatedFilter;
    this.triggerLogs$.next([updatedFilter]);
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
          pageSize: 5,
          withTotalPages: false,
          source: this._agentId
        };
        if (x[0]?.type !== this.ALL) {
          filter['type'] = x[0]?.type;
        }
        return this.eventService.list(filter);
      }),
      map((data) => data.data),
      map((events) =>
        events
          .filter((ev) => ev[CONNECTOR_FRAGMENT])
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
      )
      //   tap((x) => console.log('TriggerLogs Out', x))
    );

    //  const refreshedConnectorStatus$: Observable<any> =
    this.statusLogs$ = merge(
      filteredConnectorStatus$,
      this.getAllConnectorStatusEvents(),
      this.triggerLogs$
    ).pipe(
      // tap((i) => console.log('Items', i)),
      scan((acc, val) => {
        let sortedAcc;
        if (val[0]?.message == '_RESET_') {
          // console.log('Reset loaded logs!');
          sortedAcc = [];
        } else {
          sortedAcc = val.concat(acc);
        }
        sortedAcc = sortedAcc.slice(0, 9);
        return sortedAcc;
      }, []),
      shareReplay(1)
    );
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
