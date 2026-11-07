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
import { gettext } from '@c8y/ngx-components/gettext';
import { TranslateService } from '@ngx-translate/core';
import {
  BASE_URL,
  SharedService,
  PATH_TESTING_ENDPOINT,
  Operation} from '../../shared';
import { TestContext, TestResult } from '../../shared/mapping/processor/processor.model';
import { HttpStatusCode } from '@angular/common/http';

@Injectable({
  providedIn: 'root'
})
export class TestingService {
  private readonly translateService = inject(TranslateService);

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
      throw new Error(await this.describeFailure(data));
    }
    const m = await data.json();
    return m;
  }

  /**
   * The text for a test request that came back with an error status. The backend's own message wins: it
   * names the cause. Without one (a gateway answering with an HTML page, an empty body) the status says
   * what is wrong; parsing such a body as JSON used to throw "Unexpected token '<'" instead.
   */
  private async describeFailure(response: { status: number; json(): Promise<any> }): Promise<string> {
    let backendMessage: unknown;
    try {
      backendMessage = (await response.json())?.message;
    } catch {
      // Not JSON: fall through to the status text.
    }
    if (typeof backendMessage === 'string' && backendMessage.trim()) {
      return backendMessage;
    }
    const status = response.status;
    switch (status) {
      case 401:
        return this.translateService.instant(gettext('Your session has expired. Sign in again and repeat the test.'));
      case 403:
        return this.translateService.instant(gettext('Testing a mapping needs the Dynamic Mapper admin or create role.'));
      case 502:
      case 503:
      case 504:
        return this.translateService.instant(
          gettext('The Dynamic Mapper service did not answer (HTTP {{status}}). Check that it is running and try again.'),
          { status });
      default:
        return this.translateService.instant(gettext('Could not be tested! (HTTP {{status}})'), { status });
    }
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
