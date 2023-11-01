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
import * as _ from "lodash";
import {
  API,
  Mapping,
  MappingType,
  RepairStrategy,
} from "../../shared/mapping.model";
import { findDeviceIdentifier, MQTT_TEST_DEVICE_TYPE } from "../../shared/util";
import { getTypedValue } from "../shared/util";
import { C8YAgent } from "../core/c8y-agent.service";
import {
  ProcessingContext,
  SubstituteValue,
  SubstituteValueType,
} from "./prosessor.model";

@Injectable({ providedIn: "root" })
export abstract class PayloadProcessorInbound {
  constructor(private alert: AlertService, private c8yClient: C8YAgent) {}

  public abstract deserializePayload(
    context: ProcessingContext,
    mapping: Mapping
  ): ProcessingContext;

  public abstract extractFromSource(context: ProcessingContext): void;

  public initializeCache(): void {
    this.c8yClient.initializeCache();
  }

  protected JSONATA = require("jsonata");

  public async substituteInTargetAndSend(context: ProcessingContext) {
    //step 3 replace target with extract content from inbound payload
    let mapping = context.mapping;

    let postProcessingCache: Map<string, SubstituteValue[]> =
      context.postProcessingCache;
    let maxEntry: string = findDeviceIdentifier(context.mapping).pathTarget;
    for (let entry of postProcessingCache.entries()) {
      if (postProcessingCache.get(maxEntry).length < entry[1].length) {
        maxEntry = entry[0];
      }
    }

    let deviceEntries: SubstituteValue[] = postProcessingCache.get(
      findDeviceIdentifier(context.mapping).pathTarget
    );

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
          repairStrategy: RepairStrategy.DEFAULT,
        };
        if (i < postProcessingCache.get(pathTarget).length) {
          substituteValue = _.clone(postProcessingCache.get(pathTarget)[i]);
        } else if (postProcessingCache.get(pathTarget).length == 1) {
          // this is an indication that the substitution is the same for all
          // events/alarms/measurements/inventory
          if (
            substituteValue.repairStrategy ==
              RepairStrategy.USE_FIRST_VALUE_OF_ARRAY ||
            substituteValue.repairStrategy == RepairStrategy.DEFAULT
          ) {
            substituteValue = _.clone(postProcessingCache.get(pathTarget)[0]);
          } else if (
            substituteValue.repairStrategy ==
            RepairStrategy.USE_LAST_VALUE_OF_ARRAY
          ) {
            let last: number = postProcessingCache.get(pathTarget).length - 1;
            substituteValue = _.clone(
              postProcessingCache.get(pathTarget)[last]
            );
          }
          console.warn(
            `During the processing of this pathTarget: ${pathTarget} a repair strategy: ${substituteValue.repairStrategy} was used!`
          );
        }

