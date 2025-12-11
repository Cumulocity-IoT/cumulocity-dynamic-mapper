/*
 * Copyright (c) 2025 Cumulocity GmbH
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
  IOperation,
  IdReference
} from '@c8y/client';
import { AlertService } from '@c8y/ngx-components';
import { API, MAPPING_TEST_DEVICE_FRAGMENT } from '../../shared';
import { FacadeIdentityService } from './facade/facade-identity.service';
import { FacadeInventoryService } from './facade/facade-inventory.service';
import { ProcessingContext } from './processor/processor.model';
import { FacadeAlarmService } from './facade/facade-alarm.service';
import { FacadeEventService } from './facade/facade-event.service';
import { FacadeMeasurementService } from './facade/facade-measurement.service';
import { FacadeOperationService } from './facade/facade-operation.service';
import { HttpStatusCode } from '@angular/common/http';

@Injectable({ providedIn: 'root' })
export class C8YAgent {
  constructor(
    private readonly inventory: FacadeInventoryService,
    private readonly identity: FacadeIdentityService,
    private readonly event: FacadeEventService,
    private readonly alarm: FacadeAlarmService,
    private readonly measurement: FacadeMeasurementService,
    private readonly operation: FacadeOperationService,
    private readonly alert: AlertService
  ) {}

  initializeCache(): void {
    this.inventory.initializeCache();
    this.identity.initializeCache();
  }

  async createMEAO(context: ProcessingContext) {
    let result: Promise<any>;
    let error = '';
    const currentRequest =
      context.requests[context.requests.length - 1].request;
    if (context.mapping.targetAPI === API.EVENT.name) {
      const p: IEvent = currentRequest as any;
      if (p != null) {
        result = this.event.create(p, context);
      } else {
        error = `Payload is not a valid:${context.mapping.targetAPI}`;
      }
    } else if (context.mapping.targetAPI === API.ALARM.name) {
      const p: IAlarm = currentRequest as any;
      if (p != null) {
        result = this.alarm.create(p, context);
      } else {
        error = `Payload is not a valid:${context.mapping.targetAPI}`;
      }
    } else if (context.mapping.targetAPI === API.MEASUREMENT.name) {
      const p: IMeasurement = currentRequest as any;
      if (p != null) {
        result = this.measurement.create(p, context);
      } else {
        error = `Payload is not a valid:${context.mapping.targetAPI}`;
      }
    } else if (context.mapping.targetAPI === API.OPERATION.name) {
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

    if (error !== '') {
      context.requests[context.requests.length - 1].error = error;
      return '';
    }

    try {
      const { data, res } = await result;
      if (
        res.status === HttpStatusCode.Ok ||
        res.status === HttpStatusCode.Created
      ) {
        return data;
      } else {
        const e = await res.text();
        context.requests[context.requests.length - 1].error = e;
        return '';
      }
    } catch (e) {
      const { res } = await e;
      context.requests[context.requests.length - 1].error = res.statusText;
      return '';
    }
  }

  async upsertDevice(
    identity: IExternalIdentity,
    context: ProcessingContext
  ): Promise<IManagedObject> {
    let sourceId: string;
    try {
      if (identity) {
        sourceId = await this.resolveExternalId2GlobalId(identity, context);
      } else {
        const { data } = await this.detail(context.sourceId, context);
        sourceId = data?.id;
      }
    } catch (e) {
      const { res, data } = e;
      // enrich error message
      if (res?.status === HttpStatusCode.NotFound) {
        e.message = `Device with ${context.sourceId} not found!`;
      }
      console.error(e);
    }

    const currentRequest = context.requests?.slice(-1)[0] ?? null;
    if (!currentRequest.hidden) {
      const device: Partial<IManagedObject> = {
        c8y_IsDevice: {},
        [MAPPING_TEST_DEVICE_FRAGMENT]: {},
        name: currentRequest.request['name'],
        com_cumulocity_model_Agent: {}
      };
      // remove device identifier

      if (sourceId) {
        device.id = sourceId;
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
        if (identity) {
          identity = {
            ...identity,
            managedObject: {
              id: response.data.id
            }
          };
          await this.identity.create(identity, context);
        }
        return response.data;
      }
    } else {
      return { id: sourceId } as IManagedObject;
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
  ): Promise<IExternalIdentity> {
    const externalId = await this.identity.resolveGlobalId2ExternalId(
      identity,
      externalIdType,
      context
    );
    return externalId;
  }

  async detail(
    managedObjectOrId: IdReference,
    context: ProcessingContext
  ): Promise<IResult<IManagedObject>> {
    const managedObject = await this.inventory.detail(
      managedObjectOrId,
      context
    );
    return managedObject;
  }
}
