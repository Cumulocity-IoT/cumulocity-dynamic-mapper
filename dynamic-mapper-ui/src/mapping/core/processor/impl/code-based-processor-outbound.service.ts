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
  evaluateWithArgsWebWorker,
  ProcessingContext,
  processSubstitute,
  SubstituteValue
} from '../processor.model';
import { Injectable } from '@angular/core';
import {
  base64ToBytes
} from '../../../shared/util';
import { CodeTemplateMap, TemplateType } from '../../../../configuration';
import { SubstitutionContext } from '../processor-js.model';
import { Java_Types_Serialized } from '../processor-js-serialized.model';


@Injectable({ providedIn: 'root' })
export class CodeBasedProcessorOutbound extends BaseProcessorOutbound {
  deserializePayload(
    mapping: Mapping,
    message: any,
    context: ProcessingContext
  ): ProcessingContext {
    context.payload = message;
    return context;
  }

  async extractFromSource(context: ProcessingContext) {
    const { mapping, processingCache, payload } = context;
    const enc = new TextDecoder("utf-8");
    const codeTemplates: CodeTemplateMap = await this.sharedService.getCodeTemplates();
    const mappingCodeTemplateDecoded = enc.decode(base64ToBytes(mapping.code));
    const sharedCodeTemplate = codeTemplates[TemplateType.SHARED];
    const sharedCodeTemplateDecoded = enc.decode(base64ToBytes(sharedCodeTemplate.code));
    const systemCodeTemplate = codeTemplates[TemplateType.SYSTEM];
    const systemCodeTemplateDecoded = enc.decode(base64ToBytes(systemCodeTemplate.code));
    // Modify codeToRun to use arg0 instead of ctx
    const codeToRun = `${mappingCodeTemplateDecoded}${Java_Types_Serialized}${systemCodeTemplateDecoded}${sharedCodeTemplateDecoded}\n return extractFromSource(arg0);`;

    let sourceId: any = await this.evaluateExpression(
      payload,
      API[mapping.targetAPI].identifier
    );

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
        throw new Error(`Evaluation failed: ${error.message}`);
      }

      if (evalResult.result) {     
         // Continue with successful result
        const result = evalResult.result;
        const substitutions = result['substitutions']['map'];
        const keys = Object.keys(substitutions);

        for (const key of keys) {
          const values = substitutions[key];
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
        }
        context.sourceId = sourceId.toString();
      } else  {
        throw new Error ("Transformation returned no result (substitutions)!");
      }

    } catch (error) {
      throw error;
    }
  }
}