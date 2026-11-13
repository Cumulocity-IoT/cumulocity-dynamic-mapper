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
import {
  ChangeDetectionStrategy,
  Component,
  EventEmitter,
  OnInit,
  Output,
  ViewEncapsulation, input } from '@angular/core';
import { IIdentified } from '@c8y/client';
import { Column, ColumnDataType, CoreModule } from '@c8y/ngx-components';
import { gettext } from '@c8y/ngx-components/gettext';
import { AssetSelectorModule } from '@c8y/ngx-components/assets-navigator';
import { DeviceGridModule, DeviceGridService, GroupDeviceGridColumn, RegistrationDateDeviceGridColumn, SystemIdDeviceGridColumn } from '@c8y/ngx-components/device-grid';

@Component({
  selector: 'd11r-device-selector-table',
  host: { class: 'flex-grow d-col fit-h' },
  templateUrl: 'device-selector-table.component.html',
  styleUrls: ['../../../shared/mapping/mapping.style.css'],
  encapsulation: ViewEncapsulation.None,
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CoreModule, AssetSelectorModule, DeviceGridModule],
  providers: [
    DeviceGridService]
})
export class DeviceSelectorTableComponent implements OnInit {
  readonly deviceList = input<IIdentified[]>();

  /** Embedded in a page instead of a drawer: no header or buttons; every selection change is emitted as `selectionChange`. */
  readonly inline = input(false);

  @Output() cancel = new EventEmitter<void>();
  @Output() commit = new EventEmitter<IIdentified[]>();
  @Output() selectionChange = new EventEmitter<IIdentified[]>();
  columns: Column[];
  selectedItemIds: string[] = [];

  constructor(protected deviceGridService: DeviceGridService) {
  }
  ngOnInit(): void {
    this.columns = this.getColumns();
  }

  /**
   * This can only ADD devices, never remove one: c8y-device-grid has no way to pre-select rows
   * for already-subscribed devices, so there is no checkbox state that represents "currently
   * subscribed" to uncheck. Use the tree selector (which two-way binds the full device list) to
   * unsubscribe a device.
   */
  clickedUpdateSubscription() {
    const existingIds = new Set(this.deviceList().map(device => device.id));

    // Only add new IDs that don't already exist. The host's list is not changed: it receives
    // the combined list and decides what to keep.
    const added = (this.selectedItemIds ?? [])
      .filter(id => !existingIds.has(id))
      .map(id => ({ id }));
    this.commit.emit([...this.deviceList(), ...added]);
  }

  clickedCancel() {
    this.cancel.emit();
  }

  /** Executes an action on selected items, whenever the selection changes. */
  onItemsSelect(selectedItemIds: string[]) {
    const previous = new Set(this.selectedItemIds);
    this.selectedItemIds = selectedItemIds;
    if (this.inline()) {
      this.emitInline(previous);
    }
  }

  /**
   * Inline mode: the host's list is also edited elsewhere (the device tree), so only what this
   * table itself added is toggled. A row ticked here is added; a row unticked here is removed
   * again; devices that were subscribed before are never touched.
   */
  private emitInline(previous: Set<string>) {
    const selected = new Set(this.selectedItemIds ?? []);
    const base = (this.deviceList() ?? []).filter(d => !(previous.has(String(d.id)) && !selected.has(String(d.id))));
    const existingIds = new Set(base.map(d => String(d.id)));
    const added = [...selected].filter(id => !existingIds.has(id)).map(id => ({ id }));
    this.selectionChange.emit([...base, ...added]);
  }

  getColumns(): Column[] {
    const cols: Column[] = [
      // Plain text instead of NameDeviceGridColumn, whose renderer turns the name into a link to the device page
      {
        header: gettext('Name'),
        name: 'name',
        path: 'name',
        filterable: true,
        sortable: true,
        dataType: ColumnDataType.TextLong
      },
      new GroupDeviceGridColumn(),
      new RegistrationDateDeviceGridColumn(),
      new SystemIdDeviceGridColumn(),
      {
        header: gettext('Type'),
        name: 'type',
        path: 'type',
        filterable: true,
        sortable: true,
        dataType: ColumnDataType.TextLong
      },
    ];
    return cols;
  }
}
