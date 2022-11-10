import { Injectable } from "@angular/core";
import { IEvent, IAlarm, IMeasurement, IManagedObject, IResult, IExternalIdentity, IOperation } from "@c8y/client";
import { AlertService } from "@c8y/ngx-components";
import { API } from "../../shared/mapping.model";
import { FacadeIdentityService } from "./facade-identity.service";
import { FacadeInventoryService } from "./facade-inventory.service";
import { ProcessingContext } from "../processor/prosessor.model";
import { FacadeAlarmService } from "./facade-alarm.service";
import { FacadeEventService } from "./facade-event.service";
import { FacadeMeasurementService } from "./facade-measurement.service";
import { FacadeOperationService } from "./facade-operation.service";

@Injectable({ providedIn: 'root' })
export class C8YClient {
  constructor(
    private inventory: FacadeInventoryService,
    private identity: FacadeIdentityService,
    private event: FacadeEventService,
    private alarm: FacadeAlarmService,
    private measurement: FacadeMeasurementService,
    private operation: FacadeOperationService,
    private alert: AlertService) { }

  async createMEAO(context: ProcessingContext) {
    let result: any;
    let error: string = '';
    let currentRequest = context.requests[context.requests.length-1].request;
    if (context.mapping.targetAPI == API.EVENT.name) {
      let p: IEvent = currentRequest as any;
      if (p != null) {
        result = this.event.create(p, context);
      } else {
        error = "Payload is not a valid:" + context.mapping.targetAPI;
      }
    } else if (context.mapping.targetAPI == API.ALARM.name) {
      let p: IAlarm = currentRequest as any;
      if (p != null) {
        result = this.alarm.create(p,context);
      } else {
        error = "Payload is not a valid:" + context.mapping.targetAPI;
      }
    } else if (context.mapping.targetAPI == API.MEASUREMENT.name) {
      let p: IMeasurement = currentRequest as any;
      if (p != null) {
        result = this.measurement.create(p,context);
      } else {
        error = "Payload is not a valid:" + context.mapping.targetAPI;
      }
    } else if (context.mapping.targetAPI == API.OPERATION.name) {
      let p: IOperation = currentRequest as any;
      if (p != null) {
        result = this.operation.create(p,context);
      } else {
        error = "Payload is not a valid:" + context.mapping.targetAPI;
      }
    } else {
      let p: IManagedObject = currentRequest as any;
      if (p != null) {
        if (context.mapping.updateExistingDevice) {
          result = this.inventory.update(p, context);
        } else {
          result = this.inventory.create(p, context);
        }
      } else {
        error = "Payload is not a valid:" + context.mapping.targetAPI;
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

  async upsertDevice(identity: IExternalIdentity, context: ProcessingContext): Promise<IManagedObject> {
    
    let deviceId: string = await this.resolveExternalId(identity, context);
    let currentRequest = context.requests[context.requests.length-1].request;
    let device: Partial<IManagedObject> = {
      ...currentRequest,
      c8y_IsDevice: {},
      c8y_mqttMapping_TestDevice: {},
      com_cumulocity_model_Agent: {}
    }

    if (deviceId) {
      const response: IResult<IManagedObject> = await this.inventory.update(device, context);
      return response.data;
    } else {
      const response: IResult<IManagedObject> = await this.inventory.create(device, context);
      //create identity for mo
      identity = {
        ...identity,
        managedObject: {
          id: response.data.id
        }
      }
      const { data, res } = await this.identity.create(identity, context);
      return response.data;
    }
  }

  async resolveExternalId(identity: IExternalIdentity, context: ProcessingContext): Promise<string> {
    try {
      const { data, res } = await this.identity.detail(identity, context);
      return data.managedObject.id as string;
    } catch (e) {
      console.log(`External id ${identity.externalId} doesn't exist!`);
      return;
    }
  }

}