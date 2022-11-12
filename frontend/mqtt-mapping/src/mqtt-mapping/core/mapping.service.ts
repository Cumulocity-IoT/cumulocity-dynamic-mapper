import { Injectable } from '@angular/core';
import { FetchClient, IdentityService, IExternalIdentity, IFetchResponse, IManagedObject, InventoryService, IResult } from '@c8y/client';
import * as _ from 'lodash';
import { BrokerConfigurationService } from '../../mqtt-configuration/broker-configuration.service';
import { Mapping } from '../../shared/mapping.model';
import { BASE_URL, MAPPING_FRAGMENT, MAPPING_TYPE, PATH_TYPE_REGISTRY_ENDPOINT } from '../../shared/util';
import { JSONProcessor } from '../processor/impl/json-processor.service';
import { C8YRequest, ProcessingContext, ProcessingType, SubstituteValue } from '../processor/prosessor.model';

@Injectable({ providedIn: 'root' })
export class MappingService {
  constructor(
    private inventory: InventoryService,
    private identity: IdentityService,
    private configurationService: BrokerConfigurationService,
    private jsonProcessor: JSONProcessor,
    private client: FetchClient) { }

  private agentId: string;
  private managedObjectMapping: IManagedObject;
  protected JSONATA = require("jsonata");

  public async loadMappings(): Promise<Mapping[]> {
    if (!this.agentId) {
      this.agentId = await this.configurationService.initializeMQTTAgent();
    }
    console.log("MappingService: Found MQTTAgent!", this.agentId);

    let identity: IExternalIdentity = {
      type: 'c8y_Serial',
      externalId: MAPPING_TYPE
    };
    try {
      const { data, res } = await this.identity.detail(identity);
      const response: IResult<IManagedObject> = await this.inventory.detail(data.managedObject.id);
      this.managedObjectMapping = response.data;
      return response.data[MAPPING_FRAGMENT] as Mapping[];
    } catch (e) {
      console.log("So far no mqttMapping generated!")
      // create new mapping mo
      const response: IResult<IManagedObject> = await this.inventory.create({
        c8y_mqttMapping: [],
        name: "MQTT-Mapping",
        type: MAPPING_TYPE
      });

      //create identity for mo
      identity = {
        ...identity,
        managedObject: {
          id: response.data.id
        }
      }
      const { data, res } = await this.identity.create(identity);
      this.managedObjectMapping = response.data;
      // return empty mapping
      return [];
    }
  }

  async saveMappings(mappings: Mapping[]): Promise<IResult<IManagedObject>> {
    return this.inventory.update({
      c8y_mqttMapping: mappings,
      id: this.managedObjectMapping.id,
    });
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

  async getRegisteredTypes(type: string): Promise<string[]> {
    const response: IFetchResponse = await this.client.fetch(`${BASE_URL}/${PATH_TYPE_REGISTRY_ENDPOINT}/${type}`, {
      headers: {
        accept: 'application/json',
        'content-type': 'application/json'
      },
      method: 'GET',
    });

    if (response.status != 200) {
      return undefined;
    }
    //let result =  (await response.json()) as string[];
    return response.json();
  }

}