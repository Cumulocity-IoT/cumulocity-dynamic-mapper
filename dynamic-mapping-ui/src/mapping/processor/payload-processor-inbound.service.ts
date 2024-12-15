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
import { AlertService } from '@c8y/ngx-components';
import * as _ from 'lodash';
import {
  API,
  Mapping,
  MappingType,
  RepairStrategy,
  MAPPING_TEST_DEVICE_TYPE
} from '../../shared';
import { findDeviceIdentifier, getGenericDeviceIdentifier, getTypedValue, transformGenericPath2C8YPath } from '../shared/util';
import { C8YAgent } from '../core/c8y-agent.service';
import {
  ProcessingContext,
  SubstituteValue,
  SubstituteValueType
} from './processor.model';

@Injectable({ providedIn: 'root' })
export abstract class PayloadProcessorInbound {
  constructor(
    private alert: AlertService,
    private c8yClient: C8YAgent
  ) { }

  abstract deserializePayload(
    context: ProcessingContext,
    mapping: Mapping
  ): ProcessingContext;

  abstract extractFromSource(context: ProcessingContext): void;

  initializeCache(): void {
    this.c8yClient.initializeCache();
  }

  protected JSONATA = require('jsonata');

  async substituteInTargetAndSend(context: ProcessingContext) {

    // step 3 replace target with extract content from inbound payload

    const { mapping } = context;

    const { postProcessingCache } = context;
    let deviceIdentifierMapped2PathTarget: string = findDeviceIdentifier(context.mapping).pathTarget;
    for (const entry of postProcessingCache.entries()) {
      if (postProcessingCache.get(deviceIdentifierMapped2PathTarget).length < entry[1].length) {
        deviceIdentifierMapped2PathTarget = entry[0];
      }
    }

    const deviceEntries: SubstituteValue[] = postProcessingCache.get(
      findDeviceIdentifier(context.mapping).pathTarget
    );

    const countMaxlistEntries: number =
      postProcessingCache.get(deviceIdentifierMapped2PathTarget).length;
    const [toDouble] = deviceEntries;
    while (deviceEntries.length < countMaxlistEntries) {
      deviceEntries.push(toDouble);
    }

    let i: number = 0;
    for (const device of deviceEntries) {
      let predecessor: number = -1;
      let payloadTarget: JSON = null;
      try {
        payloadTarget = JSON.parse(mapping.targetTemplate);
      } catch (e) {
        this.alert.warning('Target Payload is not a valid json object!');
        throw e;
      }
      for (const pathTarget of postProcessingCache.keys()) {
        let substituteValue: SubstituteValue = {
          value: 'NOT_DEFINED' as any,
          type: SubstituteValueType.TEXTUAL,
          repairStrategy: RepairStrategy.DEFAULT
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
            const last: number = postProcessingCache.get(pathTarget).length - 1;
            substituteValue = _.clone(
              postProcessingCache.get(pathTarget)[last]
            );
          }
          console.warn(
            `During the processing of this pathTarget: ${pathTarget} a repair strategy: ${substituteValue.repairStrategy} was used!`
          );
        }

        let sourceId: SubstituteValue = {
          value: undefined,
          type: SubstituteValueType.TEXTUAL,
          repairStrategy: RepairStrategy.CREATE_IF_MISSING
        };;
        if (mapping.targetAPI != API.INVENTORY.name) {
          if (
            pathTarget == findDeviceIdentifier(mapping).pathTarget &&
            mapping.useExternalId
          ) {
            try {
              const identity = {
                externalId: substituteValue.value.toString(),
                type: mapping.externalIdType
              };
              sourceId = {
                value: await this.c8yClient.resolveExternalId2GlobalId(
                  identity,
                  context
                ), repairStrategy: RepairStrategy.DEFAULT, type: SubstituteValueType.TEXTUAL
              };
            } catch (e) {
              // console.log(
              //  `External id ${identity.externalId} doesn't exist! Just return original id ${identity.externalId} `
              // );
            }
            if (!sourceId.value && mapping.createNonExistingDevice) {
              const request = {
                c8y_IsDevice: {},
                name: `device_${mapping.externalIdType}_${substituteValue.value}`,
                d11r_device_generatedType: {},
                d11r_testDevice: {},
                type: MAPPING_TEST_DEVICE_TYPE
              };
              
              const newPredecessor = context.requests.push({
                predecessor: predecessor,
                method: 'PATCH',
                source: device.value,
                externalIdType: mapping.externalIdType,
                request: request,
                targetAPI: API.INVENTORY.name
              });

              try {
                const response = await this.c8yClient.upsertDevice(
                  {
                    externalId: substituteValue.value.toString(),
                    type: mapping.externalIdType
                  },
                  context
                );
                context.requests[newPredecessor - 1].response = response;
                substituteValue.value = response.id as any;
                sourceId.value = response.id;
              } catch (e) {
                context.requests[newPredecessor - 1].error = e;
              }
              predecessor = newPredecessor;
            } else if (!sourceId && context.sendPayload) {
              throw new Error(
                `External id ${substituteValue} for type ${mapping.externalIdType} not found!`
              );
            }
            // else {
            //   substituteValue.value = sourceId.toString();
            // }
          }

          this.substituteValueInPayload(
            mapping.mappingType,
            substituteValue,
            payloadTarget,
            pathTarget
          );
          if (getGenericDeviceIdentifier(mapping) === pathTarget) {
            this.substituteValueInPayload(
              mapping.mappingType,
              sourceId,
              payloadTarget,
              transformGenericPath2C8YPath(mapping, pathTarget)
            )
          };

          // } else if (pathTarget != API[mapping.targetAPI].identifier) {
        } else {
          this.substituteValueInPayload(
            mapping.mappingType,
            substituteValue,
            payloadTarget,
            pathTarget
          );
          if (getGenericDeviceIdentifier(mapping) === pathTarget) {
            this.substituteValueInPayload(
              mapping.mappingType,
              sourceId,
              payloadTarget,
              transformGenericPath2C8YPath(mapping, pathTarget)
            )
          };
        }
      }
      /*
       * step 4 prepare target payload for sending to c8y
       */
      if (mapping.targetAPI == API.INVENTORY.name) {
        const newPredecessor = context.requests.push({
          predecessor: predecessor,
          method: 'PATCH',
          source: device.value,
          externalIdType: mapping.externalIdType,
          request: payloadTarget,
          targetAPI: API.INVENTORY.name
        });
        try {
          const response = await this.c8yClient.upsertDevice(
            {
              externalId: getTypedValue(device),
              type: mapping.externalIdType
            },
            context
          );
          context.requests[newPredecessor - 1].response = response;
        } catch (e) {
          context.requests[newPredecessor - 1].error = e;
        }
        predecessor = context.requests.length;
      } else if (mapping.targetAPI != API.INVENTORY.name) {
        const newPredecessor = context.requests.push({
          predecessor: predecessor,
          method: 'POST',
          source: device.value,
          externalIdType: mapping.externalIdType,
          request: payloadTarget,
          targetAPI: API[mapping.targetAPI].name
        });
        try {
          const response = await this.c8yClient.createMEAO(context);
          context.requests[newPredecessor - 1].response = response;
        } catch (e) {
          context.requests[newPredecessor - 1].error = e;
        }
        predecessor = context.requests.length;
      } else {
        console.warn(
          'Ignoring payload: ${payloadTarget}, ${mapping.targetAPI}, ${postProcessingCache.size}'
        );
      }
      // console.log(
      //  `Added payload for sending: ${payloadTarget}, ${mapping.targetAPI}, numberDevices: ${deviceEntries.length}`
      // );
      i++;
    }
  }

  substituteValueInPayload(
    type: MappingType,
    sub: SubstituteValue,
    jsonObject: JSON,
    keys: string
  ) {
    const subValueMissing: boolean = !sub || sub.value == null;
    const subValueNull: boolean =
      !sub || sub.value == null || (sub.value != null && sub.value != undefined);

    if (keys == '$') {
      Object.keys(getTypedValue(sub)).forEach((key) => {
        jsonObject[key] = getTypedValue(sub)[key as keyof unknown];
      });
    } else {
      if (
        (sub.repairStrategy == RepairStrategy.REMOVE_IF_MISSING &&
          subValueMissing) ||
        (sub.repairStrategy == RepairStrategy.REMOVE_IF_NULL && subValueNull)
      ) {
        _.unset(jsonObject, keys);
      } else if (sub.repairStrategy == RepairStrategy.CREATE_IF_MISSING) {
        // const pathIsNested: boolean = keys.includes('.') || keys.includes('[');
        // if (pathIsNested) {
        //   throw new Error('Can only create new nodes on the root level!');
        // }
        // jsonObject.put("$", keys, sub.typedValue());
        _.set(jsonObject, keys, getTypedValue(sub));
      } else {
        if (_.has(jsonObject, keys)) {
          _.set(jsonObject, keys, getTypedValue(sub));
        } else {
          this.alert.warning(`Message could NOT be parsed, ignoring this message: Path: ${keys} not found!`);
          throw new Error(
            `Message could NOT be parsed, ignoring this message: Path: ${keys} not found!`
          );
        }
      }
    }
  }

  async evaluateExpression(json: JSON, path: string): Promise<JSON> {
    let result: any = '';
    if (path != undefined && path != '' && json != undefined) {
      const expression = this.JSONATA(path);
      result = expression.evaluate(json) as JSON;
    }
    return result;
  }
}
