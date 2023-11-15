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
  FetchClient,
  IdentityService,
  IExternalIdentity,
  IFetchResponse,
  Realtime,
} from "@c8y/client";
import {
  AGENT_ID,
  BASE_URL,
  CONNECTOR_STATUS_FRAGMENT,
  PATH_CONFIGURATION_CONNECTION_ENDPOINT,
  PATH_CONFIGURATION_SERVICE_ENDPOINT,
  PATH_EXTENSION_ENDPOINT,
  PATH_FEATURE_ENDPOINT,
  PATH_OPERATION_ENDPOINT,
  ConnectorConfiguration,
  ConnectorPropertyConfiguration,
  Extension,
  Feature,
  Operation,
  ServiceConfiguration,
  ConnectorStatus,
  Status,
  ConnectorConfigurationCombined,
  PATH_STATUS_CONNECTORS_ENDPOINT,
} from "../../shared";

import { BehaviorSubject } from "rxjs";

@Injectable({ providedIn: "root" })
export class BrokerConfigurationService {
  constructor(private client: FetchClient, private identity: IdentityService) {
    this.realtime = new Realtime(this.client);
  }

  private _agentId: Promise<string>;

  private _connectorConfigurationCombined: ConnectorConfigurationCombined[] =
    [];
  private _feature: Promise<Feature>;
  private realtime: Realtime;

  async getDynamicMappingServiceAgent(): Promise<string> {
    if (!this._agentId) {
      this._agentId = this.getUncachedDynamicMappingServiceAgent();
    }
    return this._agentId;
  }

  async getUncachedDynamicMappingServiceAgent(): Promise<string> {
    const identity: IExternalIdentity = {
      type: "c8y_Serial",
      externalId: AGENT_ID,
    };
    const { data, res } = await this.identity.detail(identity);
    if (res.status < 300) {
      const agentId = data.managedObject.id.toString();
      console.log("BrokerConfigurationService: Found BrokerAgent", agentId);
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

  async getConnectorSpecifications(): Promise<
    ConnectorPropertyConfiguration[]
  > {
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

    return (await response.json()) as ConnectorPropertyConfiguration[];
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
      this._feature = this.getUncachedFeatures();
    }
    return this._feature;
  }

  async getUncachedFeatures(): Promise<Feature> {
    const response = await this.client.fetch(
      `${BASE_URL}/${PATH_FEATURE_ENDPOINT}`,
      {
        method: "GET",
      }
    );
    return await response.json();
  }

  async subscribeMonitoringChannel(): Promise<object> {
    const agentId = await this.getDynamicMappingServiceAgent();
    console.log("Started subscription:", agentId);
    this.getConnectorStatus().then((status) => {
      // for (const [key, value] of Object.entries(status)) {
      //   console.log(`${key}: ${value}`);
      // }
      this._connectorConfigurationCombined.forEach((cc) => {
        if (status[cc.configuration.ident]) {
          cc.status$.next(status[cc.configuration.ident]);
        }
      });
      console.log("New monitoring event", status);
    });
    const sub = this.realtime.subscribe(
      `/managedobjects/${agentId}`,
      this.updateStatus.bind(this)
    );
    return sub;
  }

  unsubscribeFromMonitoringChannel(subscription: object) {
    this.realtime.unsubscribe(subscription);
  }

  private updateStatus(p: object): void {
    let payload = p["data"]["data"];
    let status: ConnectorStatus = payload[CONNECTOR_STATUS_FRAGMENT];
    for (const [key, value] of Object.entries(status)) {
      console.log(`${key}: ${value}`);
    }
    this._connectorConfigurationCombined.forEach((cc) => {
      if (status[cc.configuration.ident]) {
        cc.status$.next(status[cc.configuration?.ident]);
      }
    });
  }

  runOperation(op: Operation, parameter?: any): Promise<IFetchResponse> {
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
