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

import { isRawPayload, MAX_EXPLORER_SAMPLES, samplePayloadKind } from './message-explorer.service';

describe('isRawPayload', () => {
  it('is true for binary, XML and plain text, which no JSON mapping can parse', () => {
    expect(isRawPayload({ binary: true, payloadType: 'BINARY' })).toBe(true);
    expect(isRawPayload({ binary: false, payloadType: 'XML' })).toBe(true);
    expect(isRawPayload({ binary: false, payloadType: 'TEXT' })).toBe(true);
  });

  it('is false for JSON and empty payloads', () => {
    expect(isRawPayload({ binary: false, payloadType: 'JSON' })).toBe(false);
    expect(isRawPayload({ binary: false, payloadType: 'EMPTY' })).toBe(false);
  });

  it('falls back to the binary flag when the backend sends no payload type', () => {
    expect(isRawPayload({ binary: true })).toBe(true);
    expect(isRawPayload({ binary: false })).toBe(false);
  });
});

describe('samplePayloadKind', () => {
  it('groups messages by detected payload type so only like can be combined', () => {
    expect(samplePayloadKind({ binary: false, payloadType: 'JSON' })).toBe('JSON');
    expect(samplePayloadKind({ binary: true, payloadType: 'BINARY' })).toBe('BINARY');
    expect(samplePayloadKind({ binary: false, payloadType: 'JSON' }))
      .not.toBe(samplePayloadKind({ binary: true, payloadType: 'BINARY' }));
  });

  it('falls back to the binary flag when the backend sends no payload type', () => {
    expect(samplePayloadKind({ binary: true })).toBe('BINARY');
    expect(samplePayloadKind({ binary: false })).toBe('JSON');
  });

  it('caps a selection at five messages', () => {
    expect(MAX_EXPLORER_SAMPLES).toBe(5);
  });
});
