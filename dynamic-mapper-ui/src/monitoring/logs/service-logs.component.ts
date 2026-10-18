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
import { ActivatedRoute } from '@angular/router';
import { AlertService, CoreModule, SelectableItem } from '@c8y/ngx-components';
import { EditorComponent } from '@c8y/ngx-components/editor';
import { saveAs } from 'file-saver';
import { PopoverModule } from 'ngx-bootstrap/popover';
import { Direction } from '../../shared';
import { MappingService } from '../../mapping/core/mapping.service';
import { CollectedServiceLogsService } from './collected-service-logs.service';
import { MAPPING_IDENTIFIER } from './mapping-log-filter';
import { NativeServiceLogsService } from './native-service-logs.service';
import { LogRequest, LogsUnavailableError, LogWindow, ServiceLogsSource } from './service-logs.model';

type MappingDirectionFilter = 'INBOUND' | 'OUTBOUND';

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
  imports: [CoreModule, CommonModule, FormsModule, EditorComponent, PopoverModule]
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
  /** Mappings the log can be restricted to: the label is shown, the identifier is what the log lines carry. */
  mappings: { identifier: string; label: string; direction: MappingDirectionFilter }[] = [];
  /** Narrows the mapping list; it does not filter the log by itself. */
  directionFilter: 'ALL' | MappingDirectionFilter = 'ALL';
  /** Identifier of the mapping the log is restricted to; empty: all lines. */
  selectedMapping = '';
  /**
   * What the mapping select offers: all mappings, or only the inbound / outbound ones. Kept as one array that is only
   * replaced when the list really changes — the select rebuilds its state each time its `items` input is set.
   */
  mappingItems: SelectableItem[] = [];

  /** Both sources can serve this tenant (the owner tenant with collection on), so the page offers the choice. */
  canChooseSource = false;
  /** The source that was active before selecting a mapping switched to the collected log; restored on clearing it. */
  private sourceBeforeMapping: ServiceLogsSource | undefined;
  /** Whether the collected log holds anything for this tenant; probed once on opening. */
  private collectedAvailable = false;

  private timer: ReturnType<typeof setInterval>;
  /** Numbers each request so an answer that arrives after a newer request was made is ignored. */
  private requestSeq = 0;
  private inFlight = false;

  constructor(
    private nativeLogs: NativeServiceLogsService,
    private collectedLogs: CollectedServiceLogsService,
    private mappingService: MappingService,
    private alertService: AlertService,
    private route: ActivatedRoute
  ) {}

  get isCollected(): boolean {
    return this.source?.kind === 'collected';
  }

  /**
   * Grid classes of the "From" column: the first row holds the mapping controls (when there are mappings) and the date, and
   * adds up to 12 columns. Without mapping controls the date stays at the right edge. Neither source shows an instance
   * selector: the platform log is read from the instance the service picks.
   */
  get dateColumnClass(): string {
    return this.mappings.length ? 'col-md-5' : 'col-md-5 col-md-offset-7';
  }

  /** A mapping is selected and the window holds none of its lines. */
  get noLinesForMapping(): boolean {
    return !!this.selectedMapping && !this.logsText && !this.isLoading && !this.error;
  }

  async ngOnInit(): Promise<void> {
    try {
      this.darkTheme = localStorage.getItem(THEME_KEY) !== 'light';
    } catch {
      // storage unavailable
    }
    this.isLoading = true;
    try {
      // The mappings come first: a mapping asked for in the URL (the debug label of the mapping grid links here) is only
      // preselected if it exists, so the filter that is applied is always one the list shows.
      await this.loadMappings();
      this.preselectRequestedMapping();
      // Both sources are offered when the tenant can read the platform log and also has a collected one; only the
      // collected log can be filtered by mapping over its whole range, so it is the start when a mapping is asked for.
      this.collectedAvailable = await this.probeCollected();
      if (this.selectedMapping && this.collectedAvailable) {
        await this.activate(this.collectedLogs);
        this.canChooseSource = await this.probeNative();
        // clearing the filter returns to the live platform log
        this.sourceBeforeMapping = this.canChooseSource ? this.nativeLogs : undefined;
      } else if (!(await this.activate(this.nativeLogs))) {
        await this.activate(this.collectedLogs);
      } else {
        this.canChooseSource = this.collectedAvailable;
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

  /**
   * Narrows the list of mappings. A selected mapping that the new list no longer offers is dropped, and the log goes back
   * to all lines; a selection that is still offered stays as it is, so nothing is reloaded for nothing.
   */
  onDirectionChange(): void {
    this.refreshMappingItems();
    if (this.selectedMapping && !this.mappingItems.some((i) => i.value === this.selectedMapping)) {
      this.selectedMapping = '';
      this.changeSourceThenLoad(this.restoreSourceAfterMapping());
    }
  }

  /** A different mapping starts from the newest lines: the old window and its cursors belong to the old filter. */
  onMappingSelect(item: SelectableItem | undefined): void {
    this.selectedMapping = item?.value ?? '';
    this.changeSourceThenLoad(this.selectedMapping ? this.preferCollectedForMapping() : this.restoreSourceAfterMapping());
  }

  /** Loads at once when the source stays the same (nothing awaited), else after it was switched. */
  private changeSourceThenLoad(switching: Promise<void> | undefined): void {
    if (switching) {
      switching.then(() => this.loadLatest());
    } else {
      this.loadLatest();
    }
  }

  /** The select's clear button: back to all lines. */
  onMappingDeselect(): void {
    if (this.selectedMapping) {
      this.selectedMapping = '';
      this.changeSourceThenLoad(this.restoreSourceAfterMapping());
    }
  }

  /** The source switch: an explicit choice, so it is not undone when the mapping filter is cleared. */
  async onSourceChange(collected: boolean): Promise<void> {
    this.sourceBeforeMapping = undefined;
    const target = collected ? this.collectedLogs : this.nativeLogs;
    if (this.source === target) return;
    // A mapping filter belongs to the collected log (the platform log would only search its loaded window), so going to
    // the platform log shows all lines instead of an empty, filtered window.
    if (!collected && this.selectedMapping) {
      this.selectedMapping = '';
    }
    this.stopTimer();
    this.autoRefresh = false;
    this.isLoading = true;
    try {
      await this.activate(target);
    } finally {
      this.isLoading = false;
    }
  }

  /** The note shown while the mapping filter works on the platform log, or switched away from it. */
  get mappingSourceNote(): string | undefined {
    if (!this.selectedMapping) return undefined;
    if (this.sourceBeforeMapping) {
      return 'Mapping filter uses the collected log; the platform log can only be filtered within the loaded window.';
    }
    return undefined;
  }

  /** Only the collected log can select a mapping over its whole range: use it while a mapping is selected. */
  private preferCollectedForMapping(): Promise<void> | undefined {
    if (this.isCollected || !this.canChooseSource) return undefined;
    const previous = this.source;
    this.stopTimer();
    this.autoRefresh = false;
    return this.activateQuietly(this.collectedLogs).then((ok) => {
      if (ok) this.sourceBeforeMapping = previous;
    });
  }

  private restoreSourceAfterMapping(): Promise<void> | undefined {
    const previous = this.sourceBeforeMapping;
    this.sourceBeforeMapping = undefined;
    if (!previous || this.source === previous) return undefined;
    return this.activateQuietly(previous).then(() => undefined);
  }

  /** Switches the active source without loading it; the caller loads the first window. */
  private async activateQuietly(source: ServiceLogsSource): Promise<boolean> {
    try {
      await source.init();
      this.source = source;
      this.selectedInstance = undefined;
      this.error = undefined;
      return true;
    } catch (e) {
      console.error(e);
      return false;
    }
  }

  private async probeCollected(): Promise<boolean> {
    try {
      await this.collectedLogs.init();
      return !(await this.collectedLogs.load({})).empty;
    } catch {
      return false;
    }
  }

  private async probeNative(): Promise<boolean> {
    try {
      await this.nativeLogs.init();
      return true;
    } catch {
      return false;
    }
  }

  loadLatest(): Promise<void> {
    return this.fetch({});
  }

  /** Has the service store what it still holds for this tenant, then shows the newest lines. */
  async fetchNow(): Promise<void> {
    if (!this.source?.flush) return;
    this.isLoading = true;
    try {
      await this.source.flush();
    } catch (e) {
      console.error(e);
      this.alertService.danger('Failed to fetch the service log.');
    } finally {
      this.isLoading = false;
    }
    await this.loadLatest();
  }

  loadFirst(): Promise<void> {
    return this.fetch({ dateFrom: EPOCH });
  }

  loadNext(): Promise<void> {
    return this.fetch({ dateFrom: this.window?.dateTo });
  }

  loadPrevious(): Promise<void> {
    return this.fetch({ dateTo: this.window?.before ?? this.window?.dateFrom });
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
      const source = this.source; // fixed for the whole export too: a source switch meanwhile must not mix two logs
      const instance = this.selectedInstance; // fixed for the whole export: every page must come from the same instance
      const mapping = this.selectedMapping || undefined; // the export has the same lines as the page
      let request: LogRequest = { instance, mapping, dateFrom: EPOCH };
      let window: LogWindow;
      do {
        window = await source.load(request);
        if (window.logs) {
          parts.push(window.logs.endsWith('\n') ? window.logs : `${window.logs}\n`);
        }
        // An empty window (nothing of the mapping in the stretch searched) still carries how far the search got.
        if (window.dateTo === request.dateFrom) break; // no progress: never loop on the same cursor
        request = { instance, mapping, dateFrom: window.dateTo };
      } while (window.more && window.dateTo);
      saveAs(new Blob(parts, { type: 'text/plain' }), `${instance || 'service-log'}${mapping ? `-${mapping}` : ''}.log`);
    } catch (e) {
      console.error(e);
      this.alertService.danger('Failed to download the log.');
    }
  }

  /** Makes `source` the active one and shows its latest lines. Returns false if it cannot serve this tenant. */
  private async activate(source: ServiceLogsSource): Promise<boolean> {
    try {
      await source.init();
      const window = await source.load({ mapping: this.selectedMapping || undefined });
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

  /** The mappings to choose from. Without them the page works as before, just without the filter. */
  private async loadMappings(): Promise<void> {
    try {
      const mappings = await this.mappingService.getMappings(undefined);
      this.mappings = mappings
        .filter((m) => MAPPING_IDENTIFIER.test(m.identifier ?? ''))
        .map((m) => ({
          identifier: m.identifier,
          direction: (m.direction === Direction.OUTBOUND ? 'OUTBOUND' : 'INBOUND') as MappingDirectionFilter,
          label: `${m.name} (${m.direction === Direction.OUTBOUND ? 'outbound' : 'inbound'})`
        }))
        .sort((a, b) => a.label.localeCompare(b.label));
    } catch (e) {
      console.error(e);
      this.mappings = [];
    }
    this.refreshMappingItems();
  }

  /** Applies `?mapping=<identifier>` of the URL, if it names a mapping of this tenant. */
  private preselectRequestedMapping(): void {
    const requested = this.route.snapshot.queryParamMap.get('mapping');
    if (!requested) {
      return;
    }
    if (MAPPING_IDENTIFIER.test(requested) && this.mappings.some((m) => m.identifier === requested)) {
      this.selectedMapping = requested;
      this.directionFilter = 'ALL';
    } else {
      this.alertService.info('The mapping from the link was not found. Showing all lines.');
    }
  }

  /** Replaces the items only when they really differ: the select rebuilds its state each time its input is set. */
  private refreshMappingItems(): void {
    const items = this.mappings
      .filter((m) => this.directionFilter === 'ALL' || m.direction === this.directionFilter)
      .map((m) => ({ label: m.label, value: m.identifier }));
    const same = items.length === this.mappingItems.length && items.every((i, n) => i.value === this.mappingItems[n].value && i.label === this.mappingItems[n].label);
    if (!same) {
      this.mappingItems = items;
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
      const window = await this.source.load({
        instance: this.selectedInstance,
        mapping: this.selectedMapping || undefined,
        ...params
      });
      if (ticket !== this.requestSeq) return; // a newer request was started meanwhile: this answer is out of date
      // With a mapping an empty window is an answer, not a failure: show it (and its cursors) so paging keeps working.
      if (!window.logs && !window.empty && !this.selectedMapping) {
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
