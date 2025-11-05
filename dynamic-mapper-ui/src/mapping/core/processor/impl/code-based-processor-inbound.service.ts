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
} from '../../../shared/util';
import { BaseProcessorInbound } from '../base-processor-inbound.service';
import {
  evaluateWithArgsWebWorker,
  KEY_TIME,
  ProcessingContext,
  processSubstitute,
  SubstituteValue,
  SubstituteValueType,
} from '../processor.model';
import { CodeTemplateMap, TemplateType } from '../../../../configuration';
import { SubstitutionContext } from '../processor-js.model';
import { Java_Types_Serialized } from '../processor-js-serialized.model';

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
    const mappingCodeTemplateDecoded = enc.decode(base64ToBytes(mapping.code));
    const sharedCodeTemplate = codeTemplates[TemplateType.SHARED];
    const sharedCodeTemplateDecoded = enc.decode(base64ToBytes(sharedCodeTemplate.code));
    const systemCodeTemplate = codeTemplates[TemplateType.SYSTEM];
    const systemCodeTemplateDecoded = enc.decode(base64ToBytes(systemCodeTemplate.code));
    // Modify codeToRun to use arg0 instead of ctx
    const codeToRun = `${mappingCodeTemplateDecoded}${Java_Types_Serialized}${systemCodeTemplateDecoded}${sharedCodeTemplateDecoded}\n return extractFromSource(arg0);`;
    // console.log("Code to run:", codeToRun);
    let substitutionTimeExists: boolean = false;

    try {
      const ctx = new SubstitutionContext(getGenericDeviceIdentifier(context.mapping), JSON.stringify(context.payload), context.topic);

      // Call our modified evaluateWithArgs
      const evalResult = await evaluateWithArgsWebWorker(codeToRun, ctx) as any;

      // Store logs in context if needed
      context.logs = evalResult.logs;

      if (!evalResult.success) {
        // Handle evaluation error
        const error = evalResult.error;
        context.errors.push(error.message);
        console.error("Error during testing", error);
        context.logs.push(error.message);
        context.errors.push(`Evaluation failed: ${error.message}`);
      }

      if (evalResult.result) {
        // Continue with successful result
        const result = evalResult.result;
        //const substitutions = result.getSubstitutions();
        const substitutions = result['substitutions']['map'];
        const keys = Object.keys(substitutions);

        for (const key of keys) {
          const values = substitutions[key];
          // console.log(`Key: ${key}, Value: ${value}`);
          const processingCacheEntry: SubstituteValue[] = _.get(
            processingCache,
            key,
            []
          );
          if (values != null && values['items'] && values['items'].length > 0
            && values['items'][0].expandArray) {

            // extracted result from sourcePayload is an array, so we potentially have to
            // iterate over the result, e.g. creating multiple devices
            for (let i = 0; i < values['items'].length; i++) {
              const substitution = values['items'][i];
              processSubstitute(processingCacheEntry, substitution.value, substitution);
            }
          } else {
            processSubstitute(processingCacheEntry, values['items'][0].value, values['items'][0]);
          }

          processingCache.set(key, processingCacheEntry);
          if (key === KEY_TIME) {
            substitutionTimeExists = true;
          }
        }
      } else {
        context.warnings.push("Transformation returned no result (substitutions)!");
      }

    } catch (error) {
      context.errors.push(error.message);
    }

    // no substitution for the time property exists, then use the system time
    if (!substitutionTimeExists && mapping.targetAPI != API.INVENTORY.name && mapping.targetAPI != API.OPERATION.name) {
      const processingCacheEntry: SubstituteValue[] = _.get(
        processingCache,
        KEY_TIME,
        []
      );
      processingCacheEntry.push({
        value: new Date().toISOString(),
        type: SubstituteValueType.TEXTUAL,
        repairStrategy: RepairStrategy.CREATE_IF_MISSING
      });

      processingCache.set(KEY_TIME, processingCacheEntry);
    }
  }
}
