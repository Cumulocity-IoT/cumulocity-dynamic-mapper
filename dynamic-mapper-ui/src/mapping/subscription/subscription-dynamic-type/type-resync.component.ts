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
import { Component, EventEmitter, OnDestroy, OnInit, Output, ViewChild, ViewEncapsulation, ChangeDetectionStrategy, signal, viewChild, effect, input, untracked, inject } from '@angular/core';
import {
  ActionControl,
  Column,
  ColumnDataType,
  CoreModule,
  DataGridComponent,
  DisplayOptions,
  Pagination
} from '@c8y/ngx-components';
import { EventService, IEvent } from '@c8y/client';
import { TranslateService } from '@ngx-translate/core';
import { gettext } from '@c8y/ngx-components/gettext';
import { SharedService } from '../../../shared';

interface DeviceTypeRow {
  id: string;
  type: string;
  lastSyncTime: string;
  status: string;
}

type ResyncOutcome = 'RUNNING' | 'FINISHED' | 'INCOMPLETE';

/** The latest backfill event seen for a type, and what it says about the resync. */
interface ResyncState {
  outcome: ResyncOutcome;
  time: string;
  /** Time of the latest fully completed resync, if any (may be older than `time`). */
  lastCompletedTime?: string;
  detail?: string;
}

/** Backend component fragment name for BACKFILL_SUBSCRIPTION_EVENT_TYPE events (see LoggingEventType.java). */
const BACKFILL_EVENT_TYPE = 'd11r_backfillSubscriptionEvent';
const BACKFILL_FRAGMENT = 'd11r_subscription';
/** Events fetched per page, newest first; paging stops as soon as every listed type is resolved. */
const EVENT_PAGE_SIZE = 500;
const MAX_EVENT_PAGES = 10;
const POLL_INTERVAL_MS = 3000;
/** A "started" event without a result older than this is considered lost. */
const STALE_RUNNING_MS = 6 * 60 * 60 * 1000;
/** Polls without seeing any event after a click before giving up (request rejected or cancelled). */
const MAX_POLLS_WITHOUT_EVENT = 10;

import { provideGridPersistence } from '../../../shared/misc/grid-persistence';

@Component({
  selector: 'd11r-type-resync',
  host: { class: 'flex-grow d-col fit-h' },
  templateUrl: 'type-resync.component.html',
  styleUrls: ['../../../shared/mapping/mapping.style.css'],
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  providers: [...provideGridPersistence('type-resync')],
  imports: [CoreModule]
})
export class TypeResyncComponent implements OnInit, OnDestroy {
  readonly typeResyncGrid = viewChild<DataGridComponent>('typeResyncGrid');

  readonly typeList = input<string[]>([]);

  @Output() cancel = new EventEmitter<void>();
  @Output() resync = new EventEmitter<string>();

  private readonly translateService = inject(TranslateService);

  constructor(
    private readonly eventService: EventService,
    private readonly sharedService: SharedService
  ) {
    effect(() => {
      const list = this.typeList();
      untracked(() => {
        this.typeListInternal = list ?? [];
        this.rebuildRows();
      });
    });
  }

  typeListInternal: string[] = [];
  private readonly rowsState = signal<DeviceTypeRow[]>([]);
  get rows(): DeviceTypeRow[] { return this.rowsState(); }
  set rows(value: DeviceTypeRow[]) { this.rowsState.set(value); }

  // type -> state derived from its most recent backfill events
  private states: Record<string, ResyncState> = {};
  // types whose resync was just requested: type -> request time (ms) and polls so far
  private watched = new Map<string, { since: number; polls: number }>();
  private pollTimer?: ReturnType<typeof setTimeout>;
  private destroyed = false;

