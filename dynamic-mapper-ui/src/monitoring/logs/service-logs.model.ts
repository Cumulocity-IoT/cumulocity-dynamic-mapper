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
export type LogSourceKind = 'native' | 'collected';

/** What the page asks for. Neither date: the latest lines; `dateFrom` only: after it; `dateTo` only: before it. */
export interface LogRequest {
  instance?: string;
  dateFrom?: string;
  dateTo?: string;
}

/** One window of the log, whichever source produced it. */
export interface LogWindow {
  dateFrom: string | null;
  dateTo: string | null;
  logs: string;
  /** More lines exist beyond this window in the direction that was requested. */
  more: boolean;
  instance: string;
  instances: string[];
  /** No log exists at all (collected source only: nothing has been collected for this tenant). */
  empty: boolean;
  /** Lines left out by the collection limit (collected source only). */
  omitted: number;
}

export interface ServiceLogsSource {
  readonly kind: LogSourceKind;
  /** Prepares the source; throws {@link LogsUnavailableError} if it cannot serve this tenant. */
  init(): Promise<void>;
  load(request: LogRequest): Promise<LogWindow>;
}

/** The source cannot serve this tenant or user. `status` is the HTTP status when there was one. */
export class LogsUnavailableError extends Error {
  constructor(
    message: string,
    readonly status?: number
  ) {
    super(message);
    this.name = 'LogsUnavailableError';
  }
}
