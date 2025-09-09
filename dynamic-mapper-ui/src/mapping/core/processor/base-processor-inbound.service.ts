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
import { HttpStatusCode } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { IExternalIdentity } from '@c8y/client';
import { AlertService } from '@c8y/ngx-components';
import * as _ from 'lodash';
import {
  API,
  MAPPING_TEST_DEVICE_FRAGMENT,
  MAPPING_TEST_DEVICE_TYPE,
  Mapping,
  RepairStrategy,
  SharedService,
  getPathTargetForDeviceIdentifiers, transformGenericPath2C8YPath
} from '../../../shared';
import { splitTopicExcludingSeparator } from '../../shared/util';
import { C8YAgent } from '../c8y-agent.service';
import {
  TOKEN_IDENTITY,
  ProcessingContext,
  SubstituteValue,
  SubstituteValueType,
  TOKEN_TOPIC_LEVEL,
  getDeviceEntries, getTypedValue, prepareAndSubstituteInPayload,
  sortProcessingCache
} from './processor.model';

@Injectable({ providedIn: 'root' })
export abstract class BaseProcessorInbound {
  constructor(
    private alert: AlertService,
    private c8yClient: C8YAgent,
    public sharedService: SharedService,
  ) { }

  protected JSONATA = require('jsonata');

  abstract deserializePayload(
    mapping: Mapping,
    message: any,
    context: ProcessingContext
  ): ProcessingContext;

  initializeCache(): void {
    this.c8yClient.initializeCache();
  }

  abstract extractFromSource(context: ProcessingContext): void;

  enrichPayload(context: ProcessingContext): void {
    const { payload } = context;
    const topicLevels = splitTopicExcludingSeparator(context.topic, false);
    payload[TOKEN_TOPIC_LEVEL] = topicLevels;
  }

  validateProcessingCache(context: ProcessingContext) {
    const { processingCache, mapping } = context;

    // if there are too few devices identified, then we replicate the first device
    const entryWithMaxSubstitutes = Array.from(processingCache.entries())
      .reduce((max, [key, value]) =>
        value.length > (max[1]?.length ?? 0)
          ? [key, value]
          : max)[0];
    const countMaxEntries = processingCache.get(entryWithMaxSubstitutes).length;

    const pathsTargetForDeviceIdentifiers: string[] = getPathTargetForDeviceIdentifiers(context);
    const firstPathTargetForDeviceIdentifiers = pathsTargetForDeviceIdentifiers.length > 0
      ? pathsTargetForDeviceIdentifiers[0]
      : null;

    const deviceEntries: SubstituteValue[] = processingCache.get(
      firstPathTargetForDeviceIdentifiers
    );
    if (deviceEntries) {
      const [toDouble] = deviceEntries;
      while (deviceEntries.length < countMaxEntries) {
        deviceEntries.push(toDouble);
      }
    } else {
      throw new Error("Device Id not defined in substitutions!")
    }
  }

