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
export class MQTTClient {
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
    let result = context.requests[context.requests.length - 1].request;
    return result;
  }

}
