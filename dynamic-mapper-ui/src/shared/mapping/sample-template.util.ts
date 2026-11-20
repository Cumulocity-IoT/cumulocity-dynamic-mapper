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

const ISO_TIMESTAMP = /\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z/g;

/**
 * True when `template` is empty, `{}`, or equals (ignoring whitespace) one of the generic `samples`,
 * i.e. it holds nothing the user supplied. A template captured in Message Explorer or edited by hand is not generic.
 *
 * The generic samples embed the current time each time they are read, so a stored sample never matches a fresh
 * one byte for byte. ISO timestamps are therefore masked before comparing.
 */
export function isGenericSampleTemplate(template: unknown, samples: Record<string, string>): boolean {
  if (template === undefined || template === null || template === '') return true;
  const canonical = (value: unknown): string | undefined => {
    try {
      return JSON.stringify(typeof value === 'string' ? JSON.parse(value) : value).replace(ISO_TIMESTAMP, '<time>');
    } catch {
      return undefined;
    }
  };
  const current = canonical(template);
  if (current === undefined) return false;
  if (current === '{}') return true;
  return Object.values(samples).some(sample => canonical(sample) === current);
}
