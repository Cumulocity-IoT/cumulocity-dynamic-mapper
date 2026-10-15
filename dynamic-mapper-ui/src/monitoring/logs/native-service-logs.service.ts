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
import { ApplicationService, IManagedObject, InventoryService } from '@c8y/client';
import { LogRequest, LogSourceKind, LogsUnavailableError, LogWindow, ServiceLogsSource } from './service-logs.model';

const SERVICE_NAME = 'dynamic-mapper-service';

/**
 * The platform's own log of the microservice. Only the tenant that owns the microservice can read it; every
 * other tenant gets 403 (or cannot find the application), which is reported as {@link LogsUnavailableError}
 * so the page can fall back to the collected log.
 */
@Injectable({ providedIn: 'root' })
export class NativeServiceLogsService implements ServiceLogsSource {
  readonly kind: LogSourceKind = 'native';

  private appId: string;
  private instances: string[] = [];

  constructor(
    private applicationService: ApplicationService,
    private inventoryService: InventoryService
  ) {}

  async init(): Promise<void> {
    try {
      const apps = (await this.applicationService.list({ name: SERVICE_NAME })).data;
      // Several records can carry the name (stale ones of earlier deployments): take the one that lists instances.
      let chosen: { appId: string; instances: string[] } | undefined;
      for (const app of apps.filter((a) => a.name === SERVICE_NAME)) {
        const mos: IManagedObject[] = (
          await this.inventoryService.list({ type: `c8y_Application_${app.id}`, nocache: Date.now() } as any)
        ).data;
        const instances = Object.keys(mos[0]?.['c8y_Status']?.instances ?? {});
        if (instances.length) {
          chosen = { appId: `${app.id}`, instances };
          break;
        }
      }
      if (!chosen) {
        throw new LogsUnavailableError('No running instance of the microservice is visible to this tenant');
      }
      this.appId = chosen.appId;
      this.instances = chosen.instances;
    } catch (e) {
      throw e instanceof LogsUnavailableError ? e : new LogsUnavailableError(`Cannot look up the microservice: ${e}`);
    }
  }

  async load(request: LogRequest): Promise<LogWindow> {
    const instance = request.instance && this.instances.includes(request.instance) ? request.instance : this.instances[0];
    const params: Record<string, string> = {};
    if (request.dateFrom) params['dateFrom'] = request.dateFrom;
    if (request.dateTo) params['dateTo'] = request.dateTo;
    try {
      const { data } = await this.applicationService.getInstanceLog(this.appId, instance, params as any);
      const body = data as unknown as { dateFrom: string; dateTo: string; logs: string; truncated: boolean };
      return {
        dateFrom: body.dateFrom ?? null,
        dateTo: body.dateTo ?? null,
        logs: body.logs ?? '',
        more: !!body.truncated,
        instance,
        instances: this.instances,
        empty: false,
        omitted: 0
      };
    } catch (e) {
      const status = (e as any)?.res?.status ?? (e as any)?.status;
      if (status === 403 || status === 404) {
        throw new LogsUnavailableError('The platform does not serve this tenant the log of the microservice', status);
      }
      throw e;
    }
  }
}
