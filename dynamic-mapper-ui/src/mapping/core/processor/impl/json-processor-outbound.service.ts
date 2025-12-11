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
import * as _ from 'lodash';
import { BaseProcessorOutbound } from '../base-processor-outbound.service';
import { API, getGenericDeviceIdentifier, Mapping } from '../../../../shared';
import {
  ProcessingContext,
  SubstituteValue} from '../processor.model';
import { Injectable } from '@angular/core';
import {
  randomIdAsString} from '../../../shared/util';
import { IExternalIdentity } from '@c8y/client';
import { processSubstitute } from '../processor.model';

@Injectable({ providedIn: 'root' })
export class JSONProcessorOutbound extends BaseProcessorOutbound {
  deserializePayload(
    mapping: Mapping,
    message: any,
    context:ProcessingContext
  ): ProcessingContext {
    context.payload = message;
    return context;
  }

  async extractFromSource(context: ProcessingContext) {
    const { mapping, processingCache, payload } = context;

    // let sourceId: any  = await this.evaluateExpression(
    //   payload,
    //   API[mapping.targetAPI].identifier
    // );

    let sourceId: any  = await this.evaluateExpression(
      payload,
      getGenericDeviceIdentifier(mapping)
    );

    context.sourceId = sourceId.toString();

    if (mapping.useExternalId &&  "" !== mapping.externalIdType) {
      let externalId: IExternalIdentity;
      try {
          externalId = await this.c8yAgent.resolveGlobalId2ExternalId(
          context.sourceId,
          mapping.externalIdType,
          context
        );
      } catch (e) {
        //console.log(
        //  `External id ${extractedSourceContent}, ${mapping.externalIdType} doesn't exist! Just return original id ${extractedSourceContent}`
        //);
        if (context.sendPayload) {
          throw new Error(
            `External id ${externalId} for type ${mapping.externalIdType} not found!`
          );
        } else {
          // if this was running in Cumulocity, the external id could be resolved. Thus we create a device and use this for simulation
          const externalIentifier = `GENERATED_EXTERNAL_ID_${randomIdAsString()}`;
          const simulatedDevice = await this.c8yAgent.upsertDevice({ externalId: `${externalIentifier}`, type: mapping.externalIdType }, context);
          externalId = await this.c8yAgent.resolveGlobalId2ExternalId(
            context.sourceId,
            mapping.externalIdType,
            context
          );
        }
      }
     }


    // iterate over substitutions BEGIN
    // mapping.substitutions.forEach(async (substitution) => {
    for (const substitution of mapping.substitutions) {
      console.log('Substitution: ', substitution);
      try {
        // step 1 extract content from inbound payload
        let extractedSourceContent: any  = await this.evaluateExpression(
          payload,
          substitution.pathSource
        );

        // step 2 analyze extracted content: textual, array
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

      } catch (error) {
        context.errors.push(error.message);
      }
    }
    // iterate over substitutions END
  }
}
