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
import { FetchClient } from '@c8y/client';
import { BASE_URL, PATH_SERVICE_LOGS_ENDPOINT } from '../../shared';
import { LogRequest, LogSourceKind, LogsUnavailableError, LogWindow, ServiceLogsSource } from './service-logs.model';

/**
 * The log lines of this tenant that the microservice collected into events, read through the service
 * (which enforces ROLE_DYNAMIC_MAPPER_ADMIN). This is what a tenant that merely subscribes to the microservice
 * can see; see docs/feature/service-logs.md.
 */
@Injectable({ providedIn: 'root' })
export class CollectedServiceLogsService implements ServiceLogsSource {
  readonly kind: LogSourceKind = 'collected';

  constructor(private client: FetchClient) {}

  async init(): Promise<void> {
    // Nothing to prepare: the service answers for the calling tenant.
  }

  async flush(): Promise<void> {
    const response = await this.client.fetch(`${BASE_URL}/${PATH_SERVICE_LOGS_ENDPOINT}/flush`, { method: 'POST' });
    if (response.status === 403) {
      throw new LogsUnavailableError('Fetching the service log requires the role ROLE_DYNAMIC_MAPPER_ADMIN', 403);
    }
    if (!response.ok) {
      throw new LogsUnavailableError(`The service answered HTTP ${response.status}`, response.status);
    }
  }

  async load(request: LogRequest): Promise<LogWindow> {
    const query = new URLSearchParams();
    if (request.dateFrom) query.set('dateFrom', request.dateFrom);
    if (request.dateTo) query.set('dateTo', request.dateTo);
    const suffix = query.toString();
    const response = await this.client.fetch(`${BASE_URL}/${PATH_SERVICE_LOGS_ENDPOINT}${suffix ? `?${suffix}` : ''}`);
    if (response.status === 403) {
      throw new LogsUnavailableError('Reading the service log requires the role ROLE_DYNAMIC_MAPPER_ADMIN', 403);
    }
    if (!response.ok) {
      throw new LogsUnavailableError(`The service answered HTTP ${response.status}`, response.status);
    }
    const body = await response.json();
    return {
      dateFrom: body.dateFrom ?? null,
      dateTo: body.dateTo ?? null,
      before: body.before ?? null,
      logs: body.logs ?? '',
      more: !!body.more,
      instance: '',
      instances: [],
      empty: !!body.empty,
      omitted: body.omitted ?? 0
    };
  }
}
