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
import * as _ from 'lodash';
import { API, Mapping, RepairStrategy } from '../../../../shared';
import { PayloadProcessorInbound } from '../payload-processor-inbound.service';
import {
  ProcessingContext,
  SubstituteValue,
  SubstituteValueType
} from '../processor.model';
import {
  TIME,
  TOKEN_TOPIC_LEVEL,
  splitTopicExcludingSeparator
} from '../../../shared/util';
import { processSubstitute } from '../util';

@Injectable({ providedIn: 'root' })
export class JSONProcessorInbound extends PayloadProcessorInbound {
  deserializePayload(
    mapping: Mapping,
    message: any,
    context:ProcessingContext
  ): ProcessingContext {
    context.payload = message;
    return context;
  }

  async extractFromSource(context: ProcessingContext) {
    const { mapping } = context;
    const payloadJsonNode: JSON = context.payload;
    const { postProcessingCache } = context;
    const topicLevels = splitTopicExcludingSeparator(context.topic);
    payloadJsonNode[TOKEN_TOPIC_LEVEL] = topicLevels;

    const payload: string = JSON.stringify(payloadJsonNode, null, 4);
    let substitutionTimeExists: boolean = false;

    // iterate over substitutions BEGIN
    // mapping.substitutions.forEach(async (substitution) => {
    for (const substitution of mapping.substitutions) {
      let extractedSourceContent: JSON;
      try {
        // step 1 extract content from inbound payload
        extractedSourceContent = await this.evaluateExpression(
          JSON.parse(mapping.sourceTemplate),
          substitution.pathSource
        );

        // step 2 analyse extracted content: textual, array
        const postProcessingCacheEntry: SubstituteValue[] = _.get(
          postProcessingCache,
          substitution.pathTarget,
          []
        );
        if (Array.isArray(extractedSourceContent) && substitution.expandArray) {
          // extracted result from sourcePayload is an array, so we potentially have to
          // iterate over the result, e.g. creating multiple devices
          extractedSourceContent.forEach((jn) => {
            processSubstitute(postProcessingCacheEntry, jn, substitution, mapping);
          });
        } else {
          processSubstitute(postProcessingCacheEntry, extractedSourceContent, substitution, mapping);
        }
        postProcessingCache.set(
          substitution.pathTarget,
          postProcessingCacheEntry
        );

        //console.log(
        //  `Evaluated substitution (pathSource:substitute)/(${substitution.pathSource}:${extractedSourceContent}), (pathTarget)/(${substitution.pathTarget})`
        //);

        if (substitution.pathTarget === TIME) {
          substitutionTimeExists = true;
        }
      } catch (error) {
        context.errors.push(error.message);
      }
    }

    // no substitution for the time property exists, then use the system time
    if (!substitutionTimeExists && mapping.targetAPI != API.INVENTORY.name && mapping.targetAPI != API.OPERATION.name) {
      const postProcessingCacheEntry: SubstituteValue[] = _.get(
        postProcessingCache,
        TIME,
        []
      );
      postProcessingCacheEntry.push({
        value: new Date().toISOString(),
        type: SubstituteValueType.TEXTUAL,
        repairStrategy: RepairStrategy.DEFAULT
      });

      postProcessingCache.set(TIME, postProcessingCacheEntry);
    }
  }
}
