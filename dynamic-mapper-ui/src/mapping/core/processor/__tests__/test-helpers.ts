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

/**
 * Test helpers for processor unit tests.
 * Provides mock services and utility functions for testing.
 */

import { IExternalIdentity, IManagedObject } from '@c8y/client';
import { Subject } from 'rxjs';
import { ServiceConfiguration } from '../../../../configuration';

/**
 * Mock AlertService for testing
 */
export class MockAlertService {
  add = jasmine.createSpy('add');
  warning = jasmine.createSpy('warning');
  danger = jasmine.createSpy('danger');
  info = jasmine.createSpy('info');
  success = jasmine.createSpy('success');

  reset(): void {
    this.add.calls.reset();
    this.warning.calls.reset();
    this.danger.calls.reset();
    this.info.calls.reset();
    this.success.calls.reset();
  }
}

/**
 * Mock FacadeInventoryService for testing
 */
export class MockFacadeInventoryService {
  private cache: Map<string, IManagedObject> = new Map();

  initializeCache = jasmine.createSpy('initializeCache');

  create = jasmine.createSpy('create').and.callFake((obj: IManagedObject) => {
    const id = obj.id || `mock-id-${Date.now()}`;
    const created = { ...obj, id };
    this.cache.set(id, created);
    return Promise.resolve({ data: created, res: { status: 201 } });
  });

  update = jasmine.createSpy('update').and.callFake((obj: IManagedObject) => {
    if (this.cache.has(obj.id)) {
      this.cache.set(obj.id, obj);
      return Promise.resolve({ data: obj, res: { status: 200 } });
    }
    return Promise.reject({ res: { status: 404 } });
  });

  detail = jasmine.createSpy('detail').and.callFake((id: string) => {
    const obj = this.cache.get(id);
    if (obj) {
      return Promise.resolve({ data: obj, res: { status: 200 } });
    }
    return Promise.reject({ res: { status: 404 } });
  });

  setCachedDevice(id: string, device: IManagedObject): void {
    this.cache.set(id, device);
  }

  reset(): void {
    this.cache.clear();
    this.initializeCache.calls.reset();
    this.create.calls.reset();
    this.update.calls.reset();
    this.detail.calls.reset();
  }
}

/**
 * Mock FacadeIdentityService for testing
 */
export class MockFacadeIdentityService {
  private identityCache: Map<string, { identity: IExternalIdentity; globalId: string }> = new Map();

  initializeCache = jasmine.createSpy('initializeCache');

  detail = jasmine.createSpy('detail').and.callFake((identity: IExternalIdentity) => {
    const key = `${identity.type}:${identity.externalId}`;
    const entry = this.identityCache.get(key);
    if (entry) {
      return Promise.resolve({
        data: { ...entry.identity, managedObject: { id: entry.globalId } },
        res: { status: 200 }
      });
    }
    return Promise.reject({ res: { status: 404 } });
  });

  create = jasmine.createSpy('create').and.callFake((identity: IExternalIdentity, globalId: string) => {
    const key = `${identity.type}:${identity.externalId}`;
    this.identityCache.set(key, { identity, globalId });
    return Promise.resolve({
      data: { ...identity, managedObject: { id: globalId } },
      res: { status: 201 }
    });
  });

  setCachedIdentity(identity: IExternalIdentity, globalId: string): void {
    const key = `${identity.type}:${identity.externalId}`;
    this.identityCache.set(key, { identity, globalId });
  }

  reset(): void {
    this.identityCache.clear();
    this.initializeCache.calls.reset();
    this.detail.calls.reset();
    this.create.calls.reset();
  }
}

/**
 * Mock FacadeMeasurementService for testing
 */
export class MockFacadeMeasurementService {
  create = jasmine.createSpy('create').and.callFake((measurement: any) => {
    return Promise.resolve({
      data: { ...measurement, id: `measurement-${Date.now()}` },
      res: { status: 201 }
    });
  });

  reset(): void {
    this.create.calls.reset();
  }
}

/**
 * Mock FacadeEventService for testing
 */
export class MockFacadeEventService {
  create = jasmine.createSpy('create').and.callFake((event: any) => {
    return Promise.resolve({
      data: { ...event, id: `event-${Date.now()}` },
      res: { status: 201 }
    });
  });

  reset(): void {
    this.create.calls.reset();
  }
}

/**
 * Mock FacadeAlarmService for testing
 */
export class MockFacadeAlarmService {
  create = jasmine.createSpy('create').and.callFake((alarm: any) => {
    return Promise.resolve({
      data: { ...alarm, id: `alarm-${Date.now()}` },
      res: { status: 201 }
    });
  });

  reset(): void {
    this.create.calls.reset();
  }
}

/**
 * Mock FacadeOperationService for testing
 */
