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
  processSubstitute,
  SubstituteValue
} from '../processor.model';
import { Injectable } from '@angular/core';
import {
  base64ToBytes
} from '../../../shared/util';
import { CodeTemplateMap, TemplateType } from '../../../../configuration';
import { SubstitutionContext } from '../processor-js.model';
import { Java } from '../processor-js.model';

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

    let sourceId: any = await this.evaluateExpression(
      payload,
      API[mapping.targetAPI].identifier
    );

    let result;
    try {
      const ctx = new SubstitutionContext(getGenericDeviceIdentifier(context.mapping), context.payload);
      // const result = this.evaluateInCurrentScope(codeToRun);
      result = this.evaluateWithArgs(codeToRun, ctx);
    } catch (error) {
      console.error("Error during testing", error);
    }
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
    context.sourceId = sourceId.toString();

    // iterate over substitutions END
  }

  evaluateWithArgs(codeString, ...args) {
    // Add 'Java' as the first parameter
    const paramNames = ['Java'].concat(args.map((_, i) => `arg${i}`)).join(',');

    // Create the function with Java and your other parameters
    const fn = new Function(paramNames, codeString);

    // Call the function with Java as the first argument, followed by your other args
    return fn(Java, ...args);
  }

  evaluateInCurrentScope(codeString) {
    // Create a function that has access to Java
    return Function('Java', `return (${codeString})`)(Java);
  }
}
