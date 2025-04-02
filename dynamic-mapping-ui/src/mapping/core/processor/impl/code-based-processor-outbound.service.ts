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
  ProcessingContext
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
    const codeToRun = sharedCodeTemplateDecoded + ";" + mappingCodeTemplateDecoded + "";

    let sourceId: any = await this.evaluateExpression(
      payload,
      API[mapping.targetAPI].identifier
    );

    const ctx = new SubstitutionContext(getGenericDeviceIdentifier(context.mapping), context.payload);
    const result = this.evaluateInCurrentScope(codeToRun);
    context.sourceId = sourceId.toString();

    // iterate over substitutions END
  }

  evaluateWithArgs(codeString, ...args) {
    // Create parameter names for the function
    const paramNames = args.map((_, i) => `arg${i}`).join(',');
    // Create the function with those parameter names
    const fn = new Function(paramNames, codeString);
    // Call the function with the provided arguments
    return fn(...args);
  }

  evaluateInCurrentScope(codeString) {
    // Create a function that has access to Java
    return Function('Java', `return (${codeString})`)(Java);
  }
}
