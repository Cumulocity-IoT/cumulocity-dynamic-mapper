/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
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
/** The session the Message Explorer page was showing, kept in `localStorage` so a reload can resume it. */
export interface StoredExplorerSession {
  sessionId: string;
  connectorName: string;
  sessionConnectorIdentifier: string;
  sessionTopic: string;
  sessionDirection: 'INBOUND' | 'OUTBOUND';
  sessionMaxMessages: number;
  sessionTTLMinutes: number;
  sessionDeviceType: string | null;
  sessionSourceId: string | null;
  sessionDeviceName: string | null;
  sessionDeviceTypeFilter: string | null;
}

export const EXPLORER_SESSION_STORAGE_KEY = 'd11r-explorer-session';

export function writeStoredExplorerSession(state: Omit<StoredExplorerSession, 'sessionId'> & { sessionId: string | null }): void {
  localStorage.setItem(EXPLORER_SESSION_STORAGE_KEY, JSON.stringify(state));
}

export function clearStoredExplorerSession(): void {
  localStorage.removeItem(EXPLORER_SESSION_STORAGE_KEY);
}

/**
 * The stored session with defaults filled in, or `null` when nothing usable is stored. Unreadable data, and data
 * without a session id, is removed.
 */
export function readStoredExplorerSession(): StoredExplorerSession | null {
  const raw = localStorage.getItem(EXPLORER_SESSION_STORAGE_KEY);
  if (!raw) return null;
  let state: Partial<StoredExplorerSession> | null;
  try {
    state = JSON.parse(raw);
  } catch {
    clearStoredExplorerSession();
    return null;
  }
  if (!state?.sessionId) {
    clearStoredExplorerSession();
    return null;
  }
  return {
    sessionId: state.sessionId,
    connectorName: state.connectorName ?? '',
    sessionConnectorIdentifier: state.sessionConnectorIdentifier ?? '',
    sessionTopic: state.sessionTopic ?? '',
    sessionDirection: state.sessionDirection ?? 'INBOUND',
    sessionMaxMessages: state.sessionMaxMessages ?? 50,
    sessionTTLMinutes: state.sessionTTLMinutes ?? 10,
    sessionDeviceType: state.sessionDeviceType ?? null,
    sessionSourceId: state.sessionSourceId ?? null,
    sessionDeviceName: state.sessionDeviceName ?? null,
    sessionDeviceTypeFilter: state.sessionDeviceTypeFilter ?? null
  };
}
