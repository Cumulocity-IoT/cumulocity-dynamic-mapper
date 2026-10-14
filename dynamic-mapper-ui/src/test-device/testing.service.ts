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
import { Injectable } from '@angular/core';
import { transform } from 'lodash-es';

import {
  IManagedObject,
  InventoryService,
  QueriesUtil
} from '@c8y/client';
import {
  ActionControl,
  AlertService,
  BuiltInActionType,
  BulkActionControl,
  Column,
  ColumnDataType,
  Pagination
} from '@c8y/ngx-components';

import { Subject } from 'rxjs';
import { DeviceIdCellRendererComponent } from './grid/type-data-grid-column/device-id.cell-renderer.component';
import { MAPPING_TEST_DEVICE_TYPE, SharedService } from '../shared';

/** Model for custom type filtering form. */

/**
 * Data source of the Test device page: provides the columns, the pagination and the delete actions, and queries the
 * inventory for devices carrying the `d11r_testDevice` fragment (the ones created by a Live Test).
 */
@Injectable({ providedIn: 'root' })
export class TestingDeviceService {
  /** This will be used to build the inventory queries. */
  protected queriesUtil: QueriesUtil;

  constructor(
    protected inventoryService: InventoryService,
    protected sharedService: SharedService,
    public alert: AlertService
  ) {
    this.queriesUtil = new QueriesUtil();
  }

  refreshData$ = new Subject<any>();

  /**
   * Returns a list of columns.
   * We define 2 columns with inline objects (they display simple properties
   * and use default header, cell and filtring form).
   * The last column is defined via a class instance (it displays a value based on
   * several properties of the row item and has custom header, cell and filtering form).
   */
  getColumns(): Column[] {
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
        header: 'Name',
        path: 'name',
        filterable: true,
        sortable: true,
        dataType: ColumnDataType.TextShort
      },
      {
        name: 'creationTime',
        header: 'Date Created',
        path: 'creationTime',
        filterable: false,
        sortable: true,
        dataType: ColumnDataType.TextLong
      },
      {
        name: 'type',
        header: 'Type',
        path: 'type',
        filterable: true,
        sortable: true,
        dataType: ColumnDataType.TextShort
      },
    ];

    return columns;
  }

  /** Returns initial pagination object. */
  getPagination(): Pagination {
    return {
      pageSize: 10,
      currentPage: 1
    };
  }

  /** Returns an array of individual row actions. */
  getActionControls(): ActionControl[] {
    return [
      {
        type: BuiltInActionType.Delete,
        callback: (item) => this.onItemDelete(item)
      }
    ];
  }

  /** Returns an array of bulk row actions. */
  getBulkActionControls(): BulkActionControl[] {
    return [
      {
        type: BuiltInActionType.Delete,
        callback: (selectedItemIds) => this.onItemsDelete(selectedItemIds)
      }
    ];
  }

  /** Returns data for current columns and pagination setup. */
  async getData(columns: Column[], pagination: Pagination) {
    // build filters based on columns and pagination
    const filters = this.getFilters(columns, pagination);
    // execute inventory query for the list of managed objects
    return this.inventoryService.list(filters);
  }

  /** Returns the number of items matching current columns and pagination setup. */
  async getCount(columns: Column[], pagination: Pagination) {
    const filters = {
      // build filters based on columns and pagination
      ...this.getFilters(columns, pagination),
      // but we only need the number of items, not the items themselves
      pageSize: 1,
      currentPage: 1
    };
    return (await this.inventoryService.list(filters)).paging.totalPages;
  }

  /**
   * Returns the total number of test devices — the fixed `d11r_testDevice` filter only, without the column
   * filters. (It used to count every managed object of the tenant, so the header read e.g. "3 of 5977".)
   */
  async getTotal(): Promise<number> {
    const filters = {
      ...this.getFilters([], this.getPagination()),
      pageSize: 1,
      currentPage: 1
    };
    return (await this.inventoryService.list(filters)).paging.totalPages;
  }

  /** Returns filters for given columns and pagination setup. */
  private getFilters(columns: Column[], pagination: Pagination) {
    const query1: any = this.getQueryString(columns);
    const query2 = this.queriesUtil.addAndFilter(query1, {
      __has: MAPPING_TEST_DEVICE_TYPE
    });
    const queryBuilt = this.queriesUtil.buildQuery(query2);
    return {
      query: queryBuilt,
      pageSize: pagination.pageSize,
      currentPage: pagination.currentPage,
      withChildren: false,
      withTotalPages: true
    };
  }

  /** Returns a query string based on columns setup. */
  private getQueryString(columns: Column[]): string {
    // const fullQuery = this.getQueryObj(columns);
    // return this.queriesUtil.buildQuery(fullQuery);
    return this.getQueryObj(columns);
  }

  /** Returns a query object based on columns setup. */
  private getQueryObj(columns: Column[]): any {
    return transform(
      columns,
      (query, column) => this.addColumnQuery(query, column),
      {
        __filter: {},
        __orderby: []
      }
    );
  }

  /** Extends given query with a part based on the setup of given column. */
  private addColumnQuery(query: any, column: Column): void {
    let queryx = query;
    // when a column is marked as filterable
    if (column.filterable) {
      // in the case of default filtering form, `filterPredicate` will contain the string entered by a user
      if (column.filterPredicate) {
        // so we use it as the expected value, * allow to search for it anywhere in the property
        queryx.__filter[column.path] = `*${column.filterPredicate}*`;
      }

      // in the case of custom filtering form, we're storing the queryx in `externalFilterQuery.queryx`
      if (column.externalFilterQuery) {
        queryx = this.queriesUtil.addAndFilter(
          queryx,
          column.externalFilterQuery.query
        );
      }
    }

    // when a column is sortable and has a specified sorting order
    if (column.sortable && column.sortOrder) {
      // add sorting condition for the configured column `path`
      queryx.__orderby.push({
        [column.path]: column.sortOrder === 'asc' ? 1 : -1
      });
    }

    return queryx;
  }

  async onItemDelete(m: Partial<IManagedObject>): Promise<void> {
    await this.deleteTestDevices([m.id as string]);
  }

  async onItemsDelete(ms: string[]): Promise<void> {
    await this.deleteTestDevices(ms);
  }

  /**
   * Deletes the devices one by one so a single failure does not hide the others, reports what actually
   * happened (the success alert used to appear before any delete had finished), and always refreshes the grid.
   */
  private async deleteTestDevices(ids: string[]): Promise<void> {
    const params: any = {
      cascade: true
    };
    const results = await Promise.allSettled(ids.map((id) => this.inventoryService.delete(id, params)));
    const failed = results.filter((r) => r.status === 'rejected');
    this.refreshData$.next(true);
    if (failed.length === 0) {
      this.alert.success(ids.length === 1 ? 'Test device deleted.' : `${ids.length} test devices deleted.`);
    } else {
      const reason = (failed[0] as PromiseRejectedResult).reason;
      this.alert.danger(
        `${failed.length} of ${ids.length} test devices could not be deleted`
          + (reason?.message ? `: ${reason.message}` : '.')
      );
    }
  }
}
