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
import { Component, EventEmitter, OnInit, ViewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  ActionControl,
  BulkActionControl,
  Column,
  ColumnDataType,
  CoreModule,
  DataGridComponent,
  DataSourceModifier,
  DisplayOptions,
  LoadMoreMode,
  Pagination,
  ServerSideDataResult
} from '@c8y/ngx-components';
import { TestingDeviceService } from '../testing-device.service';
import { DeviceIdCellRendererComponent } from './type-data-grid-column/device-id.cell-renderer.component';
import { ActivatedRoute } from '@angular/router';
import { Feature } from '../../shared';

import { provideGridPersistence } from '../../shared/misc/grid-persistence';
import { gettext } from '@c8y/ngx-components/gettext';

@Component({
  selector: 'd11r-mapping-testing-grid',
  templateUrl: 'testing.component.html',
  styleUrls: ['../../shared/mapping/mapping.style.css'],
  standalone: true,
  providers: [...provideGridPersistence('test-device')],
  imports: [CoreModule]
})
export class TestingComponent implements OnInit {
  constructor(private service: TestingDeviceService,
    private route: ActivatedRoute
  ) {
    // we're setting up `serverSideDataCallback` to execute a method from this component with bound `this`
    this.serverSideDataCallback = this.onDataSourceModifier.bind(this);
    // refreshData$ lives on a root service: without this, every grid ever created keeps reloading.
    this.service.refreshData$.pipe(takeUntilDestroyed()).subscribe(() => {
      this.deviceGrid?.reload();
    });
    this.columns = this.getColumns();
    this.pagination = this.service.getPagination();
  }
  /**
   * Returns a list of columns.
   * We define 2 columns with inline objects (they display simple properties
   * and use default header, cell and filtring form).
   * The last column is defined via a class instance (it displays a value based on
   * several properties of the row item and has custom header, cell and filtering form).
   */
  private getColumns(): Column[] {
    const columns = [
      {
        name: 'id',
        header: 'ID',
        path: 'id',
        filterable: true,
        sortable: true,
        dataType: ColumnDataType.Numeric,
        cellRendererComponent: DeviceIdCellRendererComponent
      },
      {
        name: 'name',
        header: gettext('Name'),
        path: 'name',
        filterable: true,
        sortable: true,
        dataType: ColumnDataType.TextShort
      },
      {
        name: 'creationTime',
        header: gettext('Date Created'),
        path: 'creationTime',
        filterable: false,
        sortable: true,
        dataType: ColumnDataType.TextLong
      },
      {
        name: 'type',
        header: gettext('Type'),
        path: 'type',
        filterable: true,
        sortable: true,
        dataType: ColumnDataType.TextShort
      },
    ];

    return columns;
  }

  async ngOnInit(): Promise<void> {
    this.feature = await this.route.snapshot.data['feature'];
    // Deleting a test device is an admin action, for a single row as well as in bulk.
    if (this.feature?.userHasMappingAdminRole) {
      this.actionControls = this.service.getActionControls();
      this.bulkActionControls = this.service.getBulkActionControls();
    }
  }
  @ViewChild(DataGridComponent, { static: false })
  deviceGrid: DataGridComponent;
  feature: Feature;

  loadMoreItemsLabel: string = 'Load more managed objects';
  loadingItemsLabel: string = 'Loading managed objects…';

  displayOptions: DisplayOptions = {
    bordered: false,
    striped: true,
    filter: true,
    gridHeader: true,
    hover: true
  };

  columns: Column[];
  pagination: Pagination;
  infiniteScroll: LoadMoreMode = 'auto';
  serverSideDataCallback: any;
  selectable: boolean = true;
  actionControls: ActionControl[] = [];
  bulkActionControls: BulkActionControl[];

  refresh: EventEmitter<any> = new EventEmitter<any>();


  /**
   * This method loads data when data grid requests it (e.g. on initial load or on column settings change).
   * It gets the object with current data grid setup and is supposed to return:
   * full response, list of items, paging object, the number of items in the filtered subset, the number of all items.
   */
  async onDataSourceModifier(
    dataSourceModifier: DataSourceModifier
  ): Promise<ServerSideDataResult> {
    const { res, data, paging } = await this.service.getData(
      dataSourceModifier.columns,
      dataSourceModifier.pagination
    );
    const filteredSize: number = await this.service.getCount(
      dataSourceModifier.columns,
      dataSourceModifier.pagination
    );
    const size: number = await this.service.getTotal();

    const serverSideDataResult: ServerSideDataResult = {
      res,
      data,
      paging,
      filteredSize,
      size
    };

    return serverSideDataResult;
  }
}
