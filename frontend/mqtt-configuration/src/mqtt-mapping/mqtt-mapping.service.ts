import { Injectable } from '@angular/core';
import { AlarmService, EventService, FetchClient, IAlarm, IdentityService, IEvent, IExternalIdentity, IFetchResponse, IManagedObject, IMeasurement, InventoryService, IResult, IResultList, MeasurementService } from '@c8y/client';
import { JSONPath } from 'jsonpath-plus';
import { MQTTMapping } from '../mqtt-configuration.model';

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

  private readonly MAPPING_TYPE = 'c8y_mqttMapping_type';

  private readonly MAPPING_AGENT_TYPE = 'c8y_mqttMapping_type';

  private readonly MAPPING_FRAGMENT = 'c8y_mqttMapping';

  private readonly PATH_MAPPING_ENDPOINT = 'mapping';

  private readonly BASE_URL = 'service/generic-mqtt-agent';


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
      return undefined;
    }
  }

  async saveMappings(mappings: MQTTMapping[]): Promise<IResult<IManagedObject>> {
    return this.inventory.update({
      c8y_mqttMapping: mappings,
      id: this.mappingId,
    });
  }

  async reloadMappings(): Promise<IFetchResponse> {
    return this.client.fetch(`${this.BASE_URL}/${this.PATH_MAPPING_ENDPOINT}`, {
      headers: {
        'content-type': 'text/plain',
      },
      body: this.client.tenant,
      //body: JSON.stringify({"tenant": this.client.tenant}),
      method: 'PUT',
    });
  }


  async testResult(mapping: MQTTMapping, simulation: boolean): Promise <string> {
    let result = mapping.target;
    if (!this.agentId) {
      console.error("Need to intialize MQTTAgent:", this.agentId);
      result = '';
    } else {
      console.log("MQTTAgent is already intialized:", this.agentId);
      mapping.substitutions.forEach(sub => {
        let s = JSONPath({ path: sub.jsonPath, json: JSON.parse(mapping.source), wrap: false });
        if (!s || s == '') {
          if (sub.jsonPath != '$.TOPIC') {
            console.error("No substitution for:", sub.jsonPath, s, mapping.source);
            throw Error("Error: substitution not found:" + sub.jsonPath);
          } else {
            s = this.agentId;
          }
        }
        result = result.replace(sub.name, s);
      })
  
      // for simulation replace source id with agentId
      if (simulation) {
        const payload = JSON.parse(result);
        payload.source.id = this.agentId;
        payload.time = new Date().toISOString();
        result = JSON.stringify(payload);
      }
    }

    // The producing code (this may take some time)
    return result;
  }

  async sendTestResult(mapping: MQTTMapping): Promise<IResult<IEvent | IAlarm | IMeasurement>> {
    let test_payload_string = await this.testResult(mapping, true);
    let test_payload = JSON.parse(test_payload_string);

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

}