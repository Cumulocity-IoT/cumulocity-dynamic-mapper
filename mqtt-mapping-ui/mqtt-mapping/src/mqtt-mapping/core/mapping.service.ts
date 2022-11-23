import { Injectable } from '@angular/core';
import { FetchClient, IdentityService, IManagedObject, InventoryService, IResult } from '@c8y/client';
import * as _ from 'lodash';
import { map } from 'rxjs/operators';
import { BrokerConfigurationService } from '../../mqtt-configuration/broker-configuration.service';
import { Mapping } from '../../shared/mapping.model';
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
      mappingType: mapping.mappingType,
      postProcessingCache: new Map<string, SubstituteValue[]>(),
      sendPayload: sendPayload,
      requests: []
    }
    return ctx;
  }

  async testResult(mapping: Mapping, sendPayload: boolean): Promise<C8YRequest[]> {
    let context = this.initializeContext(mapping, sendPayload);
    this.jsonProcessor.deserializePayload(context, mapping);
    this.jsonProcessor.extractFromSource(context);
    await this.jsonProcessor.substituteInTargetAndSend(context);

    // The producing code (this may take some time)
    return context.requests;
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