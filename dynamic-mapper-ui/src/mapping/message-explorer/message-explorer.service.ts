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
import { BASE_URL, PATH_EXPLORER_ENDPOINT } from '../../shared/mapping/util';

export interface ExplorerMessage {
  topic: string;
  connectorIdentifier: string;
  connectorName: string;
  clientId?: string;    // broker client identifier that sent the message
  receivedAt: number;   // epoch millis
  payload: string;
  binary: boolean;
  direction: 'INBOUND' | 'OUTBOUND';
  sourceId?: string;    // C8Y managed object ID (device or group, outbound only)
}

export class SessionExpiredError extends Error {
  constructor(sessionId: string) {
    super(`Explorer session ${sessionId} has expired or does not exist`);
    this.name = 'SessionExpiredError';
  }
}

export interface StartSessionRequest {
  connectorIdentifier: string;
  topic: string;
  maxMessages: number;
  sessionTTLMinutes?: number;
  direction: 'INBOUND' | 'OUTBOUND';
  sourceId?: string;    // C8Y managed object ID (device or group) filter (OUTBOUND only)
  deviceType?: string;  // C8Y device type filter (OUTBOUND only)
}

export interface StartSessionResult {
  sessionId: string;
  // Set when the broker subscribe attempt for the session's topic failed (e.g. connector not
  // connected, invalid topic) — the session is still created but will never receive messages.
  subscriptionWarning?: string;
}

@Injectable({ providedIn: 'root' })
export class MessageExplorerService {

  constructor(private readonly client: FetchClient) {}

  async startSession(request: StartSessionRequest): Promise<StartSessionResult> {
    const response = await this.client.fetch(
      `${BASE_URL}/${PATH_EXPLORER_ENDPOINT}/session`,
      {
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify(request),
        method: 'POST'
      }
    );
    if (!response.ok) throw new Error(`HTTP ${response.status}: ${response.statusText}`);
    const body = await response.json();
    return {
      sessionId: body.sessionId as string,
      subscriptionWarning: body.subscriptionWarning as string | undefined
    };
  }

  async stopSession(sessionId: string): Promise<void> {
    const response = await this.client.fetch(
      `${BASE_URL}/${PATH_EXPLORER_ENDPOINT}/session/${sessionId}`,
      { method: 'DELETE' }
    );
    // 204 or 404 — both are acceptable when stopping
    if (!response.ok && response.status !== 404) {
      throw new Error(`HTTP ${response.status}: ${response.statusText}`);
    }
  }

  async getMessages(sessionId: string): Promise<ExplorerMessage[]> {
    const response = await this.client.fetch(
      `${BASE_URL}/${PATH_EXPLORER_ENDPOINT}/session/${sessionId}/messages`,
      { method: 'GET' }
    );
    if (response.status === 404) throw new SessionExpiredError(sessionId);
    if (!response.ok) throw new Error(`HTTP ${response.status}: ${response.statusText}`);
    return (await response.json()) as ExplorerMessage[];
  }

  async clearMessages(sessionId: string): Promise<void> {
    const response = await this.client.fetch(
      `${BASE_URL}/${PATH_EXPLORER_ENDPOINT}/session/${sessionId}/messages`,
      { method: 'DELETE' }
    );
    if (!response.ok && response.status !== 404) {
      throw new Error(`HTTP ${response.status}: ${response.statusText}`);
    }
  }
}
