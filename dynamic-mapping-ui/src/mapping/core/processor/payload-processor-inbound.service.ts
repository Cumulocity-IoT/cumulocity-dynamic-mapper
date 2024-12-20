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
  RepairStrategy,
  MAPPING_TEST_DEVICE_TYPE,
  MAPPING_TEST_DEVICE_FRAGMENT
} from '../../../shared';
import { getGenericDeviceIdentifier } from '../../shared/util';
import { C8YAgent } from '../c8y-agent.service';
import {
  ProcessingContext,
  SubstituteValue,
  SubstituteValueType
} from './processor.model';
import { getPathTargetForDeviceIdentifiers, getTypedValue, substituteValueInPayload, transformGenericPath2C8YPath } from './util';

@Injectable({ providedIn: 'root' })
export abstract class PayloadProcessorInbound {
  constructor(
    private alert: AlertService,
    private c8yClient: C8YAgent
  ) { }

  abstract deserializePayload(
    mapping: Mapping,
    message: any,
    context: ProcessingContext
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

    // if there are too few devices identified, then we replicate the first device
    const entryWithMaxSubstitutes = Array.from(postProcessingCache.entries())
      .reduce((max, [key, value]) =>
        value.length > (max[1]?.length ?? 0)
          ? [key, value]
          : max)[0];
    const countMaxEntries = postProcessingCache.get(entryWithMaxSubstitutes).length;

    const pathsTargetForDeviceIdentifiers: string[] = getPathTargetForDeviceIdentifiers(mapping);
    const firstPathTargetForDeviceIdentifiers = pathsTargetForDeviceIdentifiers.length > 0
      ? pathsTargetForDeviceIdentifiers[0]
      : null;

    const deviceEntries: SubstituteValue[] = postProcessingCache.get(
      firstPathTargetForDeviceIdentifiers
    );
    const [toDouble] = deviceEntries;
    while (deviceEntries.length < countMaxEntries) {
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
        let substitute: SubstituteValue = {
          value: 'NOT_DEFINED' as any,
          type: SubstituteValueType.TEXTUAL,
          repairStrategy: RepairStrategy.DEFAULT
        };
        if (i < postProcessingCache.get(pathTarget).length) {
          substitute = _.clone(postProcessingCache.get(pathTarget)[i]);
        } else if (postProcessingCache.get(pathTarget).length == 1) {
          // this is an indication that the substitution is the same for all
          // events/alarms/measurements/inventory
          if (
            substitute.repairStrategy ==
            RepairStrategy.USE_FIRST_VALUE_OF_ARRAY ||
            substitute.repairStrategy == RepairStrategy.DEFAULT
          ) {
            substitute = _.clone(postProcessingCache.get(pathTarget)[0]);
          } else if (
            substitute.repairStrategy ==
            RepairStrategy.USE_LAST_VALUE_OF_ARRAY
          ) {
            const last: number = postProcessingCache.get(pathTarget).length - 1;
            substitute = _.clone(
              postProcessingCache.get(pathTarget)[last]
            );
          }
          console.warn(
            `During the processing of this pathTarget: ${pathTarget} a repair strategy: ${substitute.repairStrategy} was used!`
          );
        }

        let sourceId: SubstituteValue = {
          value: undefined,
          type: SubstituteValueType.TEXTUAL,
          repairStrategy: RepairStrategy.CREATE_IF_MISSING
        };;
        if (mapping.targetAPI != API.INVENTORY.name) {
          if (
            pathsTargetForDeviceIdentifiers.includes(pathTarget) &&
            mapping.useExternalId
          ) {
            try {
              const identity = {
                externalId: substitute.value.toString(),
                type: mapping.externalIdType
              };
              sourceId = {
                value: await this.c8yClient.resolveExternalId2GlobalId(
                  identity,
                  context
                ), repairStrategy: RepairStrategy.DEFAULT, type: SubstituteValueType.TEXTUAL
              };
            } catch (e) {
              // here we create a mock device to testing locally 
              try {
                const request = {
                  c8y_IsDevice: {},
                  name: `device_${mapping.externalIdType}_${substitute.value}`,
                  d11r_device_generatedType: {},
                  [MAPPING_TEST_DEVICE_FRAGMENT]: {},
                  type: MAPPING_TEST_DEVICE_TYPE
                };
                const newPredecessor = context.requests.push({
                  predecessor: predecessor,
                  method: 'POST',
                  source: device.value,
                  externalIdType: mapping.externalIdType,
                  request,
                  targetAPI: API.INVENTORY.name,
                  hidden: true
                });
                const response = await this.c8yClient.upsertDevice(
                  {
                    externalId: substitute.value.toString(),
                    type: mapping.externalIdType
                  },
                  context
                );
                context.requests[newPredecessor - 1].response = response;
                substitute.value = response.id as any;
              } catch (e) {
                console.log("Error", e);
              }
            }
            if (!sourceId.value && mapping.createNonExistingDevice) {
              const request = {
                c8y_IsDevice: {},
                name: `device_${mapping.externalIdType}_${substitute.value}`,
                d11r_device_generatedType: {},
                [MAPPING_TEST_DEVICE_FRAGMENT]: {},
                type: MAPPING_TEST_DEVICE_TYPE
              };

              const newPredecessor = context.requests.push({
                predecessor: predecessor,
                method: 'PATCH',
                source: device.value,
                externalIdType: mapping.externalIdType,
                request,
                targetAPI: API.INVENTORY.name
              });

              try {
                const response = await this.c8yClient.upsertDevice(
                  {
                    externalId: substitute.value.toString(),
                    type: mapping.externalIdType
                  },
                  context
                );
                context.requests[newPredecessor - 1].response = response;
                substitute.value = response.id as any;
              } catch (e) {
                context.requests[newPredecessor - 1].error = e;
              }
              predecessor = newPredecessor;
            } else if (!sourceId && context.sendPayload) {
              throw new Error(
                `External id ${substitute} for type ${mapping.externalIdType} not found!`
              );
            }
            if (getGenericDeviceIdentifier(mapping) === pathTarget) {
              substitute.repairStrategy = RepairStrategy.CREATE_IF_MISSING;
              substituteValueInPayload(
                mapping.mappingType,
                substitute,
                payloadTarget,
                transformGenericPath2C8YPath(mapping, pathTarget)
              )
            };
          }
          substituteValueInPayload(
            mapping.mappingType,
            substitute,
            payloadTarget,
            pathTarget
          );

        } else {
          if (getGenericDeviceIdentifier(mapping) === pathTarget) {
            substitute.repairStrategy = RepairStrategy.CREATE_IF_MISSING;
            substituteValueInPayload(
              mapping.mappingType,
              sourceId,
              payloadTarget,
              transformGenericPath2C8YPath(mapping, pathTarget)
            )
          };
          substituteValueInPayload(
            mapping.mappingType,
            substitute,
            payloadTarget,
            pathTarget
          );
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

  async evaluateExpression(json: JSON, path: string): Promise<JSON> {
    let result: any = '';
    if (path != undefined && path != '' && json != undefined) {
      const expression = this.JSONATA(path);
      result = expression.evaluate(json) as JSON;
    }
    return result;
  }
}
