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
import { API, Mapping, MappingType, RepairStrategy } from '../../../shared';
import {
  TOKEN_TOPIC_LEVEL,
  splitTopicExcludingSeparator,
  splitTopicIncludingSeparator
} from '../../shared/util';
import { C8YAgent } from '../c8y-agent.service';
import {
  ProcessingContext,
  SubstituteValue,
  SubstituteValueType
} from './processor.model';
import { MQTTClient } from '../mqtt-client.service';
import { getTypedValue } from './util';

@Injectable({ providedIn: 'root' })
export abstract class BaseProcessorOutbound {
  constructor(
    private alert: AlertService,
    public c8yAgent: C8YAgent,
    private mqttClient: MQTTClient
  ) { }

  abstract deserializePayload(
    mapping: Mapping,
    message: any,
    context: ProcessingContext
  ): ProcessingContext;

  abstract extractFromSource(context: ProcessingContext): void;

  protected JSONATA = require('jsonata');

  async substituteInTargetAndSend(context: ProcessingContext) {
    // step 3 replace target with extract content from o payload
    const { mapping, processingCache } = context;
    const pathTargets = processingCache.keys();

    let predecessor: number = -1;
    let payloadTarget: JSON = null;
    try {
      payloadTarget = JSON.parse(mapping.targetTemplate);
    } catch (e) {
      this.alert.warning('Target payload is not a valid json object!');
      throw e;
    }

    /*
     * step 0 patch payload with dummy property _TOPIC_LEVEL_ in case the content
     * is required in the payload for a substitution
     */
    const splitTopicExAsList: string[] = splitTopicExcludingSeparator(
      context.topic
    );
    payloadTarget[TOKEN_TOPIC_LEVEL] = splitTopicExAsList;
    const deviceSource: string = 'undefined';

    for (const pathTarget of pathTargets) {
      let substituteValue: SubstituteValue = {
        value: 'NOT_DEFINED' as any,
        type: SubstituteValueType.TEXTUAL,
        repairStrategy: RepairStrategy.DEFAULT
      };
      if (processingCache.get(pathTarget).length > 0) {
        substituteValue = _.clone(processingCache.get(pathTarget)[0]);
      }

      this.substituteValueInPayload(
        mapping.mappingType,
        substituteValue,
        payloadTarget,
        pathTarget
      );
    }

    /*
     * step 4 prepare target payload for sending to mqttBroker
     */

    if (mapping.targetAPI != API.INVENTORY.name) {
      const topicLevels: string[] = payloadTarget[TOKEN_TOPIC_LEVEL];
      if (!topicLevels && topicLevels.length > 0) {
        // now merge the replaced topic levels
        let c: number = 0;
        const splitTopicInAsList: string[] = splitTopicIncludingSeparator(
          context.topic
        );
        topicLevels.forEach((tl) => {
          while (
            c < splitTopicInAsList.length &&
            '/' == splitTopicInAsList[c]
          ) {
            c++;
          }
          splitTopicInAsList[c] = tl;
          c++;
        });

        const resolvedPublishTopic: string = '';
        for (let d: number = 0; d < splitTopicInAsList.length; d++) {
          resolvedPublishTopic.concat(splitTopicInAsList[d]);
        }
        context.resolvedPublishTopic = resolvedPublishTopic.toString();
      } else {
        context.resolvedPublishTopic = context.mapping.publishTopic;
      }

      // leave the topic for debugging purposes
      // _.unset(payloadTarget, TOKEN_TOPIC_LEVEL);
      const newPredecessor = context.requests.push({
        predecessor: predecessor,
        method: 'POST',
        source: deviceSource,
        externalIdType: mapping.externalIdType,
        request: payloadTarget,
        targetAPI: API[mapping.targetAPI].name
      });
      try {
        const response = await this.mqttClient.createMEAO(context);
        context.requests[newPredecessor - 1].response = response;
      } catch (e) {
        context.requests[newPredecessor - 1].error = e;
      }
      predecessor = context.requests.length;
    } else {
      console.warn(
        'Ignoring payload: ${payloadTarget}, ${mapping.targetAPI}, ${processingCache.size}'
      );
    }
    //console.log(
    //  `Added payload for sending: ${payloadTarget}, ${mapping.targetAPI}, numberDevices: 1`
    //);
  }

  substituteValueInPayload(
    type: MappingType,
    sub: SubstituteValue,
    jsonObject: JSON,
    keys: string
  ) {
    const subValueMissingOrNull: boolean =
      sub.value == null || (sub.value != null && sub.value != undefined);

    if (keys == '$') {
      Object.keys(getTypedValue(sub)).forEach((key) => {
        jsonObject[key] = getTypedValue(sub)[key as keyof unknown];
      });
    } else {
      if (sub.repairStrategy == RepairStrategy.REMOVE_IF_MISSING_OR_NULL && subValueMissingOrNull) {
        _.unset(jsonObject, keys);
      } else if (sub.repairStrategy == RepairStrategy.CREATE_IF_MISSING) {
        // const pathIsNested: boolean = keys.includes('.') || keys.includes('[');
        // if (pathIsNested) {
        //   throw new Error('Can only create new nodes on the root level!');
        // }
        // jsonObject.put("$", keys, sub.typedValue());
        _.set(jsonObject, keys, getTypedValue(sub));
      } else {
        _.set(jsonObject, keys, getTypedValue(sub));
      }
    }
  }

  async evaluateExpression(json: JSON, path: string): Promise<JSON> {
    let result: any = '';
    if (path != undefined && path != '' && json != undefined) {
      const expression = this.JSONATA(path);
      result = (await expression.evaluate(json)) as JSON;
    }
    return result;
  }
}
