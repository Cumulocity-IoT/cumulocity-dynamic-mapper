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
import {
  IEvent,
  IAlarm,
  IMeasurement,
  IManagedObject,
  IResult,
  IExternalIdentity,
  IOperation
} from '@c8y/client';
import { AlertService } from '@c8y/ngx-components';
import { API, MAPPING_TEST_DEVICE_FRAGMENT } from '../../shared';
import { FacadeIdentityService } from './facade/facade-identity.service';
import { FacadeInventoryService } from './facade/facade-inventory.service';
import { ProcessingContext } from '../processor/processor.model';
import { FacadeAlarmService } from './facade/facade-alarm.service';
import { FacadeEventService } from './facade/facade-event.service';
import { FacadeMeasurementService } from './facade/facade-measurement.service';
import { FacadeOperationService } from './facade/facade-operation.service';
import { HttpStatusCode } from '@angular/common/http';

@Injectable({ providedIn: 'root' })
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

  initializeCache(): void {
    this.inventory.initializeCache();
    this.identity.initializeCache();
  }

  async createMEAO(context: ProcessingContext) {
    let result: Promise<any>;
    let error: string = '';
    const currentRequest =
      context.requests[context.requests.length - 1].request;
    if (context.mapping.targetAPI == API.EVENT.name) {
      const p: IEvent = currentRequest as any;
      if (p != null) {
        result = this.event.create(p, context);
      } else {
        error = `Payload is not a valid:${context.mapping.targetAPI}`;
      }
    } else if (context.mapping.targetAPI == API.ALARM.name) {
      const p: IAlarm = currentRequest as any;
      if (p != null) {
        result = this.alarm.create(p, context);
      } else {
        error = `Payload is not a valid:${context.mapping.targetAPI}`;
      }
    } else if (context.mapping.targetAPI == API.MEASUREMENT.name) {
      const p: IMeasurement = currentRequest as any;
      if (p != null) {
        result = this.measurement.create(p, context);
      } else {
        error = `Payload is not a valid:${context.mapping.targetAPI}`;
      }
    } else if (context.mapping.targetAPI == API.OPERATION.name) {
      const p: IOperation = currentRequest as any;
      if (p != null) {
        result = this.operation.create(p, context);
      } else {
        error = `Payload is not a valid:${context.mapping.targetAPI}`;
      }
    } else {
      const p: IManagedObject = currentRequest as any;
      if (p != null) {
        if (context.mapping.updateExistingDevice) {
          result = this.inventory.update(p, context);
        } else {
          result = this.inventory.create(p, context);
        }
      } else {
        error = `Payload is not a valid: ${context.mapping.targetAPI}`;
      }
    }

    if (error != '') {
      this.alert.danger(`Failed to test mapping: ${error}`);
      return '';
    }

    try {
      const { data, res } = await result;
      if (
        res.status == HttpStatusCode.Ok ||
        res.status == HttpStatusCode.Created
      ) {
        // this.alert.success("Successfully tested mapping!");
        return data;
      } else {
        const e = await res.text();
        this.alert.danger(`Failed to test mapping: ${e}`);
        context.requests[context.requests.length - 1].error = e;
        return '';
      }
    } catch (e) {
      const { res } = await e;
      this.alert.danger(`Failed to test mapping: ${res.statusText}`);
      context.requests[context.requests.length - 1].error = res.statusText;
      return '';
    }
  }

  async upsertDevice(
    identityx: IExternalIdentity,
    context: ProcessingContext
  ): Promise<IManagedObject> {
    let identity = identityx;
    let deviceId: string;
    try {
      deviceId = await this.resolveExternalId2GlobalId(identity, context);
    } catch (e) {
      // console.log(
      //  `External id ${identity.externalId} doesn't exist! Just return original id ${identity.externalId} `
      // );
    }

    const currentRequest = context.requests[context.requests.length-1];
    const device: Partial<IManagedObject> = {
      c8y_IsDevice: {},
      [MAPPING_TEST_DEVICE_FRAGMENT]: {},
      name: currentRequest.request['name'],
      com_cumulocity_model_Agent: {}
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
      // create identity for mo
      identity = {
        ...identity,
        managedObject: {
          id: response.data.id
        }
      };
      await this.identity.create(identity, context);
      return response.data;
    }
  }

  async resolveExternalId2GlobalId(
    identity: IExternalIdentity,
    context: ProcessingContext
  ): Promise<string> {
    const { data } = await this.identity.resolveExternalId2GlobalId(
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
