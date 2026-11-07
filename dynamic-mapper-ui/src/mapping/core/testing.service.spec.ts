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

import { TestBed } from '@angular/core/testing';
import { TranslateService } from '@ngx-translate/core';
import { FetchClient } from '@c8y/client';
import { HttpStatusCode } from '@angular/common/http';
import { TestingService } from './testing.service';
import { BASE_URL, PATH_TESTING_ENDPOINT, SharedService, Operation, Direction, MappingType, TransformationType, Mapping } from '../../shared';
import { TestContext } from '../../shared/mapping/processor/processor.model';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function mockFetchResponse(ok: boolean, body: any, status = ok ? 200 : 400): any {
  return { ok, status, statusText: ok ? 'OK' : 'Bad Request', json: async () => body };
}

/** An error response whose body is not JSON, e.g. the HTML page of a gateway. */
function mockNonJsonResponse(status: number): any {
  return {
    ok: false, status, statusText: 'Error',
    json: async () => { throw new SyntaxError("Unexpected token '<', \"<html>\" is not valid JSON"); }
  };
}

function makeMapping(): Mapping {
  return {
    id: '1',
    identifier: 'test-mapping',
    name: 'Test',
    direction: Direction.INBOUND,
    targetAPI: 'MEASUREMENT',
    mappingType: MappingType.JSON,
    transformationType: TransformationType.DEFAULT,
    substitutions: [],
    sourceTemplate: '{}',
    targetTemplate: '{}',
    mappingTopic: 'a/b',
    mappingTopicSample: 'a/b',
    active: true,
    debug: false,
    tested: false,
    filterMapping: '',
    createNonExistingDevice: false,
    updateExistingDevice: false,
    useExternalId: false,
    externalIdType: '',
    qos: undefined
  } as Mapping;
}

function makeTestContext(): TestContext {
  return { mapping: makeMapping(), payload: '{}', send: false };
}

// ---------------------------------------------------------------------------
// TestingService
// ---------------------------------------------------------------------------

describe('TestingService', () => {
  let service: TestingService;
  let mockClient: jasmine.SpyObj<FetchClient>;
  let mockSharedService: jasmine.SpyObj<SharedService>;

  beforeEach(() => {
    mockClient = jasmine.createSpyObj<FetchClient>('FetchClient', ['fetch']);
    mockSharedService = jasmine.createSpyObj<SharedService>('SharedService', ['runOperation']);

    TestBed.configureTestingModule({
      providers: [
        TestingService,
        { provide: FetchClient, useValue: mockClient },
        { provide: SharedService, useValue: mockSharedService },
        // The app's missing-translation handler returns the key with its {{placeholders}} filled.
        {
          provide: TranslateService,
          useValue: {
            instant: (key: string, params?: Record<string, unknown>) =>
              key.replace(/\{\{\s*(\w+)\s*\}\}/g, (_m: string, name: string) => String(params?.[name] ?? ''))
          }
        }
      ]
    });

    service = TestBed.inject(TestingService);
  });

  describe('testMapping', () => {
    it('should POST to the testing endpoint and return the parsed result', async () => {
      const responseBody = { success: true, requests: [], errors: [], warnings: [], logs: [] };
      mockClient.fetch.and.resolveTo(mockFetchResponse(true, responseBody));

      const context = makeTestContext();
      const result = await service.testMapping(context);

      expect(mockClient.fetch).toHaveBeenCalledWith(
        `${BASE_URL}/${PATH_TESTING_ENDPOINT}/mapping`,
        jasmine.objectContaining({
          method: 'POST',
          body: JSON.stringify(context)
        })
      );
      expect(result).toEqual(responseBody as any);
    });

    it('should throw with the backend message when the response is not ok', async () => {
      mockClient.fetch.and.resolveTo(mockFetchResponse(false, { message: 'Mapping invalid' }));

      await expectAsync(service.testMapping(makeTestContext())).toBeRejectedWithError('Mapping invalid');
    });

    it('should name the HTTP status when the error body has no message', async () => {
      mockClient.fetch.and.resolveTo(mockFetchResponse(false, {}));

      await expectAsync(service.testMapping(makeTestContext())).toBeRejectedWithError('Could not be tested! (HTTP 400)');
    });

    it('should not fail with a JSON syntax error when a gateway answers with an HTML page', async () => {
      mockClient.fetch.and.resolveTo(mockNonJsonResponse(504));

      await expectAsync(service.testMapping(makeTestContext())).toBeRejectedWithError(
        'The Dynamic Mapper service did not answer (HTTP 504). Check that it is running and try again.');
    });

    it('should say which role is missing on 403', async () => {
      mockClient.fetch.and.resolveTo(mockFetchResponse(false, { error: 'Forbidden' }, 403));

      await expectAsync(service.testMapping(makeTestContext())).toBeRejectedWithError(
        'Testing a mapping needs the Dynamic Mapper admin or create role.');
    });

    it('should tell an expired session to sign in again on 401', async () => {
      mockClient.fetch.and.resolveTo(mockNonJsonResponse(401));

      await expectAsync(service.testMapping(makeTestContext())).toBeRejectedWithError(
        'Your session has expired. Sign in again and repeat the test.');
    });

    it('should show the backend message of a busy tenant (409) as it is', async () => {
      mockClient.fetch.and.resolveTo(mockFetchResponse(false, { message: 'Another test of this tenant is still running.' }, 409));

      await expectAsync(service.testMapping(makeTestContext())).toBeRejectedWithError(
        'Another test of this tenant is still running.');
    });
  });

  describe('resetMockCache', () => {
    it('should clear both mock caches in parallel', async () => {
      mockSharedService.runOperation.and.resolveTo({ status: HttpStatusCode.Created } as any);

      await service.resetMockCache();

      expect(mockSharedService.runOperation).toHaveBeenCalledWith({
        operation: Operation.CLEAR_CACHE,
        parameter: { cacheId: 'MOCK_IDENTITY_CACHE' }
      });
      expect(mockSharedService.runOperation).toHaveBeenCalledWith({
        operation: Operation.CLEAR_CACHE,
        parameter: { cacheId: 'MOCK_INVENTORY_CACHE' }
      });
    });

    it('should throw if the identity cache clear does not return 201', async () => {
      mockSharedService.runOperation.and.resolveTo({ status: HttpStatusCode.InternalServerError } as any);

      await expectAsync(service.resetMockCache()).toBeRejectedWithError('Failed to clear cache!');
    });
  });
});
