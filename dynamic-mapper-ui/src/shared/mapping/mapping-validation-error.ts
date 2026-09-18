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

/**
 * One validation failure from the backend's 422 body (`details[]`). Mirrors
 * `dynamic.mapper.model.ValidationIssue`. Everything except `code` is optional: rules that
 * cannot identify a specific element send the code alone.
 */
export interface ValidationIssue {
  /** Machine-readable code, the key into ValidationFormlyError's message catalogue. */
  code: string;
  /** Path of the offending field, e.g. `substitutions[2].pathSource`. */
  field?: string;
  /** Index of the offending substitution — what "Go to problem" navigates to. */
  index?: number;
  /** The offending value, e.g. the expression that would not parse. */
  value?: string;
  /** Why it was rejected, e.g. the expression parser's message. */
  reason?: string;
}

/**
 * A mapping save rejected by backend validation (HTTP 422).
 *
 * Exists because the structured body used to be flattened into a bare `Error` message the
 * moment it arrived, leaving the UI unable to do anything but show a toast. `message` is kept
 * byte-identical to what was thrown before, so existing `error.message` handlers are unaffected;
 * `errors` and `details` are what let the editor point the user at the actual problem.
 */
export class MappingValidationError extends Error {
  constructor(
    message: string,
    readonly errors: string[] = [],
    readonly details: ValidationIssue[] = []
  ) {
    super(message);
    this.name = 'MappingValidationError';
    // Required for `instanceof` to work when targeting ES5 (TypeScript extends-builtin caveat).
    Object.setPrototypeOf(this, MappingValidationError.prototype);
  }

  /** True when the backend identified specific elements the user can be taken to. */
  get hasActionableDetail(): boolean {
    return this.details.some(issue => issue.index !== undefined && issue.index !== null);
  }
}

/**
 * Builds the right error type from a parsed backend error body: a MappingValidationError when the
 * body carries validation codes, otherwise a plain Error with the same message as before.
 */
export function toBackendError(body: any, message: string): Error {
  const errors: string[] = Array.isArray(body?.errors) ? body.errors : [];
  const details: ValidationIssue[] = Array.isArray(body?.details) ? body.details : [];
  return errors.length > 0 || details.length > 0
    ? new MappingValidationError(message, errors, details)
    : new Error(message);
}