        if (mapping.targetAPI != API.INVENTORY.name) {
          if (
            pathTarget == findDeviceIdentifier(mapping).pathTarget &&
            mapping.mapDeviceIdentifier
          ) {
            let sourceId: string;
            const identity = {
              externalId: substituteValue.value.toString(),
              type: mapping.externalIdType,
            };
            try {
              sourceId = await this.c8yClient.resolveExternalId2GlobalId(
                identity,
                context
              );
            } catch (e) {
              console.log(
                `External id ${identity.externalId} doesn't exist! Just return original id ${identity.externalId} `
              );
            }
            if (!sourceId && mapping.createNonExistingDevice) {
              let request = {
                c8y_IsDevice: {},
                name:
                  "device_" +
                  mapping.externalIdType +
                  "_" +
                  substituteValue.value,
                c8y_mqttMapping_Generated_Type: {},
                c8y_mqttMapping_TestDevice: {},
                type: MQTT_TEST_DEVICE_TYPE,
              };
              let newPredecessor = context.requests.push({
                predecessor: predecessor,
                method: "PATCH",
                source: device.value,
                externalIdType: mapping.externalIdType,
                request: request,
                targetAPI: API.INVENTORY.name,
              });
              try {
                let response = await this.c8yClient.upsertDevice(
                  {
                    externalId: substituteValue.value.toString(),
                    type: mapping.externalIdType,
                  },
                  context
                );
                context.requests[newPredecessor - 1].response = response;
                substituteValue.value = response.id as any;
              } catch (e) {
                context.requests[newPredecessor - 1].error = e;
              }
              predecessor = newPredecessor;
            } else if (!sourceId && context.sendPayload) {
              throw new Error(
                "External id " +
                  substituteValue +
                  " for type " +
                  mapping.externalIdType +
                  " not found!"
              );
            } else if (!sourceId) {
              substituteValue.value = substituteValue.value.toString();
            } else {
              substituteValue.value = sourceId.toString();
            }
          }
          this.substituteValueInObject(
            mapping.mappingType,
            substituteValue,
            payloadTarget,
            pathTarget
          );
          //} else if (pathTarget != API[mapping.targetAPI].identifier) {
        } else {
          this.substituteValueInObject(
            mapping.mappingType,
            substituteValue,
            payloadTarget,
            pathTarget
          );
        }
      }
      /*
       * step 4 prepare target payload for sending to c8y
       */
      if (mapping.targetAPI == API.INVENTORY.name) {
        let newPredecessor = context.requests.push({
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
              type: mapping.externalIdType,
            },
            context
          );
          context.requests[newPredecessor - 1].response = response;
        } catch (e) {
          context.requests[newPredecessor - 1].error = e;
        }
        predecessor = context.requests.length;
      } else if (mapping.targetAPI != API.INVENTORY.name) {
        let newPredecessor = context.requests.push({
          predecessor: predecessor,
          method: "POST",
          source: device.value,
          externalIdType: mapping.externalIdType,
          request: payloadTarget,
          targetAPI: API[mapping.targetAPI].name,
        });
        try {
          let response = await this.c8yClient.createMEAO(context);
          context.requests[newPredecessor - 1].response = response;
        } catch (e) {
          context.requests[newPredecessor - 1].error = e;
        }
        predecessor = context.requests.length;
      } else {
        console.warn(
          "Ignoring payload: ${payloadTarget}, ${mapping.targetAPI}, ${postProcessingCache.size}"
        );
      }
      console.log(
        `Added payload for sending: ${payloadTarget}, ${mapping.targetAPI}, numberDevices: ${deviceEntries.length}`
      );
      i++;
    }
  }

  public substituteValueInObject(
    type: MappingType,
    sub: SubstituteValue,
    jsonObject: JSON,
    keys: string
  ) {
    let subValueMissing: boolean = sub.value == null;
    let subValueNull: boolean =
      sub.value == null || (sub.value != null && sub.value != undefined);

    if (keys == "$") {
      Object.keys(getTypedValue(sub)).forEach((key) => {
        jsonObject[key] = getTypedValue(sub)[key as keyof Object];
      });
    } else {
      if (
        (sub.repairStrategy == RepairStrategy.REMOVE_IF_MISSING &&
          subValueMissing) ||
        (sub.repairStrategy == RepairStrategy.REMOVE_IF_NULL && subValueNull)
      ) {
        _.unset(jsonObject, keys);
      } else if (sub.repairStrategy == RepairStrategy.CREATE_IF_MISSING) {
        let pathIsNested: boolean = keys.includes(".") || keys.includes("[");
        if (pathIsNested) {
          throw new Error("Can only crrate new nodes ion the root level!");
        }
        //jsonObject.put("$", keys, sub.typedValue());
        _.set(jsonObject, keys, getTypedValue(sub));
      } else {
        _.set(jsonObject, keys, getTypedValue(sub));
      }
    }
  }

  public async evaluateExpression(json: JSON, path: string): Promise<JSON> {
    let result: any = "";
    if (path != undefined && path != "" && json != undefined) {
      const expression = this.JSONATA(path);
      result = expression.evaluate(json) as JSON;
    }
    return result;
  }
}
