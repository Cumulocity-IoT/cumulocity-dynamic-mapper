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
import { Component, EventEmitter, OnInit, ViewChild } from '@angular/core';
import {
  ActionControl,
  BulkActionControl,
  Column,
  CommonModule,
  CoreModule,
  DataGridComponent,
  DataSourceModifier,
  DisplayOptions,
  LoadMoreMode,
  Pagination,
  ServerSideDataResult
} from '@c8y/ngx-components';
import { TestingDeviceService } from '../testing.service';
import { ActivatedRoute } from '@angular/router';
import { Feature } from '../../shared';

@Component({
  selector: 'd11r-mapping-testing-grid',
  templateUrl: 'testing.component.html',
  styleUrls: ['../../mapping/shared/mapping.style.css'],
  standalone: true,
  imports: [CoreModule, CommonModule]
})
export class TestingComponent implements OnInit {
  constructor(private service: TestingDeviceService,
    private route: ActivatedRoute
  ) {
    // we're setting up `serverSideDataCallback` to execute a method from this component with bound `this`
    this.serverSideDataCallback = this.onDataSourceModifier.bind(this);
    // we're setting up `onRefreshClick` to be executed on refresh event
    this.refresh.subscribe(() => this.onRefreshClick());
    this.service.refreshData$.subscribe(() => {
      this.deviceGrid.reload();
    });
    this.columns = this.service.getColumns();
    this.pagination = this.service.getPagination();
    this.actionControls = this.service.getActionControls();
  }
  async ngOnInit(): Promise<void> {
    this.feature = await this.route.snapshot.data['feature'];
    if (this.feature?.userHasMappingAdminRole) {
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
  actionControls: ActionControl[];
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

  /** Executes an action on refresh event. */
  onRefreshClick() {
    // console.log('refresh clicked');
  }
}
