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
import {
  clearStoredExplorerSession,
  EXPLORER_SESSION_STORAGE_KEY,
  readStoredExplorerSession,
  writeStoredExplorerSession
} from './explorer-session-store';

describe('explorer session store', () => {
  beforeEach(() => localStorage.removeItem(EXPLORER_SESSION_STORAGE_KEY));
  afterEach(() => localStorage.removeItem(EXPLORER_SESSION_STORAGE_KEY));

  const session = {
    sessionId: 's1',
    connectorName: 'MQTT',
    sessionConnectorIdentifier: 'c1',
    sessionTopic: 'a/#',
    sessionDirection: 'OUTBOUND' as const,
    sessionMaxMessages: 20,
    sessionTTLMinutes: 5,
    sessionDeviceType: 't',
    sessionSourceId: '7',
    sessionDeviceName: 'Pump',
    sessionDeviceTypeFilter: null
  };

  it('reads back what was written', () => {
    writeStoredExplorerSession(session);

    expect(readStoredExplorerSession()).toEqual(session);
  });

  it('fills defaults for fields missing from an older stored session', () => {
    localStorage.setItem(EXPLORER_SESSION_STORAGE_KEY, JSON.stringify({ sessionId: 's2' }));

    expect(readStoredExplorerSession()).toEqual({
      sessionId: 's2',
      connectorName: '',
      sessionConnectorIdentifier: '',
      sessionTopic: '',
      sessionDirection: 'INBOUND',
      sessionMaxMessages: 50,
      sessionTTLMinutes: 10,
      sessionDeviceType: null,
      sessionSourceId: null,
      sessionDeviceName: null,
      sessionDeviceTypeFilter: null
    });
  });

  it('returns null and removes data that is unreadable or has no session id', () => {
    localStorage.setItem(EXPLORER_SESSION_STORAGE_KEY, '{not json');
    expect(readStoredExplorerSession()).toBeNull();
    expect(localStorage.getItem(EXPLORER_SESSION_STORAGE_KEY)).toBeNull();

    writeStoredExplorerSession({ ...session, sessionId: null });
    expect(readStoredExplorerSession()).toBeNull();
    expect(localStorage.getItem(EXPLORER_SESSION_STORAGE_KEY)).toBeNull();
  });

  it('returns null when nothing is stored, and clear removes the entry', () => {
    expect(readStoredExplorerSession()).toBeNull();
    writeStoredExplorerSession(session);
    clearStoredExplorerSession();
    expect(localStorage.getItem(EXPLORER_SESSION_STORAGE_KEY)).toBeNull();
  });
});
