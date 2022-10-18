import { Injectable } from '@angular/core';
import { AlarmService, EventService, FetchClient, IAlarm, IdentityService, IEvent, IExternalIdentity, IFetchResponse, IManagedObject, IMeasurement, InventoryService, IResult, IResultList, MeasurementService } from '@c8y/client';
import { API, Mapping, Operation } from '../../shared/configuration.model';
import * as _ from 'lodash';
import { BASE_URL, MAPPING_FRAGMENT, MAPPING_TYPE, PATH_OPERATION_ENDPOINT, TIME, TOKEN_DEVICE_TOPIC } from '../../shared/helper';
import { BrokerConfigurationService } from '../../mqtt-configuration/broker-configuration.service';
import { AlertService } from '@c8y/ngx-components';

@Injectable({ providedIn: 'root' })
export class MappingService {
  constructor(
    private inventory: InventoryService,
    private identity: IdentityService,
    private event: EventService,
    private alarm: AlarmService,
    private measurement: MeasurementService,
    private client: FetchClient,
    private configurationService: BrokerConfigurationService,
    private alert: AlertService) { }

  private agentId: string;
  private testDeviceId: string;
  private mappingId: string;
  private JSONATA = require("jsonata");


  async loadTestDevice(): Promise<void> {
    if (!this.testDeviceId) {
      this.testDeviceId = await this.configurationService.initializeTestDevice();
    }
  }

  async loadMappings(): Promise<Mapping[]> {
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
      this.mappingId = data.managedObject.id as string;
      const response: IResult<IManagedObject> = await this.inventory.detail(this.mappingId);
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
      this.mappingId = response.data.id;
      // return empty mapping
      return [];
    }
  }

  async saveMappings(mappings: Mapping[]): Promise<IResult<IManagedObject>> {
    return this.inventory.update({
      c8y_mqttMapping: mappings,
      id: this.mappingId,
    });
  }

  async testResult(mapping: Mapping, simulation: boolean): Promise<any> {
    let result = JSON.parse(mapping.target);
    let substitutionTimeExists = false;
    if (!this.testDeviceId) {
      console.error("Need to intialize MQTT test device:", this.testDeviceId);
      result = mapping.target;
    } else {
      console.log("MQTT test device is already initialized:", this.testDeviceId);
      mapping.substitutions.forEach(sub => {
        console.log("Looking substitution for:", sub.pathSource, mapping.source, result);
        if (sub.pathTarget != TOKEN_DEVICE_TOPIC) {
          let s = this.evaluateExpression(JSON.parse(mapping.source), sub.pathSource, true);
          if (!s || s == '') {
            if (sub.pathSource != TOKEN_DEVICE_TOPIC) {
              console.error("No substitution for:", sub.pathSource, s, mapping.source);
              throw Error("Error: substitution not found:" + sub.pathSource);
            } else {
              s = this.testDeviceId;
            }
          }
          _.set(result, sub.pathTarget, s)
        }

        if (sub.pathTarget == TIME) {
          substitutionTimeExists = true;
        }

      })

      // for simulation replace source id with agentId
      if (simulation && mapping.targetAPI != API.INVENTORY) {
        result.source.id = this.testDeviceId;
        result.time = new Date().toISOString();
      }

      // no substitution fot the time property exists, then use the system time
      if (!substitutionTimeExists) {
        result.time = new Date().toISOString();
      }
    }

    // The producing code (this may take some time)
    return result;
  }

  async sendTestResult(mapping: Mapping): Promise<string> {
    let result: Promise<IResult<any>>;
    let test_payload = await this.testResult(mapping, true);
    let error: string = '';

    if (mapping.targetAPI == API.EVENT) {
      let p: IEvent = test_payload as IEvent;
      if (p != null) {
        result = this.event.create(p);
      } else {
        error = "Payload is not a valid:" + mapping.targetAPI;
      }
    } else if (mapping.targetAPI == API.ALARM) {
      let p: IAlarm = test_payload as IAlarm;
      if (p != null) {
        result = this.alarm.create(p);
      } else {
        error = "Payload is not a valid:" + mapping.targetAPI;
      }
    } else if (mapping.targetAPI == API.MEASUREMENT) {
      let p: IMeasurement = test_payload as IMeasurement;
      if (p != null) {
        result = this.measurement.create(p);
      } else {
        error = "Payload is not a valid:" + mapping.targetAPI;
      }
    } else {
      let p: IManagedObject = test_payload as IManagedObject;
      if (p != null) {
        if (mapping.updateExistingDevice) {
          result = this.inventory.update(p);
        } else {
          result = this.inventory.create(p);
        }
      } else {
        error = "Payload is not a valid:" + mapping.targetAPI;
      }
    }

    if ( error != ''){
      this.alert.danger("Failed to tested mapping: " + error);
      return '';
    }

    try {
      let { data, res } = await result;
      //console.log ("My data:", data );
      if ((res.status == 200 || res.status == 201)) {
        this.alert.success("Successfully tested mapping!");
        return data;
      } else {
        let e = await res.text();
        this.alert.danger("Failed to tested mapping: " + e);
        return '';
      }
    } catch (e) {
      let { data, res } = await e;
      this.alert.danger("Failed to tested mapping: " + data.message);
      return '';
    }

  }

  public evaluateExpression(json: JSON, path: string, flat: boolean): string {
    let result: any = '';
    if (path != undefined && path != '' && json != undefined) {
      const expression = this.JSONATA(path)
      result = expression.evaluate(json) as JSON
      if (flat) {
        if (Array.isArray(result)) {
          result = result[0];
        }
      } else {
        result = JSON.stringify(result, null, 4);
      }
    }
    return result;
  }
}