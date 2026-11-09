/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *  @authors Christof Strack, Stefan Witschel
 *
 */

import { TestBed } from '@angular/core/testing';
import { FetchClient, IFetchResponse } from '@c8y/client';
import { AiAgentService } from './ai-agent.service';

describe('AiAgentService', () => {
  let service: AiAgentService;
  let fetchClient: jasmine.SpyObj<FetchClient>;

  beforeEach(() => {
    fetchClient = jasmine.createSpyObj<FetchClient>('FetchClient', ['fetch']);
    TestBed.configureTestingModule({
      providers: [
        AiAgentService,
        { provide: FetchClient, useValue: fetchClient }
      ]
    });
    service = TestBed.inject(AiAgentService);
  });

  it('does not request agent definitions when the tenant kill switch is on', async () => {
    fetchClient.fetch.and.resolveTo({
      ok: true,
      json: async () => ({ killSwitch: true, telemetryOptOut: false })
    } as IFetchResponse);

    await expectAsync(service.getAIAgents()).toBeResolvedTo([]);

    expect(fetchClient.fetch).toHaveBeenCalledTimes(1);
    expect(fetchClient.fetch).toHaveBeenCalledWith('service/ai/settings', jasmine.anything());
  });

  it('continues with agent discovery when the settings endpoint is unsupported', async () => {
    fetchClient.fetch.and.returnValues(
      Promise.resolve({ ok: false, status: 404, statusText: 'Not Found' } as IFetchResponse),
      Promise.resolve({
        ok: true,
        json: async () => [{ name: 'legacy-agent' }]
      } as IFetchResponse)
    );

    const agents = await service.getAIAgents();

    expect(agents.map(agent => agent.name)).toEqual(['legacy-agent']);
    expect(fetchClient.fetch.calls.allArgs().map(([url]) => url)).toEqual([
      'service/ai/settings',
      'service/ai/agent'
    ]);
  });

  it('treats a missing killSwitch field as an older manager version', async () => {
    fetchClient.fetch.and.returnValues(
      Promise.resolve({
        ok: true,
        json: async () => ({ telemetryOptOut: false })
      } as IFetchResponse),
      Promise.resolve({
        ok: true,
        json: async () => [{ name: 'legacy-agent' }]
      } as IFetchResponse)
    );

    const agents = await service.getAIAgents();

    expect(agents.map(agent => agent.name)).toEqual(['legacy-agent']);
    expect(fetchClient.fetch).toHaveBeenCalledTimes(2);
  });

  it('requests agents only after settings confirm the tenant kill switch is off', async () => {
    fetchClient.fetch.and.returnValues(
      Promise.resolve({
        ok: true,
        json: async () => ({ killSwitch: false, telemetryOptOut: false })
      } as IFetchResponse),
      Promise.resolve({
        ok: true,
        json: async () => [{ name: 'mapper-agent' }]
      } as IFetchResponse)
    );

    const agents = await service.getAIAgents();
    expect(agents.map(agent => agent.name)).toEqual(['mapper-agent']);

    expect(fetchClient.fetch.calls.allArgs().map(([url]) => url)).toEqual([
      'service/ai/settings',
      'service/ai/agent'
    ]);
  });

  it('does not request agents when tenant settings cannot be verified', async () => {
    fetchClient.fetch.and.rejectWith(new Error('settings unavailable'));

    await expectAsync(service.getAIAgents()).toBeRejectedWithError('settings unavailable');

    expect(fetchClient.fetch).toHaveBeenCalledTimes(1);
    expect(fetchClient.fetch).toHaveBeenCalledWith('service/ai/settings', jasmine.anything());
  });
});
