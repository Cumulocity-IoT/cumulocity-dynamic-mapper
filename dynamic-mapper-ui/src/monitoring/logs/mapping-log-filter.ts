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

/** A log record starts with an ISO timestamp; everything else is a continuation (stack trace, multi-line payload). */
const RECORD_START = /^\d{4}-\d{2}-\d{2}T\S+\s/;
/** The tag the service writes for a line logged while it processed a payload for one mapping. */
const MAPPING_TAG = /\[m:([A-Za-z0-9_-]{1,64})\] /;
/** Logger and message are separated by this; the tag is part of the header, i.e. in front of it. */
const MESSAGE_SEPARATOR = ' : ';

/** What a mapping identifier may look like; anything else is never sent to the service. */
export const MAPPING_IDENTIFIER = /^[A-Za-z0-9_-]{1,64}$/;

/**
 * The records of `logs` that were written for the mapping with this identifier, joined by newlines.
 *
 * The tag is only looked for in a record's header (before the first ` : `), never in the message, so a payload that
 * happens to contain `[m:x]` does not count. Used for the platform's native log, which the service cannot filter; the
 * collected log is filtered by the service itself.
 */
export function filterLogByMapping(logs: string, mapping: string): string {
  if (!logs || !mapping) {
    return logs ?? '';
  }
  const out: string[] = [];
  let keep = false;
  for (const line of logs.split('\n')) {
    if (RECORD_START.test(line)) {
      const separator = line.indexOf(MESSAGE_SEPARATOR);
      const header = separator < 0 ? line : line.substring(0, separator);
      keep = MAPPING_TAG.exec(header)?.[1] === mapping;
    }
    if (keep) {
      out.push(line);
    }
  }
  return out.join('\n');
}
