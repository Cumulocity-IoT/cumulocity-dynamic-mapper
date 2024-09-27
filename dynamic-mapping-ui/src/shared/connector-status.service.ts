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
import { inject, Injectable, Injector } from '@angular/core';
import { EventService, FetchClient } from '@c8y/client';
import {
  CONNECTOR_FRAGMENT,
  ConnectorStatusEvent,
  SharedService
} from '../shared';

import {
  firstValueFrom,
  from,
  merge,
  Observable,
  ReplaySubject,
  Subject,
  Subscription
} from 'rxjs';
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
  }
  private eventRealtimeService: EventRealtimeService;

  private _agentId: string;
  private initialized: boolean = false;
  private subscription: Subscription;
  private filterStatusLog = {
    eventType: 'ALL',
    // eventType: StatusEventTypes.STATUS_CONNECTOR_EVENT_TYPE,
    connectorIdent: 'ALL'
  };
  private triggerLogs$: Subject<any> = new Subject();

  private statusLogs$: Observable<any[]>;

  getStatusLogs(): Observable<any[]> {
    console.log('Calling: getStatusLogs', this.initialized);
    return this.statusLogs$;
  }

  async startConnectorStatusLogs() {
    console.log('Calling: startConnectorStatusLogs', this.initialized);
    if (!this.initialized) {
      await this.initConnectorLogsRealtime();
      this.initialized = true;
    }
    this.triggerLogs$.next([{ type: 'reset' }]);
  }

  updateStatusLogs(filter: any) {
    this.triggerLogs$.next([{ type: 'reset' }]);
    this.filterStatusLog = filter;
  }

  async stopConnectorStatusLogs() {
    if (this.subscription) this.subscription.unsubscribe();
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
      // tap((x) => console.log('TriggerLogs In', x)),
      switchMap(() => {
        const filter = {
          pageSize: 5,
          withTotalPages: false,
          source: this._agentId
        };
        if (this.filterStatusLog.eventType !== 'ALL') {
          filter['type'] = this.filterStatusLog.eventType;
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
          return this.filterStatusLog.connectorIdent == 'ALL'
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
        if (val[0]?.type == 'reset') {
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
    firstValueFrom(this.statusLogs$);
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
        this.filterStatusLog.eventType == 'ALL'
          ? true
          : e['type'] == this.filterStatusLog.eventType
      ),
      filter((e) =>
        this.filterStatusLog.connectorIdent == 'ALL'
          ? true
          : e.connectorIdent == this.filterStatusLog.connectorIdent
      ),
      map((e) => [e]),
      tap((l) => console.log('StatusLogs:', l))
    );
  }
}
