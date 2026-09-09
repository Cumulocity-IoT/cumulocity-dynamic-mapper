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
import {
  FetchClient,
} from '@c8y/client';
import {
  BASE_URL,
  SharedService,
  PATH_TESTING_ENDPOINT,
  Operation} from '../../shared';
import { TestContext, TestResult } from './processor/processor.model';
import { HttpStatusCode } from '@angular/common/http';

@Injectable({
  providedIn: 'root'
})
export class TestingService {
  constructor(
    private readonly sharedService: SharedService,
    private readonly client: FetchClient
  ) {}

  // ===== TESTING OPERATIONS =====

  async testMapping(testingContext: TestContext): Promise<TestResult> {
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


  async resetMockCache() {
    const [response1, response2] = await Promise.all([
      this.sharedService.runOperation({
        operation: Operation.CLEAR_CACHE,
        parameter: { cacheId: 'MOCK_IDENTITY_CACHE' }
      }),
      this.sharedService.runOperation({
        operation: Operation.CLEAR_CACHE,
        parameter: { cacheId: 'MOCK_INVENTORY_CACHE' }
      })
    ]);

    if (response1.status !== HttpStatusCode.Created) {
      throw new Error('Failed to clear cache!');
    }

    // You might also want to check response2
    if (response2.status !== HttpStatusCode.Created) {
      throw new Error('Failed to clear cache!');
    }
  }

}