  async substituteInTargetAndSend(context: ProcessingContext) {

    // step 3 replace target with extract content from inbound payload

    const { processingCache, mapping } = context;
    const deviceEntries = getDeviceEntries(context);
    // sort processingCache, so that the "_CONTEXT_DATA_.deviceName" is available when creating an implicit device
    sortProcessingCache(context)

    let i: number = 0;
    for (const device of deviceEntries) {
      let payloadTarget: JSON = null;
      try {
        payloadTarget = JSON.parse(mapping.targetTemplate);
      } catch (e) {
        this.alert.warning('Target Payload is not a valid json object!');
        throw e;
      }
      for (const pathTarget of processingCache.keys()) {
        let substitute: SubstituteValue = {
          value: 'NOT_DEFINED' as any,
          type: SubstituteValueType.TEXTUAL,
          repairStrategy: RepairStrategy.DEFAULT
        };
        const pathTargetSubstitute = processingCache.get(pathTarget);
        if (i < pathTargetSubstitute.length) {
          substitute = _.clone(pathTargetSubstitute[i]);
        } else if (pathTargetSubstitute.length == 1) {
          // this is an indication that the substitution is the same for all
          // events/alarms/measurements/inventory
          if (
            substitute.repairStrategy ==
            RepairStrategy.USE_FIRST_VALUE_OF_ARRAY ||
            substitute.repairStrategy == RepairStrategy.DEFAULT
          ) {
            substitute = _.clone(pathTargetSubstitute[0]);
          } else if (
            substitute.repairStrategy ==
            RepairStrategy.USE_LAST_VALUE_OF_ARRAY
          ) {
            const last: number = pathTargetSubstitute.length - 1;
            substitute = _.clone(
              pathTargetSubstitute[last]
            );
          }
          console.warn(
            `Processing pathTarget: ${pathTarget}, repairStrategy: ${substitute.repairStrategy}.`
          );
        }
        let identity;

        // check if the targetPath == externalId and  we need to resolve an external id
        if (
          `${TOKEN_IDENTITY}.externalId` == pathTarget && mapping.useExternalId
        ) {
          identity = {
            externalId: substitute.value.toString(),
            type: mapping.externalIdType
          };
          let sourceId = {
            value: substitute.value,
            repairStrategy: RepairStrategy.CREATE_IF_MISSING,
            type: SubstituteValueType.TEXTUAL
          };
          if (mapping.targetAPI != API.INVENTORY.name) {
            try {
              sourceId.value = await this.c8yClient.resolveExternalId2GlobalId(
                identity,
                context);
            } catch (e) {
              if (mapping.createNonExistingDevice) {
                sourceId.value = await this.createImplicitDevice(identity, context);
              } else {
                e['possibleIgnoreErrorNonExisting'] = true;
                throw e;
              }
            }
            prepareAndSubstituteInPayload(context,
              sourceId,
              payloadTarget,
              transformGenericPath2C8YPath(mapping, pathTarget),
              this.alert
            )
            context.sourceId = sourceId.value;
            substitute.repairStrategy = RepairStrategy.CREATE_IF_MISSING;
          }
        } else if (`${TOKEN_IDENTITY}.c8ySourceId` == pathTarget) {
          let sourceId = {
            value: substitute.value,
            repairStrategy: RepairStrategy.CREATE_IF_MISSING,
            type: SubstituteValueType.TEXTUAL
          };
          context.sourceId = substitute.value;

          if (mapping.targetAPI != API.INVENTORY.name) {
            // check if we need to create an implicit device
            try {
              const { res } = await this.c8yClient.detail(substitute.value, context);
              if (res.status == HttpStatusCode.NotFound) {
                if (mapping.createNonExistingDevice) {
                  sourceId.value = await this.createImplicitDevice(undefined, context);
                } else {
                  const e = new Error(`Device with id: ${substitute.value} does not exist. Set option createNonExistingDevice!`);
                  e['possibleIgnoreErrorNonExisting'] = true;
                  throw e;
                }
              }
            } catch (error) {
              if (mapping.createNonExistingDevice) {
                sourceId.value = await this.createImplicitDevice(undefined, context);
              } else {
                const e = new Error(`Device with id: ${substitute.value} does not exist. Set option createNonExistingDevice!`);
                e['possibleIgnoreErrorNonExisting'] = true;
                throw e;
              }
            }
            substitute.repairStrategy = RepairStrategy.CREATE_IF_MISSING;
          }
          prepareAndSubstituteInPayload(context,
            sourceId,
            payloadTarget,
            transformGenericPath2C8YPath(mapping, pathTarget),
            this.alert
          )
          context.sourceId = substitute.value;
        }
        prepareAndSubstituteInPayload(context,
          substitute,
          payloadTarget,
          pathTarget,
          this.alert
        )
      };

      /*
       * step 4 prepare target payload for sending to c8y
       */
      const predecessor = context.requests.length;
      if (mapping.targetAPI == API.INVENTORY.name) {
        context.requests.push({
          predecessor,
          method: context.mapping.updateExistingDevice ? 'POST' : 'PATCH',
          sourceId: device.value,
          externalIdType: mapping.externalIdType,
          request: payloadTarget,
          targetAPI: API.INVENTORY.name
        });
        try {
          const identity: IExternalIdentity = mapping.useExternalId ? {
            externalId: getTypedValue(device),
            type: mapping.externalIdType
          } : undefined;
          const response = await this.c8yClient.upsertDevice(
            identity,
            context
          );
          context.requests[predecessor].response = response;
        } catch (e) {
          context.requests[predecessor].error = e.message;
        }
      } else if (mapping.targetAPI != API.INVENTORY.name) {
        context.requests.push({
          predecessor,
          method: 'POST',
          sourceId: device.value,
          externalIdType: mapping.externalIdType,
          request: payloadTarget,
          targetAPI: API[mapping.targetAPI].name
        });
        try {
          const response = await this.c8yClient.createMEAO(context);
          context.requests[predecessor].response = response;
        } catch (e) {
          context.requests[predecessor].error = e.message;
        }
      } else {
        console.warn(
          'Ignoring payload: ${payloadTarget}, ${mapping.targetAPI}, ${processingCache.size}'
        );
      }
      // console.log(
      //  `Added payload for sending: ${payloadTarget}, ${mapping.targetAPI}, numberDevices: ${deviceEntries.length}`
      // );
      i++;
    }
  }
  async createImplicitDevice(identity: IExternalIdentity, context: ProcessingContext): Promise<string> {
    let sourceId: string;

    let deviceName;
    if (context.deviceName) {
      deviceName = context.deviceName;
    }
    else {
      deviceName = identity ? `device_${identity.type}_${identity.externalId}` : `device_${context.sourceId}`;
    }
    let deviceType;

    if (context.deviceType) {
      deviceType = context.deviceType;

    } else {
      // Default device type if not specified
      deviceType = MAPPING_TEST_DEVICE_TYPE
        ;
    }

    const request = {
      c8y_IsDevice: {},
      name: deviceName,
      type: deviceType,
      d11r_device_generatedType: {},
      [MAPPING_TEST_DEVICE_FRAGMENT]: {},
    };

    const predecessor = context.requests.length;
    context.requests.push({
      predecessor,
      method: context.mapping.updateExistingDevice ? 'POST' : 'PATCH',
      externalIdType: identity?.externalId,
      request,
      targetAPI: API.INVENTORY.name,
      hidden: !context.mapping.createNonExistingDevice
    });

    try {
      const response = await this.c8yClient.upsertDevice(
        identity,
        context
      );
      context.requests[predecessor].response = response;
      context.requests[predecessor].sourceId = response.id;
      sourceId = response.id;
    } catch (e) {
      const { res, data } = e;
      if (res?.status == HttpStatusCode.NotFound) {
        e.message = `Device with ${context.sourceId} not found!`;
        context.requests[predecessor].error = e.message;
      } else {
        context.requests[predecessor].error = res.statusText;
      }
    }
    return sourceId;
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
