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
import {
  IEvent,
  IAlarm,
  IMeasurement,
  IManagedObject,
  IResult,
  IExternalIdentity,
  IOperation,
} from "@c8y/client";
import { AlertService } from "@c8y/ngx-components";
import { API } from "../../shared/mapping.model";
import { FacadeIdentityService } from "./facade-identity.service";
import { FacadeInventoryService } from "./facade-inventory.service";
import { ProcessingContext } from "../processor/prosessor.model";
import { FacadeAlarmService } from "./facade-alarm.service";
import { FacadeEventService } from "./facade-event.service";
import { FacadeMeasurementService } from "./facade-measurement.service";
import { FacadeOperationService } from "./facade-operation.service";

@Injectable({ providedIn: "root" })
export class C8YAgent {
  constructor(
    private inventory: FacadeInventoryService,
    private identity: FacadeIdentityService,
    private event: FacadeEventService,
    private alarm: FacadeAlarmService,
    private measurement: FacadeMeasurementService,
    private operation: FacadeOperationService,
    private alert: AlertService
  ) {}

  async createMEAO(context: ProcessingContext) {
    let result: any;
    let error: string = "";
    let currentRequest = context.requests[context.requests.length - 1].request;
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
        result = this.alarm.create(p, context);
      } else {
        error = "Payload is not a valid:" + context.mapping.targetAPI;
      }
    } else if (context.mapping.targetAPI == API.MEASUREMENT.name) {
      let p: IMeasurement = currentRequest as any;
      if (p != null) {
        result = this.measurement.create(p, context);
      } else {
        error = "Payload is not a valid:" + context.mapping.targetAPI;
      }
    } else if (context.mapping.targetAPI == API.OPERATION.name) {
      let p: IOperation = currentRequest as any;
      if (p != null) {
        result = this.operation.create(p, context);
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

    if (error != "") {
      this.alert.danger("Failed to tested mapping: " + error);
      return "";
    }

    try {
      let { data, res } = await result;
      //console.log ("My data:", data );
      if (res.status == 200 || res.status == 201) {
        //this.alert.success("Successfully tested mapping!");
        return data;
      } else {
        let e = await res.text();
        this.alert.danger("Failed to tested mapping: " + e);
        context.requests[context.requests.length - 1].error = e;
        return "";
      }
    } catch (e) {
      let { data, res } = await e;
      this.alert.danger("Failed to tested mapping: " + data);
      context.requests[context.requests.length - 1].error = e;
      return "";
    }
  }

  async upsertDevice(
    identity: IExternalIdentity,
    context: ProcessingContext
  ): Promise<IManagedObject> {
    let deviceId: string;
    try {
      deviceId = await this.resolveExternalId2GlobalId(identity, context);
    } catch (e) {
      console.log(
        `External id ${identity.externalId} doesn't exist! Just return original id ${identity.externalId} `
      );
    }

    let currentRequest = context.requests[context.requests.length - 1].request;
    let device: Partial<IManagedObject> = {
      ...currentRequest,
      c8y_IsDevice: {},
      c8y_mqttMapping_TestDevice: {},
      com_cumulocity_model_Agent: {},
    };
    // remove device identifier
    
    if (deviceId) {
      device.id = deviceId;
      const response: IResult<IManagedObject> = await this.inventory.update(
        device,
        context
        );
        return response.data;
      } else {
      delete device[API.INVENTORY.identifier];
      const response: IResult<IManagedObject> = await this.inventory.create(
        device,
        context
      );
      //create identity for mo
      identity = {
        ...identity,
        managedObject: {
          id: response.data.id,
        },
      };
      const { data, res } = await this.identity.create(identity, context);
      return response.data;
    }
  }

  async resolveExternalId2GlobalId(
    identity: IExternalIdentity,
    context: ProcessingContext
  ): Promise<string> {
    const { data, res } = await this.identity.resolveExternalId2GlobalId(
      identity,
      context
    );
    return data.managedObject.id as string;
  }

  async resolveGlobalId2ExternalId(
    identity: string,
    externalIdType: string,
    context: ProcessingContext
  ): Promise<string> {
    const data = await this.identity.resolveGlobalId2ExternalId(
      identity,
      externalIdType,
      context
    );
    return data.managedObject.id as string;
  }
}