  readonly columns: Column[] = [
    {
      name: 'type',
      header: gettext('Device type'),
      path: 'type',
      filterable: true,
      sortable: true,
      dataType: ColumnDataType.TextShort
    },
    {
      name: 'lastSyncTime',
      header: gettext('Last sync time'),
      path: 'lastSyncTime',
      filterable: false,
      sortable: true,
      dataType: ColumnDataType.TextShort
    },
    {
      name: 'status',
      header: gettext('Status'),
      path: 'status',
      filterable: false,
      sortable: true,
      dataType: ColumnDataType.TextShort
    }
  ];

  readonly pagination: Pagination = {
    pageSize: 30,
    currentPage: 1
  };

  readonly displayOptions: DisplayOptions = {
    bordered: true,
    striped: true,
    filter: false,
    gridHeader: true,
    hover: true
  };

  readonly actionControls: ActionControl[] = [
    {
      type: 'RESYNC',
      text: gettext('Resync existing devices'),
      icon: 'refresh',
      // Hidden while a run is in progress: a second request is rejected as a conflict anyway
      showIf: (item: DeviceTypeRow) => !this.isRunning(item.type),
      callback: (item: DeviceTypeRow) => this.requestResync(item.type)
    }
  ];

  async ngOnInit(): Promise<void> {
    await this.loadSyncStates();
  }

  ngOnDestroy(): void {
    this.destroyed = true;
    clearTimeout(this.pollTimer);
  }

  clickedCancel() {
    this.cancel.emit();
  }

  /** A run that started and reported no result yet. A very old one is a job lost to a restart. */
  private isRunning(type: string): boolean {
    const state = this.states[type];
    return !!state && state.outcome === 'RUNNING' && Date.now() - new Date(state.time).getTime() <= STALE_RUNNING_MS;
  }

  private requestResync(type: string): void {
    this.watched.set(type, { since: Date.now(), polls: 0 });
    this.resync.emit(type);
    this.schedulePoll();
  }

  /**
   * Refreshes the grid while a just-requested resync is in flight: until its "finished"/"incomplete"
   * event shows up, or — if no event for it ever appears (request rejected, confirmation declined,
   * resync already running) — for a short while only.
   */
  private schedulePoll(): void {
    clearTimeout(this.pollTimer);
    if (this.destroyed || this.watched.size === 0) {
      return;
    }
    this.pollTimer = setTimeout(async () => {
      await this.loadSyncStates(true);
      for (const [type, w] of [...this.watched]) {
        const state = this.states[type];
        const sinceMs = w.since - 2000; // tolerate clock skew between browser and backend
        const seenAfterClick = !!state && new Date(state.time).getTime() >= sinceMs;
        if (seenAfterClick && state.outcome !== 'RUNNING') {
          this.watched.delete(type); // done
        } else if (!seenAfterClick && ++w.polls >= MAX_POLLS_WITHOUT_EVENT) {
          this.watched.delete(type); // nothing started
        }
      }
      this.schedulePoll();
    }, POLL_INTERVAL_MS);
  }

