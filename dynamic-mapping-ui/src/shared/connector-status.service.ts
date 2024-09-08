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
import { Injectable } from '@angular/core';
import { EventService, FetchClient, IEvent, Realtime } from '@c8y/client';
import {
  BASE_URL,
  CONNECTOR_FRAGMENT,
  ConnectorStatus,
  PATH_STATUS_CONNECTORS_ENDPOINT,
  SharedService
} from '../shared';

import { merge, Observable, Subject } from 'rxjs';
import { filter, map, scan, share, switchMap, tap } from 'rxjs/operators';

@Injectable({ providedIn: 'root' })
export class ConnectorStatusService {
  constructor(
    private client: FetchClient,
    private eventService: EventService,
    private sharedService: SharedService
  ) {
    this.realtime = new Realtime(this.client);
  }

  private _agentId: string;
  private initialized: boolean = false;
  private realtime: Realtime;
  private subscriptionEvents: any;
  private filterStatusLog = {
    eventType: 'ALL',
    // eventType: StatusEventTypes.STATUS_CONNECTOR_EVENT_TYPE,
    connectorIdent: 'ALL'
  };
  private triggerLogs$: Subject<any> = new Subject();
  private realtimeConnectorStatus$: Subject<IEvent> = new Subject();
  private statusLogs$: Subject<any[]> = new Subject();

  getStatusLogs(): Observable<any[]> {
    return this.statusLogs$;
  }

  async startConnectorStatusLogs() {
    // console.log('Calling: startConnectorStatusLogs');
    if (!this.initialized) {
      this.startConnectorStatusSubscriptions();
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
    this.realtime.unsubscribe(this.subscriptionEvents);
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
      ),
      share(),
      tap((x) => console.log('TriggerLogs Out', x))
    );

    const realtimeConnectorStatusRealtime$ = this.realtimeConnectorStatus$.pipe(
      // tap((x) => console.log('IncomingRealtime In', x)),
      filter((event) => {
        return (
          Object.keys(event).length !== 0 &&
          (this.filterStatusLog.eventType == 'ALL'
            ? true
            : event.type == this.filterStatusLog.eventType) &&
          (this.filterStatusLog.connectorIdent == 'ALL'
            ? true
            : event[CONNECTOR_FRAGMENT]?.connectorIdent ==
              this.filterStatusLog.connectorIdent)
        );
      }),
      map((event) => {
        event[CONNECTOR_FRAGMENT].type = event?.type;
        return [event[CONNECTOR_FRAGMENT]];
      })
    );

    //  const refreshedConnectorStatus$: Observable<any> =
    merge(
      filteredConnectorStatus$,
      realtimeConnectorStatusRealtime$,
      this.triggerLogs$
    )
      .pipe(
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
        tap((logs) => this.statusLogs$.next(logs))
      )
      .subscribe();
    // refreshedConnectorStatus$.subscribe((logs) => this.statusLogs$.next(logs));
  }

  async startConnectorStatusSubscriptions(): Promise<void> {
    if (!this._agentId) {
      this._agentId = await this.sharedService.getDynamicMappingServiceAgent();
    }
    // console.log('Started subscriptions:', this._agentId);

    // subscribe to event stream
    this.subscriptionEvents = this.realtime.subscribe(
      `/events/${this._agentId}`,
      this.updateRealtimeConnectorStatus
    );
  }

  private updateRealtimeConnectorStatus = async (p: object) => {
    const payload = p['data']['data'];
    this.realtimeConnectorStatus$.next(payload);
  };

  async getConnectorStatus(): Promise<ConnectorStatus> {
    const response = await this.client.fetch(
      `${BASE_URL}/${PATH_STATUS_CONNECTORS_ENDPOINT}`,
      {
        method: 'GET'
      }
    );
    const result = await response.json();
    return result;
  }
}