export class MockFacadeOperationService {
  create = jasmine.createSpy('create').and.callFake((operation: any) => {
    return Promise.resolve({
      data: { ...operation, id: `operation-${Date.now()}` },
      res: { status: 201 }
    });
  });

  reset(): void {
    this.create.calls.reset();
  }
}

/**
 * Mock C8YAgent for testing
 */
export class MockC8YAgent {
  private mockInventory = new MockFacadeInventoryService();
  private mockIdentity = new MockFacadeIdentityService();
  private mockMeasurement = new MockFacadeMeasurementService();
  private mockEvent = new MockFacadeEventService();
  private mockAlarm = new MockFacadeAlarmService();
  private mockOperation = new MockFacadeOperationService();

  initializeCache = jasmine.createSpy('initializeCache').and.callFake(() => {
    this.mockInventory.initializeCache();
    this.mockIdentity.initializeCache();
  });

  createMEAO = jasmine.createSpy('createMEAO').and.callFake((context: any) => {
    const api = context.mapping.targetAPI;
    const request = context.requests[context.requests.length - 1].request;

    if (api === 'MEASUREMENT') {
      return this.mockMeasurement.create(request, context);
    } else if (api === 'EVENT') {
      return this.mockEvent.create(request, context);
    } else if (api === 'ALARM') {
      return this.mockAlarm.create(request, context);
    } else if (api === 'OPERATION') {
      return this.mockOperation.create(request, context);
    } else {
      return this.mockInventory.create(request, context);
    }
  });

  resolveExternalId2GlobalId = jasmine.createSpy('resolveExternalId2GlobalId').and.callFake(
    async (identity: IExternalIdentity) => {
      try {
        const result = await this.mockIdentity.detail(identity);
        return result.data.managedObject.id;
      } catch (error) {
        throw new Error(`External ID not found: ${identity.externalId}`);
      }
    }
  );

  createManagedObjectWithExternalIdentity = jasmine.createSpy('createManagedObjectWithExternalIdentity')
    .and.callFake(async (obj: IManagedObject, identity: IExternalIdentity) => {
      const created = await this.mockInventory.create(obj);
      await this.mockIdentity.create(identity, created.data.id);
      return created;
    });

  /**
   * Helper method to set up a device in cache
   */
  setupDevice(deviceId: string, externalId: string, externalIdType: string = 'c8y_Serial'): void {
    const device: IManagedObject = {
      id: deviceId,
      name: `Test Device ${externalId}`,
      type: 'c8y_TestDevice'
    } as any;
    this.mockInventory.setCachedDevice(deviceId, device);
    this.mockIdentity.setCachedIdentity(
      { type: externalIdType, externalId },
      deviceId
    );
  }

  /**
   * Get the underlying mock services for detailed assertions
   */
  getMockServices() {
    return {
      inventory: this.mockInventory,
      identity: this.mockIdentity,
      measurement: this.mockMeasurement,
      event: this.mockEvent,
      alarm: this.mockAlarm,
      operation: this.mockOperation
    };
  }

  reset(): void {
    this.initializeCache.calls.reset();
    this.createMEAO.calls.reset();
    this.resolveExternalId2GlobalId.calls.reset();
    this.createManagedObjectWithExternalIdentity.calls.reset();

    this.mockInventory.reset();
    this.mockIdentity.reset();
    this.mockMeasurement.reset();
    this.mockEvent.reset();
    this.mockAlarm.reset();
    this.mockOperation.reset();
  }
}

/**
 * Mock SharedService for testing
 */
export class MockSharedService {
  reloadInbound$ = new Subject<void>();
  reloadOutbound$ = new Subject<void>();

  getDynamicMappingServiceAgent = jasmine.createSpy('getDynamicMappingServiceAgent')
    .and.returnValue(Promise.resolve('mock-agent-id-123'));

  getFeatures = jasmine.createSpy('getFeatures').and.returnValue(
    Promise.resolve({
      supportsMessageContext: true,
      supportsCustomFunctions: true
    } as any)
  );

  getServiceConfiguration = jasmine.createSpy('getServiceConfiguration')
    .and.returnValue(
      Promise.resolve({
        logPayload: false,
        logSubstitution: true
      } as ServiceConfiguration)
    );

  getCodeTemplates = jasmine.createSpy('getCodeTemplates').and.returnValue(
    Promise.resolve({
      shared: { code: 'btoa("// Shared code template")' },
      system: { code: 'btoa("// System code template")' }
    })
  );

  reset(): void {
    this.getDynamicMappingServiceAgent.calls.reset();
    this.getFeatures.calls.reset();
    this.getServiceConfiguration.calls.reset();
    this.getCodeTemplates.calls.reset();
  }
}

/**
 * Mock MQTTClient for testing
 */
export class MockMQTTClient {
  publish = jasmine.createSpy('publish').and.returnValue(Promise.resolve());

  reset(): void {
    this.publish.calls.reset();
  }
}

