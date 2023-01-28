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
import { AlertService } from "@c8y/ngx-components";
import * as _ from 'lodash';
import { API, Mapping, RepairStrategy } from "../../shared/mapping.model";
import { MQTT_TEST_DEVICE_TYPE } from "../../shared/util";
import { getTypedValue } from "../shared/util";
import { C8YClient } from "../core/c8y-client.service";
import { ProcessingContext, SubstituteValue, SubstituteValueType } from "./prosessor.model";

@Injectable({ providedIn: 'root' })
export abstract class PayloadProcessorIncoming {
  constructor(
    private alert: AlertService,
    private c8yClient: C8YClient) { }

  public abstract deserializePayload(context: ProcessingContext, mapping: Mapping): ProcessingContext;

  public abstract extractFromSource(context: ProcessingContext): void;

  protected JSONATA = require("jsonata");

  public async substituteInTargetAndSend(context: ProcessingContext) {
    //step 3 replace target with extract content from incoming payload
    let mapping = context.mapping;

    let postProcessingCache: Map<string, SubstituteValue[]> = context.postProcessingCache;
    let maxEntry: string = API[mapping.targetAPI].identifier;
    for (let entry of postProcessingCache.entries()) {
      if (postProcessingCache.get(maxEntry).length < entry[1].length) {
        maxEntry = entry[0];
      }
    }

    let deviceEntries: SubstituteValue[] = postProcessingCache.get(API[mapping.targetAPI].identifier);

    let countMaxlistEntries: number = postProcessingCache.get(maxEntry).length;
    let toDouble: SubstituteValue = deviceEntries[0];
    while (deviceEntries.length < countMaxlistEntries) {
      deviceEntries.push(toDouble);
    }

    let i: number = 0;
    for (let device of deviceEntries) {
      let predecessor: number = -1;
      let payloadTarget: JSON = null;
      try {
        payloadTarget = JSON.parse(mapping.target);
      } catch (e) {
        this.alert.warning("Target Payload is not a valid json object!");
        throw e;
      }
      for (let pathTarget of postProcessingCache.keys()) {
        let substituteValue: SubstituteValue = {
          value: "NOT_DEFINED" as any,
          type: SubstituteValueType.TEXTUAL,
          repairStrategy: RepairStrategy.DEFAULT
        }
        if (i < postProcessingCache.get(pathTarget).length) {
          substituteValue = _.clone(postProcessingCache.get(pathTarget)[i]);
        } else if (postProcessingCache.get(pathTarget).length == 1) {
          // this is an indication that the substitution is the same for all
          // events/alarms/measurements/inventory
          if (substituteValue.repairStrategy == RepairStrategy.USE_FIRST_VALUE_OF_ARRAY || substituteValue.repairStrategy == RepairStrategy.DEFAULT) {
            substituteValue = _.clone(postProcessingCache.get(pathTarget)[0]);
          } else if (substituteValue.repairStrategy == RepairStrategy.USE_LAST_VALUE_OF_ARRAY) {
            let last: number = postProcessingCache.get(pathTarget).length - 1;
            substituteValue = _.clone(postProcessingCache.get(pathTarget)[last]);
          }
          console.warn(`During the processing of this pathTarget: ${pathTarget} a repair strategy: ${substituteValue.repairStrategy} was used!`);
        }

        if (mapping.targetAPI != (API.INVENTORY.name)) {
          if (pathTarget == API[mapping.targetAPI].identifier) {
            let sourceId: string = await this.c8yClient.resolveExternalId(
              {
                externalId: substituteValue.value.toString(),
                type: mapping.externalIdType
              }, context);
            if (!sourceId && mapping.createNonExistingDevice) {
              let request = {
                c8y_IsDevice: {},
                name: "device_" + mapping.externalIdType + "_" + substituteValue.value,
                c8y_mqttMapping_Generated_Type: {},
                c8y_mqttMapping_TestDevice: {},
                type: MQTT_TEST_DEVICE_TYPE
              }
              let newPredecessor = context.requests.push({
                predecessor: predecessor,
                method: "PATCH",
                source: device.value,
                externalIdType: mapping.externalIdType,
                request: request,
                targetAPI: API.INVENTORY.name,
              })
              try {
                let response = await this.c8yClient.upsertDevice({
                  externalId: substituteValue.value.toString(),
                  type: mapping.externalIdType
                }, context);
                context.requests[newPredecessor - 1].response = response;
                substituteValue.value = response.id as any;
              } catch (e) {
                context.requests[newPredecessor - 1].error = e;
              }
              predecessor = newPredecessor;
            } else if (sourceId == null && context.sendPayload) {
              throw new Error("External id " + substituteValue + " for type "
                + mapping.externalIdType + " not found!");
            } else if (sourceId == null) {
              substituteValue.value = null
            } else {
              substituteValue.value = sourceId.toString();
            }
          }
          if (substituteValue.repairStrategy == RepairStrategy.REMOVE_IF_MISSING && !substituteValue) {
            _.unset(payloadTarget, pathTarget);
          } else {
            _.set(payloadTarget, pathTarget, getTypedValue(substituteValue))
          }
        } else if (pathTarget != (API[mapping.targetAPI].identifier)) {
          if (substituteValue.repairStrategy == RepairStrategy.REMOVE_IF_MISSING && !substituteValue) {
            _.unset(payloadTarget, pathTarget);
          } else {
            _.set(payloadTarget, pathTarget, getTypedValue(substituteValue))
          }

        }
      }
      /*
       * step 4 prepare target payload for sending to c8y
       */
      if (mapping.targetAPI == API.INVENTORY.name) {
        let newPredecessor = context.requests.push(
          {
            predecessor: predecessor,
            method: "PATCH",
            source: device.value,
            externalIdType: mapping.externalIdType,
            request: payloadTarget,
            targetAPI: API.INVENTORY.name,
          });
        try {
          let response = await this.c8yClient.upsertDevice(
            {
              externalId: getTypedValue(device),
              type: mapping.externalIdType
            }, context);
          context.requests[newPredecessor - 1].response = response;
        } catch (e) {
          context.requests[newPredecessor - 1].error = e;
        }
        predecessor = context.requests.length;

      } else if (mapping.targetAPI != API.INVENTORY.name) {
        let newPredecessor = context.requests.push(
          {
            predecessor: predecessor,
            method: "POST",
            source: device.value,
            externalIdType: mapping.externalIdType,
            request: payloadTarget,
            targetAPI: API[mapping.targetAPI].name,
          })
        try {
          let response = await this.c8yClient.createMEAO(context);
          context.requests[newPredecessor - 1].response = response;
        } catch (e) {
          context.requests[newPredecessor - 1].error = e;
        }
        predecessor = context.requests.length;
      } else {
        console.warn("Ignoring payload: ${payloadTarget}, ${mapping.targetAPI}, ${postProcessingCache.size}");
      }
      console.log(`Added payload for sending: ${payloadTarget}, ${mapping.targetAPI}, numberDevices: ${deviceEntries.length}`);
      i++;
    }
  }

}