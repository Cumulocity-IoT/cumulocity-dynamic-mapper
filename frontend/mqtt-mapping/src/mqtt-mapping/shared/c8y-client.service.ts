import { Injectable } from "@angular/core";
import { InventoryService, IdentityService, EventService, AlarmService, MeasurementService, IEvent, IAlarm, IMeasurement, IManagedObject, IResult, IExternalIdentity } from "@c8y/client";
import { AlertService } from "@c8y/ngx-components";
import { Mapping, API } from "../../shared/mapping.model";

@Injectable({ providedIn: 'root' })
export class C8YClient {
  constructor(
    private inventory: InventoryService,
    private identity: IdentityService,
    private event: EventService,
    private alarm: AlarmService,
    private measurement: MeasurementService,
    private alert: AlertService) { }

  async createMEAO(targetAPI: string, payloadTarget: JSON, mapping: Mapping) {
    let result: any;
    let error: string = '';
    if (targetAPI == API.EVENT.name) {
      let p: IEvent = payloadTarget as any;
      if (p != null) {
        result = this.event.create(p);
      } else {
        error = "Payload is not a valid:" + targetAPI;
      }
    } else if (targetAPI == API.ALARM.name) {
      let p: IAlarm = payloadTarget as any;
      if (p != null) {
        result = this.alarm.create(p);
      } else {
        error = "Payload is not a valid:" + targetAPI;
      }
    } else if (targetAPI == API.MEASUREMENT.name) {
      let p: IMeasurement = payloadTarget as any;
      if (p != null) {
        result = this.measurement.create(p);
      } else {
        error = "Payload is not a valid:" + targetAPI;
      }
    } else {
      let p: IManagedObject = payloadTarget as any;
      if (p != null) {
        if (mapping.updateExistingDevice) {
          result = this.inventory.update(p);
        } else {
          result = this.inventory.create(p);
        }
      } else {
        error = "Payload is not a valid:" + targetAPI;
      }
    }

    if (error != '') {
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


  async upsertDevice(payload: any, externalId: string, externalIdType: string): Promise<IManagedObject> {
    let deviceId: string = await this.resolveExternalId(externalId, externalIdType);
    let device: Partial<IManagedObject> = {
      ...payload,
      c8y_IsDevice: {},
      c8y_mqttMapping_TestDevice: {},
      com_cumulocity_model_Agent: {}
    }
    if (deviceId) {
      const response: IResult<IManagedObject> = await this.inventory.update(device);
      return response.data;
    } else {
      const response: IResult<IManagedObject> = await this.inventory.create(device);
      //create identity for mo
      let identity = {
        type: externalIdType,
        externalId: externalId,
        managedObject: {
          id: response.data.id
        }
      }
      const { data, res } = await this.identity.create(identity);
      return response.data;
    }
  }

  async resolveExternalId(externalId: string, externalIdType: string): Promise<string> {
    let identity: IExternalIdentity = {
      type: externalIdType,
      externalId: externalId
    };
    try {
      const { data, res } = await this.identity.detail(identity);
      return data.managedObject.id as string;
    } catch (e) {
      console.log(`External id ${externalId} doesn't exist!`);
      return;
    }
  }

}