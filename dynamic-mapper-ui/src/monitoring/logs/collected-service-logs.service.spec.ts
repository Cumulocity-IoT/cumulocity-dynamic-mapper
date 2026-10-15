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
import { FetchClient } from '@c8y/client';
import { CollectedServiceLogsService } from './collected-service-logs.service';
import { LogsUnavailableError } from './service-logs.model';

describe('CollectedServiceLogsService', () => {
  function serviceAnswering(status: number, body: unknown = {}) {
    const calls: string[] = [];
    const client = {
      fetch: async (url: string) => {
        calls.push(url);
        return { status, ok: status >= 200 && status < 300, json: async () => body };
      }
    } as unknown as FetchClient;
    return { service: new CollectedServiceLogsService(client), calls };
  }

  it('asks the service for the latest lines when no bound is given', async () => {
    const { service, calls } = serviceAnswering(200, { empty: true });

    await service.load({});

    expect(calls[0]).toBe('service/dynamic-mapper-service/monitoring/service-logs');
  });

  it('passes the instance and the bounds on as query parameters', async () => {
    const { service, calls } = serviceAnswering(200, {});

    await service.load({ instance: 'pod-1', dateFrom: '2026-10-15T12:00:00.000Z' });

    expect(calls[0]).toContain('instance=pod-1');
    expect(calls[0]).toContain('dateFrom=2026-10-15T12%3A00%3A00.000Z');
    expect(calls[0]).not.toContain('dateTo');
  });

  it('maps the service answer to a window', async () => {
    const { service } = serviceAnswering(200, {
      dateFrom: 'a',
      dateTo: 'b',
      logs: 'line',
      more: true,
      instance: 'pod-1',
      instances: ['pod-1', 'pod-0'],
      empty: false,
      omitted: 3
    });

    const window = await service.load({});

    expect(window).toEqual({
      dateFrom: 'a',
      dateTo: 'b',
      logs: 'line',
      more: true,
      instance: 'pod-1',
      instances: ['pod-1', 'pod-0'],
      empty: false,
      omitted: 3
    });
  });

  it('reports a missing admin role as unavailable, with the status', async () => {
    const { service } = serviceAnswering(403);

    const error = await service.load({}).catch((e) => e);

    expect(error instanceof LogsUnavailableError).toBe(true);
    expect(error.status).toBe(403);
    expect(error.message).toContain('ROLE_DYNAMIC_MAPPER_ADMIN');
  });

  it('reports any other failure as unavailable too', async () => {
    const { service } = serviceAnswering(500);

    const error = await service.load({}).catch((e) => e);

    expect(error instanceof LogsUnavailableError).toBe(true);
    expect(error.status).toBe(500);
  });
});
