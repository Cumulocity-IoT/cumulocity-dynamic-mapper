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
import { CommonModule } from '@angular/common';
import { Component, OnDestroy, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AlertService, CoreModule } from '@c8y/ngx-components';
import { EditorComponent } from '@c8y/ngx-components/editor';
import { saveAs } from 'file-saver';
import { CollectedServiceLogsService } from './collected-service-logs.service';
import { NativeServiceLogsService } from './native-service-logs.service';
import { LogRequest, LogsUnavailableError, LogWindow, ServiceLogsSource } from './service-logs.model';

const THEME_KEY = 'd11r-service-logs-theme';
const REFRESH_INTERVAL_MS = 10000;
const EPOCH = '1970-01-01T00:00:00.000Z';

/**
 * The log of the dynamic-mapper-service. The tenant that owns the microservice reads the platform's native log;
 * every other tenant cannot, and reads the lines the service collected for it instead. The page tries the native
 * log first and falls back by itself, and says which one it shows. Both sources use the same window and paging
 * semantics, so the buttons behave identically.
 */
@Component({
  selector: 'd11r-service-logs',
  templateUrl: 'service-logs.component.html',
  standalone: true,
  imports: [CoreModule, CommonModule, FormsModule, EditorComponent]
})
export class ServiceLogsComponent implements OnInit, OnDestroy {
  instances: string[] = [];
  selectedInstance: string;
  logsText = '';
  dateFromInput = '';
  darkTheme = true;
  editorOptions: EditorComponent['editorOptions'] = {
    language: 'java',
    readOnly: true,
    wordWrap: 'on',
    minimap: { enabled: false },
    fontSize: 14,
    scrollBeyondLastLine: false
  };
  window: LogWindow;
  source: ServiceLogsSource;
  isLoading = false;
  autoRefresh = false;
  error: string;
  errorHint: string;
  /** The collected log exists but nothing has been collected for this tenant yet. */
  nothingCollected = false;

  private timer: ReturnType<typeof setInterval>;
  /** Numbers each request so an answer that arrives after a newer request was made is ignored. */
  private requestSeq = 0;
  private inFlight = false;

  constructor(
    private nativeLogs: NativeServiceLogsService,
    private collectedLogs: CollectedServiceLogsService,
    private alertService: AlertService
  ) {}

  get isCollected(): boolean {
    return this.source?.kind === 'collected';
  }

  async ngOnInit(): Promise<void> {
    try {
      this.darkTheme = localStorage.getItem(THEME_KEY) !== 'light';
    } catch {
      // storage unavailable
    }
    this.isLoading = true;
    try {
      // The owner tenant reads the platform's log; for anyone else that is refused, and the collected log is used.
      if (!(await this.activate(this.nativeLogs))) {
        await this.activate(this.collectedLogs);
      }
    } finally {
      this.isLoading = false;
    }
  }

  ngOnDestroy(): void {
    this.stopTimer();
  }

  applyDateFrom(): void {
    if (this.dateFromInput) {
      // The picker value is interpreted as UTC, like in the Administration application.
      this.fetch({ dateFrom: new Date(`${this.dateFromInput}Z`).toISOString() });
    }
  }

  toggleTheme(): void {
    this.darkTheme = !this.darkTheme;
    try {
      localStorage.setItem(THEME_KEY, this.darkTheme ? 'dark' : 'light');
    } catch {
      // storage unavailable
    }
  }

  onInstanceChange(): void {
    this.loadLatest();
  }

  loadLatest(): Promise<void> {
    return this.fetch({});
  }

  loadFirst(): Promise<void> {
    return this.fetch({ dateFrom: EPOCH });
  }

  loadNext(): Promise<void> {
    return this.fetch({ dateFrom: this.window?.dateTo });
  }

  loadPrevious(): Promise<void> {
    return this.fetch({ dateTo: this.window?.dateFrom });
  }

  toggleAutoRefresh(): void {
    this.autoRefresh = !this.autoRefresh;
    this.stopTimer();
    if (this.autoRefresh) {
      this.timer = setInterval(() => this.fetch({}, true), REFRESH_INTERVAL_MS);
    }
  }

  async download(): Promise<void> {
    try {
      const parts: string[] = [];
      let request: LogRequest = { instance: this.selectedInstance, dateFrom: EPOCH };
      let window: LogWindow;
      do {
        window = await this.source.load(request);
        parts.push(window.logs.endsWith('\n') ? window.logs : `${window.logs}\n`);
        request = { instance: this.selectedInstance, dateFrom: window.dateTo };
      } while (window.more && window.dateTo);
      saveAs(new Blob(parts, { type: 'text/plain' }), `${this.selectedInstance}.log`);
    } catch (e) {
      console.error(e);
      this.alertService.danger('Failed to download the log.');
    }
  }

  /** Makes `source` the active one and shows its latest lines. Returns false if it cannot serve this tenant. */
  private async activate(source: ServiceLogsSource): Promise<boolean> {
    try {
      await source.init();
      const window = await source.load({});
      this.source = source;
      this.show(window);
      return true;
    } catch (e) {
      if (e instanceof LogsUnavailableError) {
        if (source.kind === 'collected') {
          this.source = source;
          this.error = 'The service log is not available to you.';
          this.errorHint = e.message;
        }
        return false;
      }
      console.error(e);
      this.error = 'Failed to load service logs.';
      this.source = source;
      return true; // a real failure, not "unavailable": do not mask it by falling back
    }
  }

  private show(window: LogWindow): void {
    this.window = window;
    this.instances = window.instances;
    this.selectedInstance = window.instance;
    this.nothingCollected = window.empty;
    this.logsText = window.logs;
    this.dateFromInput = window.dateFrom ? new Date(window.dateFrom).toISOString().slice(0, 19) : '';
  }

  private stopTimer(): void {
    clearInterval(this.timer);
    this.timer = undefined;
  }

  private async fetch(params: { dateFrom?: string; dateTo?: string }, silent = false): Promise<void> {
    if (!this.source) return;
    // A refresh timer must not pile requests up behind a slow one.
    if (silent && this.inFlight) return;
    const ticket = ++this.requestSeq;
    this.inFlight = true;
    this.isLoading = !silent;
    try {
      const window = await this.source.load({ instance: this.selectedInstance, ...params });
      if (ticket !== this.requestSeq) return; // a newer request was started meanwhile: this answer is out of date
      if (!window.logs && !window.empty) {
        if (!silent) this.alertService.info('No logs available.');
        return;
      }
      this.show(window);
    } catch (e) {
      if (ticket !== this.requestSeq) return;
      if (e instanceof LogsUnavailableError) {
        this.stopTimer();
        this.autoRefresh = false;
        this.error = 'The service log is not available to you.';
        this.errorHint = e.message;
      } else if (!silent) {
        this.alertService.danger('Failed to load logs.');
      }
      console.error(e);
    } finally {
      if (ticket === this.requestSeq) {
        this.inFlight = false;
        this.isLoading = false;
      }
    }
  }
}
