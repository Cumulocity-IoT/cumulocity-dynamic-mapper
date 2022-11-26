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
import { InventoryService, IResult } from '@c8y/client';
import * as _ from 'lodash';
import { BehaviorSubject } from 'rxjs';
import { map } from 'rxjs/operators';
import { BrokerConfigurationService } from '../../mqtt-configuration/broker-configuration.service';
import { Mapping, Operation } from '../../shared/mapping.model';
import { BASE_URL, MQTT_MAPPING_FRAGMENT, MQTT_MAPPING_TYPE } from '../../shared/util';
import { JSONProcessor } from '../processor/impl/json-processor.service';
import { C8YRequest, ProcessingContext, ProcessingType, SubstituteValue } from '../processor/prosessor.model';

@Injectable({ providedIn: 'root' })
export class MappingService {
  constructor(
    private inventory: InventoryService,
    private configurationService: BrokerConfigurationService,
    private jsonProcessor: JSONProcessor) { }

  private agentId: string;
  protected JSONATA = require("jsonata");
  private reload$: BehaviorSubject<void> = new BehaviorSubject(null);

  public async changeActivationMapping(parameter: any) {
    await this.configurationService.runOperation(Operation.ACTIVATE_MAPPING, parameter);
  }
  public async loadMappings(): Promise<Mapping[]> {
    let result: Mapping[] = [];
    if (!this.agentId) {
      this.agentId = await this.configurationService.initializeMQTTAgent();
    }
    console.log("MappingService: Found MQTTAgent!", this.agentId);

    const filter: object = {
      pageSize: 100,
      withTotalPages: true,
      type: MQTT_MAPPING_TYPE,
    };
    let data = (await this.inventory.list(filter)).data;

    data.forEach(m => result.push({
      ...m[MQTT_MAPPING_FRAGMENT],
      id: m.id
    }))
    return result;
  }

  reloadMappings() {
    this.reload$.next();
  }
  
  listToReload(): BehaviorSubject<void> {
    return this.reload$;
  }

  async saveMappings(mappings: Mapping[]): Promise<void> {
    mappings.forEach(m => {
      this.inventory.update({
        c8y_mqttMapping: m,
        id: m.id,
      })
    })
  }

  async updateMapping(mapping: Mapping): Promise<Mapping> {
    const { data, res } = await this.inventory.update({
      c8y_mqttMapping: mapping,
      id: mapping.id,
    })
    return mapping;
  }

  async deleteMapping(mapping: Mapping): Promise<IResult<null>> {
    let result = this.inventory.delete(mapping.id)
    return result
  }

  async createMapping(mapping: Mapping): Promise<Mapping> {
    {
      const { data, res } = await this.inventory.create({
        c8y_mqttMapping: mapping,
        type: MQTT_MAPPING_TYPE,
      });
      mapping.id = data.id;
    }
    {
      const { data, res } = await this.inventory.update({
        c8y_mqttMapping: mapping,
        id: mapping.id,
      })
    }
    return mapping;
  }

  private initializeContext(mapping: Mapping, sendPayload: boolean): ProcessingContext {
    let ctx: ProcessingContext = {
      mapping: mapping,
      topic: mapping.templateTopicSample,
      processingType: ProcessingType.UNDEFINED,
      cardinality: new Map<string, number>(),
      errors: [],
      mappingType: mapping.mappingType,
      postProcessingCache: new Map<string, SubstituteValue[]>(),
      sendPayload: sendPayload,
      requests: []
    }
    return ctx;
  }

  async testResult(mapping: Mapping, sendPayload: boolean): Promise<ProcessingContext> {
    let context = this.initializeContext(mapping, sendPayload);
    this.jsonProcessor.deserializePayload(context, mapping);
    this.jsonProcessor.extractFromSource(context);
    await this.jsonProcessor.substituteInTargetAndSend(context);

    // The producing code (this may take some time)
    return context;
  }

  public evaluateExpression(json: JSON, path: string): JSON {
    let result: any = '';
    if (path != undefined && path != '' && json != undefined) {
      const expression = this.JSONATA(path)
      result = expression.evaluate(json) as JSON
    }
    return result;
  }

}