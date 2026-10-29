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
import {
  Component,
  EventEmitter,
  Input,
  OnDestroy,
  OnInit,
  Output,
  ViewChild,
  ViewEncapsulation
} from '@angular/core';
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
const NOT_SYNCED_YET = 'Not synced yet';
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
  standalone: true,
  providers: [...provideGridPersistence('type-resync')],
  imports: [CoreModule]
})
export class TypeResyncComponent implements OnInit, OnDestroy {
  @ViewChild('typeResyncGrid') typeResyncGrid!: DataGridComponent;

  @Input() set typeList(list: string[]) {
    this.typeListInternal = list ?? [];
    this.rebuildRows();
  }

  @Output() cancel = new EventEmitter<any>();
  @Output() resync = new EventEmitter<string>();

  constructor(
    private readonly eventService: EventService,
    private readonly sharedService: SharedService
  ) {}

  typeListInternal: string[] = [];
  rows: DeviceTypeRow[] = [];

  // type -> state derived from its most recent backfill events
  private states: Record<string, ResyncState> = {};
  // types whose resync was just requested: type -> request time (ms) and polls so far
  private watched = new Map<string, { since: number; polls: number }>();
  private pollTimer?: ReturnType<typeof setTimeout>;
  private destroyed = false;

  readonly columns: Column[] = [
    {
      name: 'type',
      header: 'Device type',
      path: 'type',
      filterable: true,
      sortable: true,
      dataType: ColumnDataType.TextShort
    },
    {
      name: 'lastSyncTime',
      header: 'Last sync time',
      path: 'lastSyncTime',
      filterable: false,
      sortable: true,
      dataType: ColumnDataType.TextShort
    },
    {
      name: 'status',
      header: 'Status',
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
      text: 'Resync existing devices',
      icon: 'refresh',
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
      await this.loadSyncStates();
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
   * single fixed-size page by busier ones.
   */
  private async loadSyncStates(): Promise<void> {
    try {
      const agentId = await this.sharedService.getDynamicMappingServiceAgent();
      const types = new Set(this.typeListInternal);
      const latest: Record<string, ResyncState> = {};
      const resolved = new Set<string>();

      let page = await this.eventService.list({
        source: agentId,
        type: BACKFILL_EVENT_TYPE,
        // revert=true (newest first) is only honoured together with a date range
        dateFrom: new Date(0).toISOString(),
        dateTo: new Date(Date.now() + 60_000).toISOString(),
        revert: true,
        pageSize: EVENT_PAGE_SIZE,
        withTotalPages: false
      });

      for (let n = 0; n < MAX_EVENT_PAGES; n++) {
        for (const event of page.data as IEvent[]) {
          const fragment = event[BACKFILL_FRAGMENT];
          const type: string | undefined = fragment?.type;
          if (!type || !types.has(type) || resolved.has(type)) {
            continue;
          }
          const completed = fragment.subscribed !== undefined;
          const incomplete = fragment.subscribedBeforeFailure !== undefined;
          const state = latest[type];
          if (!state) {
            latest[type] = {
              time: event.time,
              outcome: completed ? 'FINISHED' : incomplete ? 'INCOMPLETE' : 'RUNNING',
              detail: completed
                ? `${fragment.subscribed} subscribed, ${fragment.failed} failed`
                : incomplete
                  ? `${fragment.subscribedBeforeFailure} subscribed before failure: ${fragment.error}`
                  : undefined,
              lastCompletedTime: completed ? event.time : undefined
            };
          } else if (completed && !state.lastCompletedTime) {
            state.lastCompletedTime = event.time;
          }
          if (latest[type].lastCompletedTime) {
            resolved.add(type);
          }
        }
        if (resolved.size >= types.size || !page.paging?.nextPage) {
          break;
        }
        page = await page.paging.next();
      }
      this.states = latest;
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
    switch (state.outcome) {
      case 'RUNNING':
        // A job lost to a service restart never reports a result; don't show "Running" forever
        return Date.now() - new Date(state.time).getTime() > STALE_RUNNING_MS
          ? 'No result reported (service restarted?)'
          : 'Running…';
      case 'INCOMPLETE':
        return `Incomplete (${state.detail})`;
      default:
        return `OK (${state.detail})`;
    }
  }

  private rebuildRows(): void {
    this.rows = this.typeListInternal.map(type => {
      const completed = this.states[type]?.lastCompletedTime;
      return {
        id: type,
        type,
        lastSyncTime: completed ? new Date(completed).toLocaleString() : NOT_SYNCED_YET,
        status: this.statusText(type)
      };
    });
  }
}
