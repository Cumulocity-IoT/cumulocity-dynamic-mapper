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
import { Injectable } from '@angular/core';
import * as _ from 'lodash';
import { API, getGenericDeviceIdentifier, Mapping, RepairStrategy } from '../../../../shared';
import {
  base64ToBytes,
  TIME,
} from '../../../shared/util';
import { BaseProcessorInbound } from '../base-processor-inbound.service';
import {
  evaluateWithArgs,
  ProcessingContext,
  processSubstitute,
  SubstituteValue,
  SubstituteValueType
} from '../processor.model';
import { CodeTemplateMap, TemplateType } from '../../../../configuration';
import { SubstitutionContext } from '../processor-js.model';

@Injectable({ providedIn: 'root' })
export class CodeBasedProcessorInbound extends BaseProcessorInbound {
  deserializePayload(
    mapping: Mapping,
    message: any,
    context: ProcessingContext
  ): ProcessingContext {
    context.payload = message;
    return context;
  }

  async extractFromSource(context: ProcessingContext) {
    const { mapping, payload } = context;
    const { processingCache } = context;

    const payloadAsString: string = JSON.stringify(payload, null, 4);

    const enc = new TextDecoder("utf-8");
    const codeTemplates: CodeTemplateMap = await this.sharedService.getCodeTemplates();
    const sharedCodeTemplate = codeTemplates[TemplateType.SHARED];
    const sharedCodeTemplateDecoded = enc.decode(base64ToBytes(sharedCodeTemplate.code));
    const mappingCodeTemplateDecoded = enc.decode(base64ToBytes(mapping.code));
    // Modify codeToRun to use arg0 instead of ctx
    const codeToRun = `
            ${mappingCodeTemplateDecoded};
            ${sharedCodeTemplateDecoded};
            // Use arg0 which is the ctx parameter passed to the function
            return extractFromSource(arg0);
            `;

    let substitutionTimeExists: boolean = false;

    let result;

    try {
      const ctx = new SubstitutionContext(getGenericDeviceIdentifier(context.mapping), context.payload);
      // const result = this.evaluateInCurrentScope(codeToRun);
      result = evaluateWithArgs(codeToRun, ctx);
      const substitutions = result.getSubstitutions();
      const keys = substitutions.keySet();

            for (const key of keys) {
              const values = substitutions.get(key);
              // console.log(`Key: ${key}, Value: ${value}`);
              const processingCacheEntry: SubstituteValue[] = _.get(
                processingCache,
                key,
                []
              );
              if (values != null && !values.isEmpty()
                && values.get(0).expandArray) {
                // extracted result from sourcePayload is an array, so we potentially have to
                // iterate over the result, e.g. creating multiple devices
                values.forEach((substitution) => {
                  processSubstitute(processingCacheEntry, substitution.value, substitution);
                });
              } else {
                processSubstitute(processingCacheEntry, values.get(0).value, values.get(0));
              }
      
              processingCache.set(key, processingCacheEntry);
            }

    } catch (error) {
      context.errors.push(error.message);
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
