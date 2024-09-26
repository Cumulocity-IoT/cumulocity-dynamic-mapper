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
import { FetchClient, IEvent, IFetchResponse } from '@c8y/client';
import {
  BASE_URL,
  CONNECTOR_FRAGMENT,
  ConnectorConfiguration,
  ConnectorSpecification,
  ConnectorStatus,
  ConnectorStatusEvent,
  PATH_CONFIGURATION_CONNECTION_ENDPOINT,
  PATH_STATUS_CONNECTORS_ENDPOINT,
  SharedService,
  StatusEventTypes
} from '.';

import {
  BehaviorSubject,
  combineLatest,
  concat,
  forkJoin,
  from,
  Observable,
  of,
  ReplaySubject,
  Subject,
  Subscription
} from 'rxjs';
import { filter, map, switchMap, tap } from 'rxjs/operators';
import {
  EventRealtimeService,
  RealtimeSubjectService
} from '@c8y/ngx-components';

@Injectable({ providedIn: 'root' })
export class ConnectorConfigurationService {
  constructor(
    private client: FetchClient,
    private sharedService: SharedService
  ) {
    this.eventRealtimeService = new EventRealtimeService(
      inject(RealtimeSubjectService)
    );
    this.startConnectorConfigurations();
  }

  private _connectorConfigurations: ConnectorConfiguration[];
  private _connectorSpecifications: ConnectorSpecification[];

  private triggerConfigurations$: Subject<string> = new Subject();
  private realtimeConnectorConfigurations$: Subject<ConnectorConfiguration[]> =
    new ReplaySubject(1);
  private enrichedConnectorConfiguration$: Observable<ConnectorConfiguration[]>;

  private initialized: boolean = false;
  private eventRealtimeService: EventRealtimeService;
  private subscription: Subscription;

  getRealtimeConnectorConfigurations(): Observable<ConnectorConfiguration[]> {
    return this.realtimeConnectorConfigurations$;
  }

  resetCache() {
    // console.log('Calling: BrokerConfigurationService.resetCache()');
    this._connectorConfigurations = [];
    this._connectorSpecifications = undefined;
  }

  startConnectorConfigurations() {
    const n = Date.now();
    if (!this.initialized) {
      this.initConnectorConfigurations();
      this.initialized = true;
      this.testRealtime();
    }
    this.triggerConfigurations$.next('start' + '/' + n);
  }

  updateConnectorConfigurations() {
    const n = Date.now();
    this.triggerConfigurations$.next('refresh' + '/' + n);
  }

  stopConnectorConfigurations() {
    if (this.subscription) this.subscription.unsubscribe();
  }

  initConnectorConfigurations() {
    this.enrichedConnectorConfiguration$ = this.triggerConfigurations$.pipe(
      //   tap((state) =>
      //     console.log('New triggerConfigurations:', state + '/' + Date.now())
      //   ),
      switchMap(() => {
        const observableConfigurations = from(
          this.getConnectorConfigurations()
        );
        const observableStatus = from(this.getConnectorStatus());
        return forkJoin([observableConfigurations, observableStatus]);
      }),
      map((vars) => {
        const [configurations, connectorStatus] = vars;
        configurations.forEach((cc) => {
          const status = connectorStatus[cc.ident]
            ? connectorStatus[cc.ident].status
            : ConnectorStatus.UNKNOWN;
          if (!cc['status$']) {
            cc['status$'] = new BehaviorSubject<string>(status);
          } else {
            cc['status$'].next(status);
          }
        });
        return configurations;
      })
      // shareReplay(1)
    );
    combineLatest([
      this.enrichedConnectorConfiguration$,
      this.getConnectorStatusEvents()
    ])
      .pipe(
        map((vars) => {
          const [configurations, payload] = vars;
          if (payload) {
            const statusLog: ConnectorStatusEvent = payload[CONNECTOR_FRAGMENT];
            configurations.forEach((cc) => {
              if (statusLog && statusLog['connectorIdent'] == cc.ident) {
                if (!cc['status$']) {
                  cc['status$'] = new BehaviorSubject<string>(statusLog.status);
                } else {
                  cc['status$'].next(statusLog.status);
                }
              }
            });
          }
          return configurations;
        }),
        tap((confs) => this.realtimeConnectorConfigurations$.next(confs))
      )
      .subscribe();
  }

