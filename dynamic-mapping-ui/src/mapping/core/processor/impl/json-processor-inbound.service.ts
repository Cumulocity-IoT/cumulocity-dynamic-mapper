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
import {
  TIME,
} from '../../../shared/util';
import { BaseProcessorInbound } from '../base-processor-inbound.service';
import {
  ProcessingContext,
  processSubstitute,
  SubstituteValue,
  SubstituteValueType
} from '../processor.model';

@Injectable({ providedIn: 'root' })
export class JSONProcessorInbound extends BaseProcessorInbound {
  deserializePayload(
    mapping: Mapping,
    message: any,
    context:ProcessingContext
  ): ProcessingContext {
    context.payload = message;
    return context;
  }

  async extractFromSource(context: ProcessingContext) {
    const { mapping, payload } = context;
    const { processingCache } = context;

    const payloadAsString: string = JSON.stringify(payload, null, 4);
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
        const processingCacheEntry: SubstituteValue[] = _.get(
          processingCache,
          substitution.pathTarget,
          []
        );
        if (Array.isArray(extractedSourceContent) && substitution.expandArray) {
          // extracted result from sourcePayload is an array, so we potentially have to
          // iterate over the result, e.g. creating multiple devices
          extractedSourceContent.forEach((jn) => {
            processSubstitute(processingCacheEntry, jn, substitution);
          });
        } else {
          processSubstitute(processingCacheEntry, extractedSourceContent, substitution);
        }
        processingCache.set(
          substitution.pathTarget,
          processingCacheEntry
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
      const processingCacheEntry: SubstituteValue[] = _.get(
        processingCache,
        TIME,
        []
      );
      processingCacheEntry.push({
        value: new Date().toISOString(),
        type: SubstituteValueType.TEXTUAL,
        repairStrategy: RepairStrategy.DEFAULT
      });

      processingCache.set(TIME, processingCacheEntry);
    }
  }
}
