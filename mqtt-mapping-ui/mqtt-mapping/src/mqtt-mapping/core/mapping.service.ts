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
import { FetchClient, InventoryService, QueriesUtil } from "@c8y/client";
import * as _ from "lodash";
import { BehaviorSubject } from "rxjs";
import { BrokerConfigurationService } from "../../mqtt-configuration/broker-configuration.service";
import {
  C8YAPISubscription,
  Direction,
  Mapping,
  Operation,
} from "../../shared/mapping.model";
import {
  BASE_URL,
  MQTT_MAPPING_FRAGMENT,
  MQTT_MAPPING_TYPE,
  PATH_MAPPING_ENDPOINT,
  PATH_SUBSCRIPTIONS_ENDPOINT,
  PATH_SUBSCRIPTION_ENDPOINT,
} from "../../shared/util";
import { JSONProcessorInbound } from "../processor/impl/json-processor-inbound.service";
import { JSONProcessorOutbound } from "../processor/impl/json-processor-outbound.service";
import {
  ProcessingContext,
  ProcessingType,
  SubstituteValue,
} from "../processor/prosessor.model";

@Injectable({ providedIn: "root" })
export class MappingService {
  constructor(
    private inventory: InventoryService,
    private configurationService: BrokerConfigurationService,
    private jsonProcessorInbound: JSONProcessorInbound,
    private client: FetchClient
  ) {
    this.queriesUtil = new QueriesUtil();
  }

  queriesUtil: QueriesUtil;
  protected JSONATA = require("jsonata");
  private reload$: BehaviorSubject<void> = new BehaviorSubject(null);

  public async changeActivationMapping(parameter: any) {
    await this.configurationService.runOperation(
      Operation.ACTIVATE_MAPPING,
      parameter
    );
  }

  public async loadMappings(direction: Direction): Promise<Mapping[]> {
    let result: Mapping[] = [];

    const filter: object = {
      pageSize: 100,
      withTotalPages: true,
      type: MQTT_MAPPING_TYPE,
    };
    let query: any = { "c8y_mqttMapping.direction": direction };

    if (direction == Direction.INBOUND) {
      query = this.queriesUtil.addOrFilter(query, {
        __not: { __has: "c8y_mqttMapping.direction" },
      });
    }
    query = this.queriesUtil.addAndFilter(query, {
      type: { __has: "c8y_mqttMapping" },
    });

    let data = (await this.inventory.listQuery(query, filter)).data;
    // const query = {
    //       'c8y_mqttMapping.snoopStatus': direction
    // }
    //let data = (await this.inventory.list(filter)).data;

    data.forEach((m) =>
      result.push({
        ...m[MQTT_MAPPING_FRAGMENT],
        id: m.id,
      })
    );
    return result;
  }

  reloadMappings() {
    this.reload$.next();
  }

  listToReload(): BehaviorSubject<void> {
    return this.reload$;
  }

  async updateSubscriptions(sub: C8YAPISubscription): Promise<any> {
    let response = this.client.fetch(
      `${BASE_URL}/${PATH_SUBSCRIPTION_ENDPOINT}`,
      {
        headers: {
          "content-type": "application/json",
        },
        body: JSON.stringify(sub),
        method: "PUT",
      }
    );
    let data = await response;
    if (!data.ok) throw new Error(data.statusText)!;
    let m = await data.text();
    return m;
  }

  async getSubscriptions(): Promise<C8YAPISubscription> {
    let response = this.client.fetch(
      `${BASE_URL}/${PATH_SUBSCRIPTIONS_ENDPOINT}`,
      {
        headers: {
          "content-type": "application/json",
        },
        method: "GET",
      }
    );
    let data = await response;
    let sub = await data.json();
    return sub;
  }

  async saveMappings(mappings: Mapping[]): Promise<void> {
    mappings.forEach((m) => {
      this.inventory.update({
        c8y_mqttMapping: m,
        id: m.id,
      });
    });
  }

  async updateMapping(mapping: Mapping): Promise<Mapping> {
    let response = this.client.fetch(
      `${BASE_URL}/${PATH_MAPPING_ENDPOINT}/${mapping.id}`,
      {
        headers: {
          "content-type": "application/json",
        },
        body: JSON.stringify(mapping),
        method: "PUT",
      }
    );
    let data = await response;
    if (!data.ok) throw new Error(data.statusText)!;
    let m = await data.json();
    return m;
  }

  async deleteMapping(mapping: Mapping): Promise<string> {
    //let result = this.inventory.delete(mapping.id)
    let response = await this.client.fetch(
      `${BASE_URL}/${PATH_MAPPING_ENDPOINT}/${mapping.id}`,
      {
        headers: {
          "content-type": "application/json",
        },
        method: "DELETE",
      }
    );
    let data = await response;
    if (!data.ok) throw new Error(data.statusText)!;
    return data.text();
  }

  async createMapping(mapping: Mapping): Promise<Mapping> {
    let response = this.client.fetch(`${BASE_URL}/${PATH_MAPPING_ENDPOINT}`, {
      headers: {
        "content-type": "application/json",
      },
      body: JSON.stringify(mapping),
      method: "POST",
    });
    let data = await response;
    if (!data.ok) throw new Error(data.statusText)!;
    let m = await data.json();
    return m;
  }

  private initializeContext(
    mapping: Mapping,
    sendPayload: boolean
  ): ProcessingContext {
    let ctx: ProcessingContext = {
      mapping: mapping,
      topic: mapping.templateTopicSample,
      processingType: ProcessingType.UNDEFINED,
      cardinality: new Map<string, number>(),
      errors: [],
      mappingType: mapping.mappingType,
      postProcessingCache: new Map<string, SubstituteValue[]>(),
      sendPayload: sendPayload,
      requests: [],
    };
    return ctx;
  }

  async testResult(
    mapping: Mapping,
    sendPayload: boolean
  ): Promise<ProcessingContext> {
    let context = this.initializeContext(mapping, sendPayload);
    // if (mapping.direction == Direction.INBOUND) {
    this.jsonProcessorInbound.deserializePayload(context, mapping);
    this.jsonProcessorInbound.extractFromSource(context);
    await this.jsonProcessorInbound.substituteInTargetAndSend(context);
    // } else {
    //   this.jsonProcessorOuting.deserializePayload(context, mapping);
    //   this.jsonProcessorOuting.extractFromSource(context);
    //   await this.jsonProcessorOuting.substituteInTargetAndSend(context);
    // }

    // The producing code (this may take some time)
    return context;
  }

  public evaluateExpression(json: JSON, path: string): JSON {
    let result: any = "";
    if (path != undefined && path != "" && json != undefined) {
      const expression = this.JSONATA(path);
      result = expression.evaluate(json) as JSON;
    }
    return result;
  }
}
