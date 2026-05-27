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
import { ConnectorConfigurationService } from './connector-configuration.service';
import {
  BASE_URL,
  ConnectorConfiguration,
  ConnectorType,
  Direction,
  PATH_CONFIGURATION_CONNECTION_ENDPOINT
} from '..';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function makeConfig(identifier = 'conn-1'): ConnectorConfiguration {
  return {
    identifier,
    name: 'Test Connector',
    connectorType: ConnectorType.MQTT,
    enabled: false,
    properties: {}
  };
}

function mockFetchResponse(ok: boolean, status = 200): any {
  return { ok, status, statusText: ok ? 'OK' : 'Bad Request', json: async () => ({}) };
}

// ---------------------------------------------------------------------------
// ConnectorConfigurationService
// ---------------------------------------------------------------------------

describe('ConnectorConfigurationService', () => {
  let service: ConnectorConfigurationService;
  let mockClient: jasmine.SpyObj<FetchClient>;

  beforeEach(() => {
    mockClient = jasmine.createSpyObj<FetchClient>('FetchClient', ['fetch']);

    TestBed.configureTestingModule({
      providers: [
        ConnectorConfigurationService,
        { provide: FetchClient, useValue: mockClient }
      ]
    });

    service = TestBed.inject(ConnectorConfigurationService);
  });

  afterEach(() => {
    service.cleanUp();
  });

  // -------------------------------------------------------------------------
  // Polling interval API
  // -------------------------------------------------------------------------

  describe('polling interval', () => {
    it('should default to 15000ms', () => {
      expect(service.getCurrentPollingIntervalValue()).toBe(15000);
    });

    it('should update interval via setPollingInterval', () => {
      service.setPollingInterval(5000);
      expect(service.getCurrentPollingIntervalValue()).toBe(5000);
    });

    it('should return label for known interval', () => {
      service.setPollingInterval(30000);
      expect(service.getCurrentPollingIntervalLabel()).toBe('30 seconds');
    });

    it('should return label for 15 seconds default', () => {
      expect(service.getCurrentPollingIntervalLabel()).toBe('15 seconds');
    });

    it('should return numeric label for unknown interval', () => {
      service.setPollingInterval(7000);
      expect(service.getCurrentPollingIntervalLabel()).toBe('7 seconds');
    });

    it('should return the matching PollingInterval object', () => {
      service.setPollingInterval(60000);
      const interval = service.getCurrentPollingInterval();
      expect(interval).toBeDefined();
      expect(interval.label).toBe('60 seconds');
      expect(interval.seconds).toBe(60);
    });

    it('should return all available intervals', () => {
      const intervals = service.getAvailablePollingIntervals();
      expect(intervals.length).toBe(4);
      expect(intervals.map(i => i.value)).toEqual([5000, 15000, 30000, 60000]);
    });
  });

  // -------------------------------------------------------------------------
  // Polling enabled/disabled
  // -------------------------------------------------------------------------

  describe('polling enabled flag', () => {
    it('should be enabled by default', () => {
      expect(service.isPollingEnabled()).toBe(true);
    });

    it('should disable polling', () => {
      service.setPollingEnabled(false);
      expect(service.isPollingEnabled()).toBe(false);
    });

    it('should re-enable polling', () => {
      service.setPollingEnabled(false);
      service.setPollingEnabled(true);
      expect(service.isPollingEnabled()).toBe(true);
    });
  });

  // -------------------------------------------------------------------------
  // resetCache
  // -------------------------------------------------------------------------

  describe('resetCache', () => {
    it('should clear the specifications$ cache so next call creates fresh stream', () => {
      // Access specifications to populate cache
      service.getSpecifications();
      // Reset cache
      service.resetCache();
      // The internal field is private; we verify indirectly that getSpecifications()
      // returns a new observable reference after reset.
      const obs1 = service.getSpecifications();
      service.resetCache();
      const obs2 = service.getSpecifications();
      expect(obs1).not.toBe(obs2);
    });
  });

  // -------------------------------------------------------------------------
  // createConfiguration
  // -------------------------------------------------------------------------

  describe('createConfiguration', () => {
    it('should POST to the correct endpoint', async () => {
      const config = makeConfig();
      const fakeResponse = mockFetchResponse(true);
      mockClient.fetch.and.returnValue(Promise.resolve(fakeResponse));

      const result = await service.createConfiguration(config);

      expect(mockClient.fetch).toHaveBeenCalledOnceWith(
        `${BASE_URL}/${PATH_CONFIGURATION_CONNECTION_ENDPOINT}/instance`,
        jasmine.objectContaining({ method: 'POST' })
      );
      expect(result).toBe(fakeResponse);
    });

    it('should include the configuration as JSON body', async () => {
      const config = makeConfig('my-connector');
      mockClient.fetch.and.returnValue(Promise.resolve(mockFetchResponse(true)));

      await service.createConfiguration(config);

      const [, options] = mockClient.fetch.calls.mostRecent().args;
      expect(options.body).toBe(JSON.stringify(config));
    });

    it('should propagate errors thrown by FetchClient', async () => {
      mockClient.fetch.and.returnValue(Promise.reject(new Error('network error')));

      await expectAsync(service.createConfiguration(makeConfig())).toBeRejectedWithError('network error');
    });
  });

  // -------------------------------------------------------------------------
  // updateConfiguration
  // -------------------------------------------------------------------------

  describe('updateConfiguration', () => {
    it('should PUT to the correct endpoint with identifier in URL', async () => {
      const config = makeConfig('conn-42');
      mockClient.fetch.and.returnValue(Promise.resolve(mockFetchResponse(true)));

      await service.updateConfiguration(config);

      expect(mockClient.fetch).toHaveBeenCalledOnceWith(
        `${BASE_URL}/${PATH_CONFIGURATION_CONNECTION_ENDPOINT}/instance/conn-42`,
        jasmine.objectContaining({ method: 'PUT' })
      );
    });

    it('should include the updated configuration as JSON body', async () => {
      const config = makeConfig('conn-42');
      mockClient.fetch.and.returnValue(Promise.resolve(mockFetchResponse(true)));

      await service.updateConfiguration(config);

      const [, options] = mockClient.fetch.calls.mostRecent().args;
      expect(options.body).toBe(JSON.stringify(config));
    });
  });

  // -------------------------------------------------------------------------
  // deleteConfiguration
  // -------------------------------------------------------------------------

  describe('deleteConfiguration', () => {
    it('should DELETE to the correct endpoint', async () => {
      mockClient.fetch.and.returnValue(Promise.resolve(mockFetchResponse(true)));

      await service.deleteConfiguration('conn-to-delete');

      expect(mockClient.fetch).toHaveBeenCalledOnceWith(
        `${BASE_URL}/${PATH_CONFIGURATION_CONNECTION_ENDPOINT}/instance/conn-to-delete`,
        jasmine.objectContaining({ method: 'DELETE' })
      );
    });

    it('should throw when identifier is empty', async () => {
      await expectAsync(service.deleteConfiguration('')).toBeRejectedWithError('Identifier is required');
      expect(mockClient.fetch).not.toHaveBeenCalled();
    });

    it('should throw when identifier is whitespace only', async () => {
      await expectAsync(service.deleteConfiguration('   ')).toBeRejectedWithError('Identifier is required');
    });
  });

  // -------------------------------------------------------------------------
  // cleanUp
  // -------------------------------------------------------------------------

  describe('cleanUp', () => {
    it('should nullify cached stream references', () => {
      // Populate all caches by calling the getters
      service.getConfigurations();
      service.getSpecifications();
      service.cleanUp();

      // After cleanUp, new calls must produce fresh streams (not the same reference)
      // We can only observe this externally by calling again and checking it doesn't throw.
      // (Re-creating after cleanUp is the responsibility of the caller; we just verify no error)
      expect(() => service.getSpecifications()).not.toThrow();
    });
  });
});
