import { Injectable } from '@angular/core';
import { AlarmService, EventService, FetchClient, IAlarm, IdentityService, IEvent, IExternalIdentity, IFetchResponse, IManagedObject, IMeasurement, InventoryService, IResult, IResultList, MeasurementService } from '@c8y/client';
import { JSONPath } from 'jsonpath-plus';
import { MQTTMapping } from '../mqtt-configuration.model';
import * as _ from 'lodash';

@Injectable({ providedIn: 'root' })
export class MQTTMappingService {
  constructor(
    private inventory: InventoryService,
    private identity: IdentityService,
    private event: EventService,
    private alarm: AlarmService,
    private measurement: MeasurementService,
    private client: FetchClient) {
    // find mqtt agent for tesing
  }

  mappingId: string;
  agentId: string;

  private readonly MAPPING_TYPE = 'c8y_mqttMapping';
  private readonly MAPPING_FRAGMENT = 'c8y_mqttMapping';
  private readonly PATH_OPERATION_ENDPOINT = 'operation';
  private readonly BASE_URL = 'service/generic-mqtt-agent';
  private JSONATA = require("jsonata");

  async initializeMQTTAgent(): Promise<string>{
    if (!this.agentId) {
      const identity: IExternalIdentity = {
        type: 'c8y_Serial',
        externalId: 'MQTT_AGENT'
      };

      const { data, res } = await this.identity.detail(identity);
      this.agentId = data.managedObject.id.toString();
      return this.agentId;
    }
  }

  async loadMappings(): Promise<MQTTMapping[]> {
    const filter: object = {
      pageSize: 100,
      withTotalPages: true
    };

    const query = {
      type: this.MAPPING_TYPE
    }
    const response: IResultList<IManagedObject> = await this.inventory.listQuery(query, filter);
    if (response.data && response.data.length > 0) {
      this.mappingId = response.data[0].id;
      console.log("Found mqtt mapping:", this.mappingId, response.data[0][this.MAPPING_FRAGMENT])
      return response.data[0][this.MAPPING_FRAGMENT] as MQTTMapping[];
    } else {
      console.log("No mqtt mapping found!")
      return [];
    }
  }


  async initalizeMappings(): Promise<MQTTMapping[]> {
    const response: IResult<IManagedObject> = await this.inventory.create({
      c8y_mqttMapping: [],
      name: "MQTT-Mapping",
      type: this.MAPPING_TYPE
    });
    return [];
  }

  async saveMappings(mappings: MQTTMapping[]): Promise<IResult<IManagedObject>> {
    return this.inventory.update({
      c8y_mqttMapping: mappings,
      id: this.mappingId,
    });
  }

  async reloadMappings(): Promise<IFetchResponse> {
    return this.client.fetch(`${this.BASE_URL}/${this.PATH_OPERATION_ENDPOINT}`, {
      headers: {
        'content-type': 'application/json',
      },
      body: JSON.stringify({"tenant": this.client.tenant, "operation": "RELOAD"}),
      method: 'POST',
    });
  }


  async testResult(mapping: MQTTMapping, simulation: boolean): Promise <any> {
    let result = JSON.parse(mapping.target);
    if (!this.agentId) {
      console.error("Need to intialize MQTTAgent:", this.agentId);
      result = JSON.stringify(mapping.target);
    } else {
      console.log("MQTTAgent is already initialized:", this.agentId);
      mapping.substitutions.forEach(sub => {
        console.log("Looking substitution for:", sub.pathSource, mapping.source, result);
        // test for JSONPATH implementation
        //let s = JSONPath({ path: "$." + sub.pathSource, json: JSON.parse(mapping.source), wrap: false });
        let s = this.evaluateExpression(JSON.parse(mapping.source), sub.pathSource);
        if (!s || s == '') {
          // test for JSONPATH implementation
          //if ("$." + sub.pathSource != '$.TOPIC') {
          if (sub.pathSource != 'TOPIC') {
            console.error("No substitution for:", sub.pathSource, s, mapping.source);
            throw Error("Error: substitution not found:" + sub.pathSource);
          } else {
            s = this.agentId;
          }
        }
        _.set(result, sub.pathTarget, s)
      })
  
      // for simulation replace source id with agentId
      if (simulation) {
        result.source.id = this.agentId;
        result.time = new Date().toISOString();
      }
    }

    // The producing code (this may take some time)
    return result;
  }

  async sendTestResult(mapping: MQTTMapping): Promise<IResult<IEvent | IAlarm | IMeasurement>> {
    let test_payload = await this.testResult(mapping, true);

    if (mapping.targetAPI == 'event') {
      let p: IEvent = test_payload as IEvent;
      if (p != null) {
        return this.event.create(p);
      } else {
        throw new Error("Payload is not a valid:" + mapping.targetAPI);
      }
    } else if (mapping.targetAPI == 'alarm') {
      let p: IAlarm = test_payload as IAlarm;
      if (p != null) {
        return this.alarm.create(p);
      } else {
        throw new Error("Payload is not a valid:" + mapping.targetAPI);
      }
    } else if (mapping.targetAPI == 'measurement') {
      let p: IMeasurement = test_payload as IMeasurement;
      if (p != null) {
        return this.measurement.create(p);
      } else {
        throw new Error("Payload is not a valid:" + mapping.targetAPI);
      }
    }
    return null;
  }

  public evaluateExpression(json: JSON, path: string): string {
      const expression = this.JSONATA(path)
      return expression.evaluate(json)
      //return JSON.stringify(expression.evaluate(json), null, 4)
  }
}