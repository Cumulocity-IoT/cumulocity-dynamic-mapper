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
import { PayloadProcessorOutbound } from '../payload-processor-outbound.service';
import { API, Mapping } from '../../../shared';
import {
  ProcessingContext,
  SubstituteValue} from '../processor.model';
import { Injectable } from '@angular/core';
import {
  patchC8YTemplateForTesting,
  processSubstitute,
  randomString} from '../../shared/util';
import { IExternalIdentity } from '@c8y/client';

@Injectable({ providedIn: 'root' })
export class JSONProcessorOutbound extends PayloadProcessorOutbound {
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
    const { postProcessingCache } = context;
    const payload: string = JSON.stringify(context.payload, null, 4);

    const payloadObjectNode = context.payload;

    let sourceId: any  = await this.evaluateExpression(
      payloadObjectNode,
      API[mapping.targetAPI].identifier
    );

    context.sourceId = sourceId.toString();

    if (mapping.useExternalId &&  "" != mapping.externalIdType) {
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
          // if this was runnning in Cumulocity, the external id could be resolved. Thus we create a device and use this for simulation
          const externalIentifier = `GENERATED_EXTERNAL_ID_${randomString()}`;
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
          payloadObjectNode,
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

      } catch (error) {
        context.errors.push(error.message);
      }
    }
    // iterate over substitutions END
  }
}