/**
 * Helper function to create a spy object with specific method stubs
 */
export function createSpyObj<T>(
  baseName: string,
  methodNames: (keyof T)[]
): jasmine.SpyObj<T> {
  return jasmine.createSpyObj(baseName, methodNames as string[]);
}

/**
 * Helper to wait for async operations in tests
 */
export function flushPromises(): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, 0));
}

/**
 * Helper to create a resolved promise spy
 */
export function createResolvedPromiseSpy<T>(value: T): jasmine.Spy {
  return jasmine.createSpy().and.returnValue(Promise.resolve(value));
}

/**
 * Helper to create a rejected promise spy
 */
export function createRejectedPromiseSpy(error: any): jasmine.Spy {
  return jasmine.createSpy().and.returnValue(Promise.reject(error));
}

/**
 * Helper to verify spy was called with partial object match
 */
export function expectSpyToHaveBeenCalledWithPartial<T>(
  spy: jasmine.Spy,
  partial: Partial<T>
): void {
  expect(spy).toHaveBeenCalled();
  const calls = spy.calls.all();
  const match = calls.some(call => {
    const arg = call.args[0];
    return Object.keys(partial).every(key => {
      const partialValue = (partial as any)[key];
      const argValue = arg?.[key];
      if (typeof partialValue === 'object' && partialValue !== null) {
        return JSON.stringify(partialValue) === JSON.stringify(argValue);
      }
      return partialValue === argValue;
    });
  });
  expect(match).toBe(
    true,
    `Expected spy to have been called with object containing ${JSON.stringify(partial)}`
  );
}

/**
 * Helper to assert error was thrown with specific message
 */
export async function expectAsyncError(
  asyncFn: () => Promise<any>,
  expectedMessage?: string
): Promise<void> {
  try {
    await asyncFn();
    fail('Expected async function to throw an error');
  } catch (error) {
    if (expectedMessage) {
      expect(error.message).toContain(expectedMessage);
    }
    // Error was thrown as expected
  }
}

/**
 * Helper to create a mock Worker for testing Web Worker code
 */
export class MockWorker implements Worker {
  onmessage: ((this: Worker, ev: MessageEvent) => any) | null = null;
  onerror: ((this: AbstractWorker, ev: ErrorEvent) => any) | null = null;
  onmessageerror: ((this: Worker, ev: MessageEvent) => any) | null = null;

  private messageHandlers: ((e: MessageEvent) => void)[] = [];

  postMessage = jasmine.createSpy('postMessage').and.callFake((data: any) => {
    // Simulate async processing
    setTimeout(() => {
      if (this.onmessage) {
        const event = new MessageEvent('message', { data: { type: 'result', success: true, result: {} } });
        this.onmessage(event);
      }
    }, 0);
  });

  terminate = jasmine.createSpy('terminate');

  addEventListener(type: string, listener: EventListener): void {
    if (type === 'message') {
      this.messageHandlers.push(listener as (e: MessageEvent) => void);
    }
  }

  removeEventListener(type: string, listener: EventListener): void {
    if (type === 'message') {
      const index = this.messageHandlers.indexOf(listener as (e: MessageEvent) => void);
      if (index > -1) {
        this.messageHandlers.splice(index, 1);
      }
    }
  }

  dispatchEvent(event: Event): boolean {
    return true;
  }

  /**
   * Simulate a successful worker response
   */
  simulateSuccess(result: any, logs: string[] = []): void {
    setTimeout(() => {
      if (this.onmessage) {
        const event = new MessageEvent('message', {
          data: { type: 'result', success: true, result, logs }
        });
        this.onmessage(event);
      }
    }, 0);
  }

  /**
   * Simulate a worker error
   */
  simulateError(error: string, logs: string[] = []): void {
    setTimeout(() => {
      if (this.onmessage) {
        const event = new MessageEvent('message', {
          data: { type: 'result', success: false, error: { message: error }, logs }
        });
        this.onmessage(event);
      }
    }, 0);
  }

  /**
   * Simulate a worker timeout
   */
  simulateTimeout(): void {
    // Don't send any message - let the timeout handler kick in
  }
}

/**
 * Global factory function to create mock workers
 */
export function setupMockWorkerFactory(): void {
  const originalWorker = (window as any).Worker;
  (window as any).Worker = class extends MockWorker {
    constructor(scriptURL: string | URL, options?: WorkerOptions) {
      super();
      // Store the script URL if needed for assertions
      (this as any).scriptURL = scriptURL;
    }
  };

  // Store original for cleanup
  (window as any).OriginalWorker = originalWorker;
}

/**
 * Restore the original Worker class
 */
export function restoreWorkerFactory(): void {
  if ((window as any).OriginalWorker) {
    (window as any).Worker = (window as any).OriginalWorker;
    delete (window as any).OriginalWorker;
  }
}
