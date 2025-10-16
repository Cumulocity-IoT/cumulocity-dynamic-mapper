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

import { inject, Injectable } from '@angular/core';
import {
  FetchClient,
} from '@c8y/client';
import {
  Subject,
} from 'rxjs';
import {
  BASE_URL,
  Direction,
  SharedService,
  Mapping,
  isSubstitutionsAsCode,
  PATH_TESTING_ENDPOINT,
  TransformationType
} from '../../shared';
import { JSONProcessorInbound } from './processor/impl/json-processor-inbound.service';
import { JSONProcessorOutbound } from './processor/impl/json-processor-outbound.service';
import { CodeBasedProcessorOutbound } from './processor/impl/code-based-processor-outbound.service';
import { CodeBasedProcessorInbound } from './processor/impl/code-based-processor-inbound.service';
import {
  EventRealtimeService,
  RealtimeSubjectService
} from '@c8y/ngx-components';
import { ProcessingContext, ProcessingType, SubstituteValue, TestContext } from './processor/processor.model';

@Injectable({
  providedIn: 'root'
})
export class TestingService {
  // Core dependencies
  private readonly eventRealtimeService: EventRealtimeService;

  // Observables and subjects
  private readonly unsubscribe$ = new Subject<void>();


  // Cache
  private _agentId: string;
  private readonly JSONATA = require('jsonata');

  constructor(
    private readonly jsonProcessorInbound: JSONProcessorInbound,
    private readonly jsonProcessorOutbound: JSONProcessorOutbound,
    private readonly codeBasedProcessorOutbound: CodeBasedProcessorOutbound,
    private readonly codeBasedProcessorInbound: CodeBasedProcessorInbound,
    private readonly sharedService: SharedService,
    private readonly client: FetchClient
  ) {
    this.eventRealtimeService = new EventRealtimeService(inject(RealtimeSubjectService));
  }

  // TODO ngOnDestroy is not called for services, find alternative how to stop the realtime service
  ngOnDestroy(): void {
    this.unsubscribe$.next();
    this.unsubscribe$.complete();
    if (this.eventRealtimeService) {
      this.eventRealtimeService.stop();
    }
  }


  // ===== PROCESSING OPERATIONS =====

  public initializeContext(mapping: Mapping): ProcessingContext {
    const ctx: ProcessingContext = {
      mapping: mapping,
      topic:
        mapping.direction == Direction.INBOUND
          ? mapping.mappingTopicSample
          : mapping.publishTopicSample,
      processingType: ProcessingType.UNDEFINED,
      errors: [],
      mappingType: mapping.mappingType,
      processingCache: new Map<string, SubstituteValue[]>(),
      sendPayload: false,
      requests: []
    };
    return ctx;
  }

  initializeCache(dir: Direction): void {
    if (dir == Direction.INBOUND) {
      this.jsonProcessorInbound.initializeCache();
    }
  }

  async testResult(
    context: ProcessingContext,
    message: any
  ): Promise<ProcessingContext> {
    const { mapping } = context;
    if (context.mapping.transformationType == TransformationType.SMART_FUNCTION) {
      const testingContext = { mapping: context.mapping, payload: context.mapping.sourceTemplate, send: false };
      const testingResult = await this.testMapping(testingContext);
      console.log(testingResult);
    } else {
      if (mapping.direction == Direction.INBOUND) {
        if (isSubstitutionsAsCode(mapping)) {
          this.codeBasedProcessorInbound.deserializePayload(mapping, message, context);
          this.codeBasedProcessorInbound.enrichPayload(context);
          await this.codeBasedProcessorInbound.extractFromSource(context);
          this.codeBasedProcessorInbound.validateProcessingCache(context);
          await this.codeBasedProcessorInbound.substituteInTargetAndSend(context);
        } else {
          this.jsonProcessorInbound.deserializePayload(mapping, message, context);
          this.jsonProcessorInbound.enrichPayload(context);
          await this.jsonProcessorInbound.extractFromSource(context);
          this.jsonProcessorInbound.validateProcessingCache(context);
          await this.jsonProcessorInbound.substituteInTargetAndSend(context);
        }
      } else {
        if (isSubstitutionsAsCode(mapping)) {
          this.codeBasedProcessorOutbound.deserializePayload(mapping, message, context);
          await this.codeBasedProcessorOutbound.extractFromSource(context);
          await this.codeBasedProcessorOutbound.substituteInTargetAndSend(context);
        } else {
          this.jsonProcessorOutbound.deserializePayload(mapping, message, context);
          await this.jsonProcessorOutbound.extractFromSource(context);
          await this.jsonProcessorOutbound.substituteInTargetAndSend(context);
        }
      }
    }

    return context;
  }

  async testMapping(testingContext: TestContext): Promise<ProcessingContext[]> {
    const response = this.client.fetch(`${BASE_URL}/${PATH_TESTING_ENDPOINT}/mapping`, {
      headers: {
        'content-type': 'application/json'
      },
      body: JSON.stringify(testingContext),
      method: 'POST'
    });
    const data = await response;
    if (!data.ok) {
      const errorTxt = await data.json();
      throw new Error(errorTxt.message ?? 'Could not be tested!');
    }
    const m = await data.json();
    return m;
  }

}
