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
import { FetchClient, IFetchResponse } from '@c8y/client';
import {
  BASE_URL,
  ConnectorConfiguration,
  ConnectorSpecification,
  ConnectorStatus,
  ConnectorStatusEvent,
  PATH_CONFIGURATION_CONNECTION_ENDPOINT,
  PATH_STATUS_CONNECTORS_ENDPOINT,
} from '..';

import {
  combineLatest,
  from,
  merge,
  Observable,
  Subject,
  Subscription
} from 'rxjs';
import { map, shareReplay, switchMap, tap } from 'rxjs/operators';
import {
  EventRealtimeService,
  RealtimeSubjectService
} from '@c8y/ngx-components';

@Injectable({ providedIn: 'root' })
export class ConnectorConfigurationService {
  constructor(
    private client: FetchClient,
  ) {
    this.eventRealtimeService = new EventRealtimeService(
      inject(RealtimeSubjectService)
    );
    this.startConnectorConfigurations();
    this.sharedConnectorConfigurations$ = this.connectorConfigurations$.pipe(
      // tap(() => console.log('Further up I')),
      // shareReplay(1),
      // tap(() => console.log('Further up II')),
      switchMap((configurations: ConnectorConfiguration[]) => {
        // console.log('Further up III');
        return combineLatest([
          from([configurations]),
          from(this.getConnectorStatus()),
          from(this.getConnectorSpecifications()),
        ]).pipe(
          map(([configs, statusMap, specs]) => {
            // console.log('Changes configs:', configs);
            // console.log('Changes statusMap:', statusMap);
            return configs.map((config) => ({
              ...config,
              status$: new Observable<ConnectorStatus>((observer) => {
                if (statusMap[config.identifier]) {
                  observer.next(statusMap[config.identifier].status);
                }
                return () => {}; // Cleanup function
              }),
              supportedDirections: specs.find(
                connector => connector.connectorType === config.connectorType
              )?.supportedDirections
            }));
          })
        );
      }),
      shareReplay(1)
    );
  }

  private _connectorConfigurations: ConnectorConfiguration[];
  private _connectorSpecifications: ConnectorSpecification[];

  private triggerConfigurations$: Subject<string> = new Subject();

  private initialized: boolean = false;
  private eventRealtimeService: EventRealtimeService;
  private subscription: Subscription;
  private connectorConfigurations$: Observable<ConnectorConfiguration[]>;
  private sharedConnectorConfigurations$: Observable<ConnectorConfiguration[]>;

  resetCache() {
    // console.log('Calling: BrokerConfigurationService.resetCache()');
    this._connectorConfigurations = [];
    this._connectorSpecifications = undefined;
  }

  startConnectorConfigurations() {
    if (!this.initialized) {
      this.initialized = true;
      this.connectorConfigurations$ = merge(
        from(this.getConnectorConfigurations()),
        this.triggerConfigurations$.pipe(
          switchMap(() => {
            return from(this.getConnectorConfigurations());
          })
        )
      ).pipe(
        // tap(() => console.log('Something happened')),
        shareReplay(1)
      );
      // this.testRealtime();
    }
  }

  updateConnectorConfigurations() {
    const n = Date.now();
    this.triggerConfigurations$.next('refresh' + '/' + n);
  }

  stopConnectorConfigurations() {
    if (this.subscription) this.subscription.unsubscribe();
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

  async getConnectorStatus(): Promise<{
    [identifier: string]: ConnectorStatusEvent;
  }> {
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
      `${BASE_URL}/${PATH_CONFIGURATION_CONNECTION_ENDPOINT}/instance/${configuration.identifier}`,
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

  async deleteConnectorConfiguration(identifier: string): Promise<IFetchResponse> {
    return this.client.fetch(
      `${BASE_URL}/${PATH_CONFIGURATION_CONNECTION_ENDPOINT}/instance/${identifier}`,
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
      `${BASE_URL}/${PATH_CONFIGURATION_CONNECTION_ENDPOINT}/instance`,
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

  async getConnectorConfiguration(identifier: string): Promise<ConnectorConfiguration>{
    const response = await this.client.fetch(
      `${BASE_URL}/${PATH_CONFIGURATION_CONNECTION_ENDPOINT}/instance/${identifier}`,
      {
        headers: {
          accept: 'application/json'
        },
        method: 'GET'
      }
    );
    const result = await response.json();

    return result;
  }

  getConnectorConfigurationsAsObservable(): Observable<ConnectorConfiguration[]> {
    return this.connectorConfigurations$;
  }

  // private getConnectorStatusEvents(): Observable<ConnectorStatusEvent> {
  //   // subscribe to event stream
  //   this.eventRealtimeService.start();
  //   return from(this.sharedService.getDynamicMappingServiceAgent()).pipe(
  //     switchMap((agentId) => {
  //       return concat(
  //         this.eventRealtimeService.onAll$(agentId).pipe(
  //           filter(
  //             (p) =>
  //               p['data']['type'] ==
  //             LoggingEventTypeMap[LoggingEventType.STATUS_CONNECTOR_EVENT_TYPE].type
  //           ),
  //           map((p) => {
  //             const connectorFragment = p['data'][CONNECTOR_FRAGMENT];
  //             return {
  //               connectorIdentifier: connectorFragment.connectorIdentifier,
  //               connectorName: connectorFragment.connectorName,
  //               status: connectorFragment.status,
  //               message: connectorFragment.message,
  //               type: connectorFragment.type
  //             };
  //           }),
  //           tap((p) => {
  //             console.log('Status change connector original:', p);
  //             this.updateConnectorConfigurations();
  //           })
  //         )
  //       );
  //     })
  //   );
  // }

  private async testRealtime() {
    console.log('Calling testRealtime');

    const eventRealtimeService = new EventRealtimeService(
      inject(RealtimeSubjectService)
    );
    eventRealtimeService.start();

    eventRealtimeService
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

  private updateRealtimeConnectorStatus = async (p: object) => {
    const payload = p['data']['data'];
    console.log('Status change connector old fashin:', payload);
  };

  getConnectorConfigurationsWithLiveStatus(): Observable<
    ConnectorConfiguration[]
  > {
    // console.log('Further up 0');
    return this.sharedConnectorConfigurations$;
  }
}
