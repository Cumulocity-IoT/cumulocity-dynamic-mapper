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
  ViewEncapsulation
} from '@angular/core';
import { IIdentified } from '@c8y/client';
import { Column } from '@c8y/ngx-components';
import { DeviceGridService, GroupDeviceGridColumn, NameDeviceGridColumn, RegistrationDateDeviceGridColumn, SystemIdDeviceGridColumn } from '@c8y/ngx-components/device-grid';

@Component({
  selector: 'd11r-device-selector-subscription2',
  templateUrl: 'device-selector-subscription2.component.html',
  styleUrls: ['../../shared/mapping.style.css'],
  encapsulation: ViewEncapsulation.None,
  standalone: false
})
export class DeviceSelectorSubscription2Component implements OnInit {
  @Input() deviceList: IIdentified[];

  @Output() cancel = new EventEmitter<any>();
  @Output() commit = new EventEmitter<IIdentified[]>();
  columns: Column[];
  selectedItemIds: string[] = [];

  constructor(protected deviceGridService: DeviceGridService) {
  }
  ngOnInit(): void {
    this.columns = this.getColumns();
    console.log(this.deviceList);
  }

  clickedUpdateSubscription() {
    // contains:
    //   [
    //   {
    //       "id": "4827613",
    //       "name": "Pressure_d21",
    //       "type": "type_A"
    //   },
    //   {
    //       "id": "2129684",
    //       "name": "Pressure_d21",
    //       "type": "type_A"
    //   }
    //  ]
    const existingIds = new Set(this.deviceList.map(device => device.id));

    // Only add new IDs that don't already exist
    this.selectedItemIds?.forEach((id) => {
      if (!existingIds.has(id)) {
        this.deviceList.push({ id });
      }
    });
    this.commit.emit(this.deviceList);
  }

  clickedCancel() {
    this.cancel.emit();
  }

  onDeviceQueryStringChange(deviceQueryString: string): void {
    // the query string is based on currently selected filters and sorting in columns:

    // eslint-disable-next-line no-console
    // console.log({ deviceQueryString });
  }

  /** Executes an action on selected items, whenever the selection changes. */
  onItemsSelect(selectedItemIds: string[]) {
    this.selectedItemIds = selectedItemIds;
  }

  getColumns(): Column[] {
    const cols: Column[] = [
      new NameDeviceGridColumn(),
      new GroupDeviceGridColumn(),
      new RegistrationDateDeviceGridColumn(),
      new SystemIdDeviceGridColumn(),
      {
        header: 'Type',
        name: 'type',
        path: 'type',
        filterable: true,
        sortable: true,
      },
    ];
    return cols;
  }
}
