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
import { Injectable } from "@angular/core";
import {
  EventService,
  FetchClient,
  IdentityService,
  IEvent,
  IExternalIdentity,
  IFetchResponse,
  IResultList,
  Realtime,
} from "@c8y/client";
import {
  AGENT_ID,
  BASE_URL,
  CONNECTOR_FRAGMENT,
  ConnectorSpecification,
  PATH_CONFIGURATION_CONNECTION_ENDPOINT,
  PATH_CONFIGURATION_SERVICE_ENDPOINT,
  PATH_EXTENSION_ENDPOINT,
  PATH_FEATURE_ENDPOINT,
  PATH_OPERATION_ENDPOINT,
  ConnectorConfiguration,
  Extension,
  Feature,
  Operation,
  ServiceConfiguration,
  ConnectorStatus,
  Status,
  ConnectorConfigurationCombined,
  PATH_STATUS_CONNECTORS_ENDPOINT,
  StatusEventTypes,
} from "../../shared";

import { BehaviorSubject, merge, Observable, Subject } from "rxjs";
import {
  map,
  scan,
  tap,
  filter,
  switchMap,
  withLatestFrom,
} from "rxjs/operators";

@Injectable({ providedIn: "root" })
export class BrokerConfigurationService {
  trigger_02$: Subject<string> = new Subject();
  constructor(
    private client: FetchClient,
    private identity: IdentityService,
    private eventService: EventService
  ) {
    this.realtime = new Realtime(this.client);
    this.startConnectorLogsRealtime();
    this.startConnectorStatusCheck();
  }

  private _agentId: Promise<string>;
  private _connectorConfigurationCombined: ConnectorConfigurationCombined[] =
    [];
  private _feature: Promise<Feature>;
  private realtime: Realtime;
  statusLogs$: Observable<any[]>;
  subscriptionEvents: any;
  statusLogEventType: string;
  filterTrigger$: BehaviorSubject<string> = new BehaviorSubject(StatusEventTypes.STATUS_CONNECTOR_EVENT_TYPE);
  incomingRealtime$: Subject<IEvent> = new Subject();

  public getStatusLogs(): Observable<any[]> {
    return this.statusLogs$;
  }

  async getDynamicMappingServiceAgent(): Promise<string> {
    if (!this._agentId) {
      const identity: IExternalIdentity = {
        type: "c8y_Serial",
        externalId: AGENT_ID,
      };
      const { data, res } = await this.identity.detail(identity);
      if (res.status < 300) {
        const agentId = data.managedObject.id.toString();
        this._agentId = Promise.resolve(agentId);
        console.log("BrokerConfigurationService: Found BrokerAgent", agentId);
      }
    }
    return this._agentId;
  }


