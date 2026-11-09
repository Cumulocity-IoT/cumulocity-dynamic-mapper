/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

import { interpolateMessage } from './translate-stub';

describe('interpolateMessage', () => {
  it('inserts the parameters into the placeholders', () => {
    expect(interpolateMessage('Version {{version}} of {{ name }}', { version: '1.2', name: 'M' })).toBe('Version 1.2 of M');
  });

  it('keeps a placeholder without a parameter, and a message without parameters', () => {
    expect(interpolateMessage('Hello {{who}}', {})).toBe('Hello {{who}}');
    expect(interpolateMessage('Plain')).toBe('Plain');
  });
});
