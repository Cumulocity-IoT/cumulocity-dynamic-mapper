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
import { FetchClient } from '@c8y/client';
import { HttpStatusCode } from '@angular/common/http';
import { TestingService } from './testing.service';
import { BASE_URL, PATH_TESTING_ENDPOINT, SharedService, Operation, Direction, MappingType, TransformationType, Mapping } from '../../shared';
import { TestContext } from './processor/processor.model';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function mockFetchResponse(ok: boolean, body: any): any {
  return { ok, status: ok ? 200 : 400, statusText: ok ? 'OK' : 'Bad Request', json: async () => body };
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
  return { mapping: makeMapping(), payload: '{}', send: false, createTestDevice: false };
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
        { provide: SharedService, useValue: mockSharedService }
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

    it('should throw a default message when the error body has no message', async () => {
      mockClient.fetch.and.resolveTo(mockFetchResponse(false, {}));

      await expectAsync(service.testMapping(makeTestContext())).toBeRejectedWithError('Could not be tested!');
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