  async updateConnectorConfiguration(
    configuration: ConnectorConfiguration
  ): Promise<IFetchResponse> {
    return this.client.fetch(
      `${BASE_URL}/${PATH_CONFIGURATION_CONNECTION_ENDPOINT}/instance/${configuration.ident}`,
      {
        headers: {
          "content-type": "application/json",
        },
        body: JSON.stringify(configuration),
        method: "PUT",
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
          "content-type": "application/json",
        },
        body: JSON.stringify(configuration),
        method: "POST",
      }
    );
  }

  async updateServiceConfiguration(
    configuration: ServiceConfiguration
  ): Promise<IFetchResponse> {
    return this.client.fetch(
      `${BASE_URL}/${PATH_CONFIGURATION_SERVICE_ENDPOINT}`,
      {
        headers: {
          "content-type": "application/json",
        },
        body: JSON.stringify(configuration),
        method: "POST",
      }
    );
  }

  async deleteConnectorConfiguration(ident: String): Promise<IFetchResponse> {
    return this.client.fetch(
      `${BASE_URL}/${PATH_CONFIGURATION_CONNECTION_ENDPOINT}/instance/${ident}`,
      {
        headers: {
          accept: "application/json",
          "content-type": "application/json",
        },
        method: "DELETE",
      }
    );
  }

  async getConnectorSpecifications(): Promise<ConnectorSpecification[]> {
    const response = await this.client.fetch(
      `${BASE_URL}/${PATH_CONFIGURATION_CONNECTION_ENDPOINT}/specifications`,
      {
        headers: {
          accept: "application/json",
        },
        method: "GET",
      }
    );

    if (response.status != 200) {
      return undefined;
    }

    return (await response.json()) as ConnectorSpecification[];
  }

  async getConnectorConfigurations(): Promise<ConnectorConfiguration[]> {
    const response = await this.client.fetch(
      `${BASE_URL}/${PATH_CONFIGURATION_CONNECTION_ENDPOINT}/instances`,
      {
        headers: {
          accept: "application/json",
        },
        method: "GET",
      }
    );

    if (response.status != 200) {
      return undefined;
    }

    return (await response.json()) as ConnectorConfiguration[];
  }

  async getConnectorConfigurationsCombined(): Promise<
    ConnectorConfigurationCombined[]
  > {
    const configurations: ConnectorConfiguration[] =
      await this.getConnectorConfigurations();
    this._connectorConfigurationCombined = [];
    configurations.forEach((conf) => {
      this._connectorConfigurationCombined.push({
        configuration: conf,
        status$: new BehaviorSubject<string>(Status.UNKNOWN),
      });
    });
    return this._connectorConfigurationCombined;
  }

  async getServiceConfiguration(): Promise<ServiceConfiguration> {
    const response = await this.client.fetch(
      `${BASE_URL}/${PATH_CONFIGURATION_SERVICE_ENDPOINT}`,
      {
        headers: {
          accept: "application/json",
        },
        method: "GET",
      }
    );

    if (response.status != 200) {
      return undefined;
    }

    return (await response.json()) as ServiceConfiguration;
  }

  async getConnectorStatus(): Promise<ConnectorStatus> {
    const response = await this.client.fetch(
      `${BASE_URL}/${PATH_STATUS_CONNECTORS_ENDPOINT}`,
      {
        method: "GET",
      }
    );
    const result = await response.json();
    return result;
  }

  async getFeatures(): Promise<Feature> {
    if (!this._feature) {
      const response = await this.client.fetch(
        `${BASE_URL}/${PATH_FEATURE_ENDPOINT}`,
        {
          method: "GET",
        }
      );
      this._feature = await response.json();
    }
    return this._feature;
  }


  async startConnectorStatusCheck(): Promise<void> {
    const agentId = await this.getDynamicMappingServiceAgent();
    console.log("Started subscription:", agentId);
    this.getConnectorStatus().then((status) => {
      this._connectorConfigurationCombined.forEach((cc) => {
        if (status[cc.configuration.ident]) {
          cc.status$.next(status[cc.configuration.ident].status);
        }
      });
      console.log("Update connector status from backend", status);
    });

    // subscribe to event stream
    this.subscriptionEvents = this.realtime.subscribe(
      `/events/${agentId}`,
      this.updateConnectorStatus
    );
  }

  private updateConnectorStatus = (p: object) => {
    let payload = p["data"]["data"];
    if (payload.type == this.statusLogEventType) {
      payload[CONNECTOR_FRAGMENT].type = payload.type;
      this.incomingRealtime$.next(payload);
    }

    if (payload.type == StatusEventTypes.STATUS_CONNECTOR_EVENT_TYPE) {
      let statusLog: ConnectorStatus = payload[CONNECTOR_FRAGMENT];
      this._connectorConfigurationCombined.forEach((cc) => {
        if (statusLog["connectorIdent"] == cc.configuration.ident) {
          cc.status$.next(statusLog.status);
        }
      });
    }
  };

  public updateStatusLogs(eventType: string) {
    this.filterTrigger$.next(eventType);
    this.statusLogEventType = eventType;
  }

  async startConnectorLogsRealtime(): Promise<void> {
    const agentId = await this.getDynamicMappingServiceAgent();
    console.log("Agent Id", agentId);

    const sourceList$ = this.filterTrigger$.pipe(
      tap((x) => console.log("Trigger", x)),
      switchMap((type) =>
        this.eventService.list({
          pageSize: 5,
          withTotalPages: true,
          type: type,
          source: agentId,
        })
      ),
      map((data) => data.data),
      map((events) =>
        events.map((event) => {
          event[CONNECTOR_FRAGMENT].type = event.type;
          return event[CONNECTOR_FRAGMENT];
        })
      ),
      tap((x) => console.log("Reload", x))
    );

    const sourceRealtime$ = this.incomingRealtime$.pipe(
      filter((event) => event.type == this.statusLogEventType),
      map((event) => [event[CONNECTOR_FRAGMENT]])
    );

    this.statusLogs$ = merge(sourceList$, sourceRealtime$).pipe(
      withLatestFrom(this.filterTrigger$),
      scan((acc, [val, filterEventsType]) => {
        acc = acc.filter((event) => event.type == filterEventsType);
        acc = val.concat(acc);
        const sortedAcc = acc.slice(0, 9);
        return sortedAcc;
      }, [])
    );
  }

  stopConnectorStatusSubscriptions() {
    this.realtime.unsubscribe(this.subscriptionEvents);
  }

  public runOperation(op: Operation, parameter?: any): Promise<IFetchResponse> {
    let body: any = {
      operation: op,
    };
    if (parameter) {
      body = {
        ...body,
        parameter: parameter,
      };
    }
    return this.client.fetch(`${BASE_URL}/${PATH_OPERATION_ENDPOINT}`, {
      headers: {
        "content-type": "application/json",
      },
      body: JSON.stringify(body),
      method: "POST",
    });
  }

  async getProcessorExtensions(): Promise<Object> {
    const response: IFetchResponse = await this.client.fetch(
      `${BASE_URL}/${PATH_EXTENSION_ENDPOINT}`,
      {
        headers: {
          accept: "application/json",
          "content-type": "application/json",
        },
        method: "GET",
      }
    );

    if (response.status != 200) {
      return undefined;
    }
    return response.json();
  }

  async getProcessorExtension(name: string): Promise<Extension> {
    const response: IFetchResponse = await this.client.fetch(
      `${BASE_URL}/${PATH_EXTENSION_ENDPOINT}/${name}`,
      {
        headers: {
          accept: "application/json",
          "content-type": "application/json",
        },
        method: "GET",
      }
    );

    if (response.status != 200) {
      return undefined;
    }
    //let result =  (await response.json()) as string[];
    return response.json();
  }

  async deleteProcessorExtension(name: string): Promise<string> {
    const response: IFetchResponse = await this.client.fetch(
      `${BASE_URL}/${PATH_EXTENSION_ENDPOINT}/${name}`,
      {
        headers: {
          accept: "application/json",
          "content-type": "application/json",
        },
        method: "DELETE",
      }
    );

    if (response.status != 200) {
      return undefined;
    }
    //let result =  (await response.json()) as string[];
    return response.json();
  }
}
