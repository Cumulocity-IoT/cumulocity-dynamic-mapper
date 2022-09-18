import { Injectable } from '@angular/core';
import { AlarmService, EventService, FetchClient, IAlarm, IdentityService, IEvent, IExternalIdentity, IFetchResponse, IManagedObject, IMeasurement, InventoryService, IResult, IResultList, MeasurementService } from '@c8y/client';
import { API, Mapping } from '../../shared/mqtt-configuration.model';
import * as _ from 'lodash';
import { TOKEN_DEVICE_TOPIC } from '../../shared/mqtt-helper';

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

  private mappingId: string;
  private agentId: string;

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

      this.agentId = null;
      const { data, res } = await this.identity.detail(identity);
      if (res.status < 300){
        this.agentId = data.managedObject.id.toString();
      }
      return this.agentId;
    }
  }

  async loadMappings(): Promise<Mapping[]> {
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
      return response.data[0][this.MAPPING_FRAGMENT] as Mapping[];
    } else {
      console.log("No mqtt mapping found!")
      return [];
    }
  }


  async initalizeMappings(): Promise<Mapping[]> {
    const response: IResult<IManagedObject> = await this.inventory.create({
      c8y_mqttMapping: [],
      name: "MQTT-Mapping",
      type: this.MAPPING_TYPE
    });
    return [];
  }

  async saveMappings(mappings: Mapping[]): Promise<IResult<IManagedObject>> {
    return this.inventory.update({
      c8y_mqttMapping: mappings,
      id: this.mappingId,
    });
  }

  async activateMappings(): Promise<IFetchResponse> {
    return this.client.fetch(`${this.BASE_URL}/${this.PATH_OPERATION_ENDPOINT}`, {
      headers: {
        'content-type': 'application/json',
      },
      body: JSON.stringify({"operation": "RELOAD"}),
      method: 'POST',
    });
  }


  async testResult(mapping: Mapping, simulation: boolean): Promise <any> {
    let result = JSON.parse(mapping.target);
    if (!this.agentId) {
      console.error("Need to intialize MQTTAgent:", this.agentId);
      result = mapping.target;
    } else {
      console.log("MQTTAgent is already initialized:", this.agentId);
      mapping.substitutions.forEach(sub => {
        console.log("Looking substitution for:", sub.pathSource, mapping.source, result);
        if ( sub.pathTarget != TOKEN_DEVICE_TOPIC) {
          let s = this.evaluateExpression(JSON.parse(mapping.source), sub.pathSource);
          if (!s || s == '') {
            if (sub.pathSource != TOKEN_DEVICE_TOPIC) {
              console.error("No substitution for:", sub.pathSource, s, mapping.source);
              throw Error("Error: substitution not found:" + sub.pathSource);
            } else {
              s = this.agentId;
            }
          }
          _.set(result, sub.pathTarget, s)
        }
      })
  
      // for simulation replace source id with agentId
      if (simulation && mapping.targetAPI!=API.INVENTORY) {
        result.source.id = this.agentId;
        result.time = new Date().toISOString();
      }
    }

    // The producing code (this may take some time)
    return result;
  }

  async sendTestResult(mapping: Mapping): Promise<IResult<IEvent | IAlarm | IMeasurement | IManagedObject>> {
    let test_payload = await this.testResult(mapping, true);

    if (mapping.targetAPI == API.EVENT) {
      let p: IEvent = test_payload as IEvent;
      if (p != null) {
        return this.event.create(p);
      } else {
        throw new Error("Payload is not a valid:" + mapping.targetAPI);
      }
    } else if (mapping.targetAPI == API.ALARM) {
      let p: IAlarm = test_payload as IAlarm;
      if (p != null) {
        return this.alarm.create(p);
      } else {
        throw new Error("Payload is not a valid:" + mapping.targetAPI);
      }
    } else if (mapping.targetAPI == API.MEASUREMENT) {
      let p: IMeasurement = test_payload as IMeasurement;
      if (p != null) {
        return this.measurement.create(p);
      } else {
        throw new Error("Payload is not a valid:" + mapping.targetAPI);
      }
    } else {
      let p: IManagedObject = test_payload as IManagedObject;
      if (p != null) {
        return this.inventory.create(p);
      } else {
        throw new Error("Payload is not a valid:" + mapping.targetAPI);
      }
    }
  }

  public evaluateExpression(json: JSON, path: string): string {
      let result = '';
      if ( path != undefined && path != '' && json != undefined ) {
        const expression = this.JSONATA(path)
        result = expression.evaluate(json)
      }
      return result;
  }
}