  async getConnectorSpecifications(): Promise<ConnectorSpecification[]> {
    if (!this._connectorSpecifications) {
      const response = await this.client.fetch(
        `${BASE_URL}/${PATH_CONFIGURATION_CONNECTION_ENDPOINT}/specifications`,
        {
          headers: {
            accept: 'application/json'
          },
          method: 'GET'
        }
      );
      this._connectorSpecifications = await response.json();
    }
    return this._connectorSpecifications;
  }

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

  async updateConnectorConfiguration(
    configuration: ConnectorConfiguration
  ): Promise<IFetchResponse> {
    return this.client.fetch(
      `${BASE_URL}/${PATH_CONFIGURATION_CONNECTION_ENDPOINT}/instance/${configuration.ident}`,
      {
        headers: {
          'content-type': 'application/json'
        },
        body: JSON.stringify(configuration),
        method: 'PUT'
      }
    );
  }

  async createConnectorConfiguration(
    configuration: ConnectorConfiguration
  ): Promise<IFetchResponse> {
    return this.client.fetch(
      `${BASE_URL}/${PATH_CONFIGURATION_CONNECTION_ENDPOINT}/instance`,
      {
        headers: {
          'content-type': 'application/json'
        },
        body: JSON.stringify(configuration),
        method: 'POST'
      }
    );
  }

  async deleteConnectorConfiguration(ident: string): Promise<IFetchResponse> {
    return this.client.fetch(
      `${BASE_URL}/${PATH_CONFIGURATION_CONNECTION_ENDPOINT}/instance/${ident}`,
      {
        headers: {
          accept: 'application/json',
          'content-type': 'application/json'
        },
        method: 'DELETE'
      }
    );
  }

  async getConnectorConfigurations(): Promise<ConnectorConfiguration[]> {
    const response = await this.client.fetch(
      `${BASE_URL}/${PATH_CONFIGURATION_CONNECTION_ENDPOINT}/instances`,
      {
        headers: {
          accept: 'application/json'
        },
        method: 'GET'
      }
    );
    this._connectorConfigurations = await response.json();

    return this._connectorConfigurations;
  }

  private getConnectorStatusEvents(): Observable<number | IEvent> {
    // subscribe to event stream
    this.eventRealtimeService.start();
    return from(this.sharedService.getDynamicMappingServiceAgent()).pipe(
      switchMap((agentId) => {
        // Emit an initial value immediately
        const initialValue: IEvent = {
          type: 'INITIAL',
          data: null,
          source: undefined,
          time: '',
          text: ''
        };
        return concat(
          of(initialValue),
          this.eventRealtimeService.onAll$(agentId).pipe(
            map((p) => p['data']),
            filter(
              (p) => p['type'] == StatusEventTypes.STATUS_CONNECTOR_EVENT_TYPE
            ),
            tap((p) => {
              console.log('Status change connector original:', p);
            })
          )
        );
      })
    );
  }

  private testRealtime() {
    console.log('Calling testRealtime');

    // const eventRealtimeService1 = new EventRealtimeService(
    //   inject(RealtimeSubjectService)
    // );
    // eventRealtimeService1.start();

    const eventRealtimeService2 = new EventRealtimeService(
      inject(RealtimeSubjectService)
    );
    eventRealtimeService2.start();

    // const initialValue: IEvent = {
    //   type: 'INITIAL',
    //   data: null,
    //   source: undefined,
    //   time: '',
    //   text: ''
    // };
    // concat(
    //   of(initialValue),
    //   eventRealtimeService1.onAll$('9262685372').pipe(
    //     map((p) => p['data']),
    //     filter(
    //       (p) => p['type'] == StatusEventTypes.STATUS_CONNECTOR_EVENT_TYPE
    //     ),
    //     tap((p) => {
    //       console.log('Status change connector:', p);
    //     })
    //   )
    // ).subscribe((p) => console.log('Status change connector combined:', p));

    eventRealtimeService2
      .onAll$('9262685372')
      .pipe(
        //  map((p) => p['data']),
        // filter((p) => p['type'] == StatusEventTypes.STATUS_CONNECTOR_EVENT_TYPE),
        tap((p) => {
          console.log('Status change connector simple:', p);
        })
      )
      .subscribe();
  }
}
