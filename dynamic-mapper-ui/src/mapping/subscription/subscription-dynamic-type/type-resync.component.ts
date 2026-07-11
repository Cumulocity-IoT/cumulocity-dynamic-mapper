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
}

/** Backend component fragment name for BACKFILL_SUBSCRIPTION_EVENT_TYPE events (see LoggingEventType.java). */
const BACKFILL_EVENT_TYPE = 'd11r_backfillSubscriptionEvent';
const BACKFILL_FRAGMENT = 'd11r_subscription';
const NOT_SYNCED_YET = 'Not synced yet';

@Component({
  selector: 'd11r-type-resync',
  host: { class: 'flex-grow d-col fit-h' },
  templateUrl: 'type-resync.component.html',
  styleUrls: ['../../shared/mapping.style.css'],
  encapsulation: ViewEncapsulation.None,
  standalone: true,
  imports: [CoreModule]
})
export class TypeResyncComponent implements OnInit {
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

  // type -> ISO timestamp of its most recent finished resync
  private lastSyncTimes: Record<string, string> = {};

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
      callback: (item: DeviceTypeRow) => this.resync.emit(item.type)
    }
  ];

  async ngOnInit(): Promise<void> {
    await this.loadLastSyncTimes();
  }

  clickedCancel() {
    this.cancel.emit();
  }

  /**
   * Loads the most recent "finished" BACKFILL_SUBSCRIPTION_EVENT_TYPE event per device type from
   * Service Events, in a single call, and uses it to populate the "Last sync time" column.
   */
  private async loadLastSyncTimes(): Promise<void> {
    try {
      const agentId = await this.sharedService.getDynamicMappingServiceAgent();
      const { data: events } = await this.eventService.list({
        source: agentId,
        type: BACKFILL_EVENT_TYPE,
        pageSize: 200,
        withTotalPages: false
      });

      // Don't assume a particular sort order from the API — keep the max timestamp seen per type.
      const latest: Record<string, string> = {};
      for (const event of events as IEvent[]) {
        const fragment = event[BACKFILL_FRAGMENT];
        // Only the "finished" event carries a `subscribed` count; the "started" event doesn't.
        const type = fragment?.type;
        if (!type || fragment?.subscribed === undefined) {
          continue;
        }
        if (!latest[type] || new Date(event.time).getTime() > new Date(latest[type]).getTime()) {
          latest[type] = event.time;
        }
      }
      this.lastSyncTimes = latest;
    } catch (error) {
      console.error('Failed to load last sync times for type subscriptions:', error);
      this.lastSyncTimes = {};
    }
    this.rebuildRows();
  }

  private rebuildRows(): void {
    this.rows = this.typeListInternal.map(type => ({
      id: type,
      type,
      lastSyncTime: this.lastSyncTimes[type]
        ? new Date(this.lastSyncTimes[type]).toLocaleString()
        : NOT_SYNCED_YET
    }));
  }
}
