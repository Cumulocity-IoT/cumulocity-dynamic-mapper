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
import * as _ from 'lodash';
import { PayloadProcessorOutbound } from "../payload-processor-outbound.service";
import { Mapping, API, RepairStrategy } from "../../../shared/mapping.model";
import { splitTopicExcludingSeparator, TOKEN_TOPIC_LEVEL, isNumeric, whatIsIt, TIME } from "../../../shared/util";
import { ProcessingContext, SubstituteValue, SubstituteValueType } from "../prosessor.model";
import { Injectable } from '@angular/core';

@Injectable({ providedIn: 'root' })
export class JSONProcessorOutbound extends PayloadProcessorOutbound {

  public deserializePayload(context: ProcessingContext, mapping: Mapping): ProcessingContext {
    context.payload = JSON.parse(mapping.source);
    return context;
  }

  public extractFromSource(context: ProcessingContext) {
    let mapping: Mapping = context.mapping;
    let payloadJsonNode: JSON = context.payload;
    let postProcessingCache: Map<string, SubstituteValue[]> = context.postProcessingCache;
    let topicLevels = splitTopicExcludingSeparator(context.topic);
    payloadJsonNode[TOKEN_TOPIC_LEVEL] = topicLevels;

    let payload: string = JSON.stringify(payloadJsonNode, null, 4);
    let substitutionTimeExists: boolean = false;

    mapping.substitutions.forEach(substitution => {
      let extractedSourceContent: JSON;
      try {
        // step 1 extract content from inbound payload
        extractedSourceContent = this.evaluateExpression(JSON.parse(mapping.source), substitution.pathSource);

        //step 2 analyse exctracted content: textual, array
        let postProcessingCacheEntry: SubstituteValue[] = _.get(postProcessingCache, substitution.pathTarget, []);
        if (extractedSourceContent == undefined) {
          console.error("No substitution for: ", substitution.pathSource, payload);
          postProcessingCacheEntry.push(
            {
              value: extractedSourceContent,
              type: SubstituteValueType.IGNORE,
              repairStrategy: substitution.repairStrategy
            });
          postProcessingCache.set(substitution.pathTarget, postProcessingCacheEntry);
        } else {
          if (Array.isArray(extractedSourceContent)) {
            if (substitution.expandArray) {
              // extracted result from sourcPayload is an array, so we potentially have to
              // iterate over the result, e.g. creating multiple devices
              extractedSourceContent.forEach(jn => {
                if (isNumeric(jn)) {
                  postProcessingCacheEntry
                    .push({
                      value: jn.toString(),
                      type: SubstituteValueType.NUMBER,
                      repairStrategy: substitution.repairStrategy
                    });
                } else if (whatIsIt(jn) == 'String') {
                  postProcessingCacheEntry
                    .push({
                      value: jn,
                      type: SubstituteValueType.TEXTUAL,
                      repairStrategy: substitution.repairStrategy
                    });
                } else if (whatIsIt(jn) == 'Array') {
                  postProcessingCacheEntry
                    .push({
                      value: jn,
                      type: SubstituteValueType.ARRAY,
                      repairStrategy: substitution.repairStrategy
                    });
                } else {
                  console.warn(`Since result is not textual or number it is ignored: ${jn}`);
                }
              })
              context.cardinality.set(substitution.pathTarget, extractedSourceContent.length);
              postProcessingCache.set(substitution.pathTarget, postProcessingCacheEntry);
            } else {
              // treat this extracted enry as single value, no MULTI_VALUE or MULTI_DEVICE substitution
              context.cardinality.set(substitution.pathTarget, 1);
              postProcessingCacheEntry
                .push({
                  value: extractedSourceContent,
                  type: SubstituteValueType.ARRAY,
                  repairStrategy: substitution.repairStrategy
                })
              postProcessingCache.set(substitution.pathTarget, postProcessingCacheEntry);
            }
          } else if (isNumeric(JSON.stringify(extractedSourceContent))) {
            context.cardinality.set(substitution.pathTarget, 1);
            postProcessingCacheEntry.push(
              { value: extractedSourceContent, type: SubstituteValueType.NUMBER, repairStrategy: substitution.repairStrategy });
            postProcessingCache.set(substitution.pathTarget, postProcessingCacheEntry);
          } else if (whatIsIt(extractedSourceContent) == "String") {
            context.cardinality.set(substitution.pathTarget, 1);
            postProcessingCacheEntry.push(
              { value: extractedSourceContent, type: SubstituteValueType.TEXTUAL, repairStrategy: substitution.repairStrategy });
            postProcessingCache.set(substitution.pathTarget, postProcessingCacheEntry);
          } else {
            console.log(`This substitution, involves an objects for: ${substitution.pathSource}, ${extractedSourceContent}`);
            context.cardinality.set(substitution.pathTarget, 1);
            postProcessingCacheEntry.push(
              { value: extractedSourceContent, type: SubstituteValueType.OBJECT, repairStrategy: substitution.repairStrategy });
            postProcessingCache.set(substitution.pathTarget, postProcessingCacheEntry);
          }
          console.log(`Evaluated substitution (pathSource:substitute)/(${substitution.pathSource}:${extractedSourceContent}), (pathTarget)/(${substitution.pathTarget}`);
        }
        if (substitution.pathTarget === TIME) {
          substitutionTimeExists = true;
        }
      } catch (error) {
        context.errors.push(error.message)
      }
    })

    // no substitution for the time property exists, then use the system time
    if (!substitutionTimeExists && mapping.targetAPI != API.INVENTORY.name) {
      let postProcessingCacheEntry: SubstituteValue[] = _.get(postProcessingCache, TIME, []);
      postProcessingCacheEntry.push(
        { value: new Date().toISOString(), type: SubstituteValueType.TEXTUAL, repairStrategy: RepairStrategy.DEFAULT });

      postProcessingCache.set(TIME, postProcessingCacheEntry);
    }
  }

  public evaluateExpression(json: JSON, path: string): JSON {
    let result: any = '';
    if (path != undefined && path != '' && json != undefined) {
      const expression = this.JSONATA(path)
      result = expression.evaluate(json) as JSON
    }
    return result;
  }

}