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
import {
  findDeviceIdentifier,
  splitTopicExcludingSeparator,
  splitTopicIncludingSeparator,
  TOKEN_TOPIC_LEVEL,
} from "../../shared/util";
import { getTypedValue } from "../shared/util";
import { C8YAgent } from "../core/c8y-agent.service";
import {
  ProcessingContext,
  SubstituteValue,
  SubstituteValueType,
} from "./prosessor.model";
import { MQTTClient } from "../core/mqtt-client.service";

@Injectable({ providedIn: "root" })
export abstract class PayloadProcessorOutbound {
  constructor(private alert: AlertService, public c8yAgent: C8YAgent,  private mqttClient: MQTTClient) {}

  public abstract deserializePayload(
    context: ProcessingContext,
    mapping: Mapping
  ): ProcessingContext;

  public abstract extractFromSource(context: ProcessingContext): void;

  protected JSONATA = require("jsonata");

  public async substituteInTargetAndSend(context: ProcessingContext) {
    //step 3 replace target with extract content from o payload
    let mapping = context.mapping;

    let postProcessingCache: Map<string, SubstituteValue[]> =
      context.postProcessingCache;
    const pathTargets = postProcessingCache.keys();

    let predecessor: number = -1;
    let payloadTarget: JSON = null;
    try {
      payloadTarget = JSON.parse(mapping.target);
    } catch (e) {
      this.alert.warning("Target Payload is not a valid json object!");
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
    let deviceSource: string = "undefined";

    for (let pathTarget of pathTargets) {
      let substituteValue: SubstituteValue = {
        value: "NOT_DEFINED" as any,
        type: SubstituteValueType.TEXTUAL,
        repairStrategy: RepairStrategy.DEFAULT,
      };
      if (postProcessingCache.get(pathTarget).length > 0) {
        substituteValue = _.clone(postProcessingCache.get(pathTarget)[0]);
      }
      
      this.substituteValueInObject(
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

      let topicLevels : string [] = payloadTarget[TOKEN_TOPIC_LEVEL];
      if ( !topicLevels && topicLevels.length > 0) {
          // now merge the replaced topic levels
          let c : number = 0;
          let splitTopicInAsList :  string[] = splitTopicIncludingSeparator(context.topic);
          topicLevels.forEach(tl => {
              while (c  < splitTopicInAsList.length
                      && ("/" == (splitTopicInAsList[c]))) {
                  c++;
              }
              splitTopicInAsList[c] = tl;
              c++;
          });

          let resolvedPublishTopic: string = '';
          for ( let d: number = 0; d < splitTopicInAsList.length; d++) {
              resolvedPublishTopic.concat(splitTopicInAsList[d]);
          }
          context.resolvedPublishTopic = resolvedPublishTopic.toString();
      } else {
          context.resolvedPublishTopic = context.mapping.publishTopic;
      }

      // leave the topic for debugging purposes
      //_.unset(payloadTarget, TOKEN_TOPIC_LEVEL);
      let newPredecessor = context.requests.push({
        predecessor: predecessor,
        method: "POST",
        source: deviceSource,
        externalIdType: mapping.externalIdType,
        request: payloadTarget,
        targetAPI: API[mapping.targetAPI].name,
      });
      try {
        let response = await this.mqttClient.createMEAO(context);
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
      `Added payload for sending: ${payloadTarget}, ${mapping.targetAPI}, numberDevices: 1`
    );
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

  public async evaluateExpression(json: JSON, path: string): Promise<JSON> {
    let result: any = "";
    if (path != undefined && path != "" && json != undefined) {
      const expression = this.JSONATA(path);
      result = await expression.evaluate(json) as JSON;
    }
    return result;
  }
}