  /**
   * Derives per-type resync state from BACKFILL_SUBSCRIPTION_EVENT_TYPE Service Events. Events are
   * read newest first, page by page, until every listed type has both its latest event and its latest
   * completed run (or the page budget is used up) — so a rarely resynced type isn't pushed out of a
   * single fixed-size page by busier ones. A `quick` load (the poll after a request, every few seconds)
   * reads the newest page only and merges it into what is known: it only has to see new events.
   */
  private async loadSyncStates(quick = false): Promise<void> {
    try {
      const agentId = await this.sharedService.getDynamicMappingServiceAgent();
      const types = new Set(this.typeListInternal);
      const latest: Record<string, ResyncState> = {};
      const resolved = new Set<string>();
      // Per type: the newest "started" event, the newest result event, the newest completed run.
      // Compared by time, never by list position or id, so the outcome doesn't depend on how the
      // API orders events (ids are not monotonic; same-millisecond events sort arbitrarily).
      interface Seen {
        startTime?: string;
        endTime?: string;
        endOutcome?: ResyncOutcome;
        endDetail?: string;
        completedTime?: string;
      }
      const seen: Record<string, Seen> = {};
      const ms = (t?: string) => (t ? new Date(t).getTime() : Number.NEGATIVE_INFINITY);

      // Default order is newest first. Do not pass revert=true: with a date range it means OLDEST first.
      let page = await this.eventService.list({
        source: agentId,
        type: BACKFILL_EVENT_TYPE,
        pageSize: EVENT_PAGE_SIZE,
        withTotalPages: false
      });

      for (let n = 0; n < MAX_EVENT_PAGES; n++) {
        for (const event of page.data as IEvent[]) {
          const fragment = event[BACKFILL_FRAGMENT];
          const type: string | undefined = fragment?.type;
          if (!type || !types.has(type)) {
            continue;
          }
          const completed = fragment.subscribed !== undefined;
          const incomplete = fragment.subscribedBeforeFailure !== undefined;
          const s = (seen[type] ??= {});
          if (!completed && !incomplete) {
            if (ms(event.time) > ms(s.startTime)) {
              s.startTime = event.time;
            }
            continue;
          }
          if (ms(event.time) > ms(s.endTime)) {
            s.endTime = event.time;
            s.endOutcome = completed ? 'FINISHED' : 'INCOMPLETE';
            s.endDetail = completed
              ? this.translateService.instant(gettext('{{subscribed}} subscribed, {{failed}} failed'),
                  { subscribed: fragment.subscribed, failed: fragment.failed })
              : this.translateService.instant(gettext('{{count}} subscribed before failure: {{error}}'),
                  { count: fragment.subscribedBeforeFailure, error: fragment.error });
          }
          if (completed && ms(event.time) > ms(s.completedTime)) {
            s.completedTime = event.time;
          }
        }
        // A type is settled once its latest completed run and a start event are both known
        for (const [type, s] of Object.entries(seen)) {
          if (s.completedTime && s.startTime) {
            resolved.add(type);
          }
        }
        if (quick || resolved.size >= types.size || !page.paging?.nextPage) {
          break;
        }
        page = await page.paging.next();
      }
      for (const [type, s] of Object.entries(seen)) {
        // A result at or after the newest start ends that run; only a newer start means "running"
        const running = !!s.startTime && ms(s.startTime) > ms(s.endTime);
        latest[type] = running
          ? { time: s.startTime!, outcome: 'RUNNING', lastCompletedTime: s.completedTime }
          : { time: s.endTime!, outcome: s.endOutcome!, detail: s.endDetail, lastCompletedTime: s.completedTime };
      }
      if (quick) {
        // keep what the first page does not show: the last completed run of each type
        const merged = { ...this.states };
        for (const [type, state] of Object.entries(latest)) {
          state.lastCompletedTime ??= this.states[type]?.lastCompletedTime;
          merged[type] = state;
        }
        this.states = merged;
      } else {
        this.states = latest;
      }
    } catch (error) {
      console.error('Failed to load resync status for type subscriptions:', error);
      this.states = {};
    }
    this.rebuildRows();
  }

  private statusText(type: string): string {
    const state = this.states[type];
    if (!state) {
      return '';
    }
    const t = (key: string, params?: Record<string, unknown>) => this.translateService.instant(key, params);
    switch (state.outcome) {
      case 'RUNNING':
        // A job lost to a service restart never reports a result; don't show "Running" forever
        return Date.now() - new Date(state.time).getTime() > STALE_RUNNING_MS
          ? t(gettext('No result reported (service restarted?)'))
          : t(gettext('Running…'));
      case 'INCOMPLETE':
        return t(gettext('Incomplete ({{detail}})'), { detail: state.detail });
      default:
        return t(gettext('OK ({{detail}})'), { detail: state.detail });
    }
  }

  private rebuildRows(): void {
    this.rows = this.typeListInternal.map(type => {
      const completed = this.states[type]?.lastCompletedTime;
      return {
        id: type,
        type,
        lastSyncTime: completed
          ? new Date(completed).toLocaleString()
          : this.translateService.instant(gettext('Not synced yet')),
        status: this.statusText(type)
      };
    });
  }
}
