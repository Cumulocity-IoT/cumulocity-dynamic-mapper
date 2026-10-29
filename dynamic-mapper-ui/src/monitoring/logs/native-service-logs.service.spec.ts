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
import { ApplicationService, InventoryService } from '@c8y/client';
import { NativeServiceLogsService } from './native-service-logs.service';
import { LogsUnavailableError } from './service-logs.model';

describe('NativeServiceLogsService', () => {
  const POD = 'dynamic-mapper-service-scope-t1-deployment-abc';

  function make(options: { apps?: any[]; instancesByType?: Record<string, string[]>; logs?: () => Promise<any> }) {
    const application = {
      list: async () => ({ data: options.apps ?? [] }),
      getInstanceLog: options.logs ?? (async () => ({ data: { dateFrom: 'a', dateTo: 'b', logs: 'l', truncated: true } }))
    } as unknown as ApplicationService;
    const inventory = {
      list: async (filter: { type: string }) => ({
        data: [{ c8y_Status: { instances: Object.fromEntries((options.instancesByType?.[filter.type] ?? []).map((i) => [i, {}])) } }]
      })
    } as unknown as InventoryService;
    return new NativeServiceLogsService(application, inventory);
  }

  it('picks the application record that actually lists instances, not a stale one with the same name', async () => {
    const service = make({
      apps: [{ id: 1, name: 'dynamic-mapper-service' }, { id: 2, name: 'dynamic-mapper-service' }],
      instancesByType: { c8y_Application_2: [POD] }
    });

    await service.init();
    const window = await service.load({});

    expect(window.instance).toBe(POD);
    expect(window.instances).toEqual([POD]);
    expect(window.more).toBe(true);
  });

  it('is unavailable when the tenant sees no running instance', async () => {
    const service = make({ apps: [{ id: 1, name: 'dynamic-mapper-service' }], instancesByType: {} });

    const error = await service.init().catch((e) => e);

    expect(error instanceof LogsUnavailableError).toBe(true);
  });

  it('turns the 403 a subscribing tenant gets into "unavailable"', async () => {
    const service = make({
      apps: [{ id: 1, name: 'dynamic-mapper-service' }],
      instancesByType: { c8y_Application_1: [POD] },
      logs: async () => {
        throw { res: { status: 403 } };
      }
    });
    await service.init();

    const error = await service.load({}).catch((e) => e);

    expect(error instanceof LogsUnavailableError).toBe(true);
    expect(error.status).toBe(403);
  });

  it('filters the lines of the loaded window by mapping, and leaves the cursors alone', async () => {
    const logs = [
      '2026-10-16T04:13:01.001Z  INFO 1 --- [t] [t1] d.m.Foo : connector',
      '2026-10-16T04:13:02.002Z  INFO 1 --- [t] [t1] [m:aaa] d.m.Foo : a1',
      '2026-10-16T04:13:03.003Z  INFO 1 --- [t] [t1] [m:bbb] d.m.Foo : b1'
    ].join('\n');
    const service = make({
      apps: [{ id: 1, name: 'dynamic-mapper-service' }],
      instancesByType: { c8y_Application_1: [POD] },
      logs: async () => ({ data: { dateFrom: 'a', dateTo: 'b', logs, truncated: true } })
    });
    await service.init();

    const all = await service.load({});
    const filtered = await service.load({ mapping: 'aaa' });

    expect(all.logs).toBe(logs);
    expect(filtered.logs).toBe('2026-10-16T04:13:02.002Z  INFO 1 --- [t] [t1] [m:aaa] d.m.Foo : a1');
    expect(filtered.dateTo).toBe('b');
    expect(filtered.more).toBe(true);
  });
});
