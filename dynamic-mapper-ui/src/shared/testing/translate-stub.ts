/*
 * Copyright (c) 2025 Cumulocity GmbH
 *
 * SPDX-License-Identifier: Apache-2.0
 */

import { Provider } from '@angular/core';
import { TranslateService } from '@ngx-translate/core';

/**
 * Fills the `{{placeholders}}` of a message the way the app's missing-translation handler does
 * when a text has no translation: the message ID, with its parameters inserted.
 */
export function interpolateMessage(key: string, params?: Record<string, unknown>): string {
  return params
    ? key.replace(/\{\{\s*(\w+)\s*\}\}/g, (match, name) => (name in params ? String(params[name]) : match))
    : key;
}

/**
 * A `TranslateService` for specs: returns each message as written, with its parameters filled in.
 * Use it where a component or service injects the `TranslateService` and the spec asserts English.
 *
 *   providers: [provideTranslateStub()]
 */
export function provideTranslateStub(): Provider {
  return {
    provide: TranslateService,
    useValue: {
      instant: (key: string, params?: Record<string, unknown>) => interpolateMessage(key, params),
      get: (key: string, params?: Record<string, unknown>) => ({ subscribe: (fn: (v: string) => void) => fn(interpolateMessage(key, params)) }),
      currentLang: 'en',
      onLangChange: { subscribe: () => ({ unsubscribe: () => undefined }) }
    }
  };
}
