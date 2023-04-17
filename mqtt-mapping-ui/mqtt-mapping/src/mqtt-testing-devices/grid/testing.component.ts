/*
 * Copyright (c) 2022 Software AG, Darmstadt, Germany and/or Software AG USA Inc., Reston, VA, USA,
 * and/or its subsidiaries and/or its affiliates and/or their licensors.
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
import { Component, EventEmitter, ViewChild } from "@angular/core";
import {
  ActionControl,
  BulkActionControl,
  Column,
  DataGridComponent,
  DataSourceModifier,
  DisplayOptions,
  GridConfig,
  LoadMoreMode,
  Pagination,
  ServerSideDataResult,
} from "@c8y/ngx-components";
import { TestingDeviceService } from "./testing.service";

@Component({
  selector: "mapping-testing-grid",
  templateUrl: "testing.component.html",
  styleUrls: ["../../mqtt-mapping/shared/mapping.style.css"],
})
export class TestingComponent {
  @ViewChild(DataGridComponent, { static: false })
  deviceGrid: DataGridComponent;

  loadMoreItemsLabel: string = "Load more managed objects";
  loadingItemsLabel: string = "Loading managed objects…";

  displayOptions: DisplayOptions = {
    bordered: true,
    striped: true,
    filter: true,
    gridHeader: true,
  };

  columns: Column[] = this.service.getColumns();
  pagination: Pagination = this.service.getPagination();
  infiniteScroll: LoadMoreMode = "auto";
  serverSideDataCallback: any;

  refresh: EventEmitter<any> = new EventEmitter<any>();

  selectable: boolean = true;
  actionControls: ActionControl[] = this.service.getActionControls();
  bulkActionControls: BulkActionControl[] =
    this.service.getBulkActionControls();
  constructor(private service: TestingDeviceService) {
    // we're setting up `serverSideDataCallback` to execute a method from this component with bound `this`
    this.serverSideDataCallback = this.onDataSourceModifier.bind(this);
    // we're setting up `onRefreshClick` to be executed on refresh event
    this.refresh.subscribe(() => this.onRefreshClick());
    this.service.refreshData$.subscribe((v) => {
      this.deviceGrid.reload();
    });
  }

  /**
   * This method loads data when data grid requests it (e.g. on initial load or on column settings change).
   * It gets the object with current data grid setup and is supposed to return:
   * full response, list of items, paging object, the number of items in the filtered subset, the number of all items.
   */
  async onDataSourceModifier(
    dataSourceModifier: DataSourceModifier
  ): Promise<ServerSideDataResult> {
    let serverSideDataResult: ServerSideDataResult;

    const { res, data, paging } = await this.service.getData(
      dataSourceModifier.columns,
      dataSourceModifier.pagination
    );
    const filteredSize: number = await this.service.getCount(
      dataSourceModifier.columns,
      dataSourceModifier.pagination
    );
    const size: number = await this.service.getTotal();

    serverSideDataResult = { res, data, paging, filteredSize, size };

    return serverSideDataResult;
  }

  onColumnsChange(columns: Column[]): void {
    // the columns list contains the current setup of the columns in the grid:

    // eslint-disable-next-line no-console
    console.log({ columns });
  }

  onDeviceQueryStringChange(deviceQueryString: string): void {
    // the query string is based on currently selected filters and sorting in columns:

    // eslint-disable-next-line no-console
    console.log({ deviceQueryString });
  }

  /** Executes an action on grid config change. */
  onConfigChange(gridConfig: GridConfig) {
    console.log("grid config changed:");
    console.dir(gridConfig);
  }

  /** Executes an action on refresh event. */
  onRefreshClick() {
    console.log("refresh clicked");
  }
}
