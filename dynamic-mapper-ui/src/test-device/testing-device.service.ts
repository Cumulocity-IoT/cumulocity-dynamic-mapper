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
import { inject, Injectable } from '@angular/core';
import { transform } from 'lodash-es';

import {
  IManagedObject,
  InventoryService,
  QueriesUtil,
  QueryObjectWithDedicatedFilter
} from '@c8y/client';
import {
  ActionControl,
  AlertService,
  BuiltInActionType,
  BulkActionControl,
  Column,
  Pagination
} from '@c8y/ngx-components';

import { Subject } from 'rxjs';
import { MAPPING_TEST_DEVICE_TYPE, SharedService } from '../shared';
import { gettext } from '@c8y/ngx-components/gettext';
import { TranslateService } from '@ngx-translate/core';

/** Model for custom type filtering form. */

/**
 * Data source of the Test device page: provides the columns, the pagination and the delete actions, and queries the
 * inventory for devices carrying the `d11r_testDevice` fragment (the ones created by a Live Test).
 */
/** The query object the c8y QueriesUtil builds a query string from. */
interface ColumnQuery {
  __filter: Record<string, unknown>;
  __orderby: Record<string, number>[];
}

@Injectable({ providedIn: 'root' })
export class TestingDeviceService {
  /** This will be used to build the inventory queries. */
  protected queriesUtil: QueriesUtil;
  private readonly translateService = inject(TranslateService);

  constructor(
    protected inventoryService: InventoryService,
    protected sharedService: SharedService,
    public alert: AlertService
  ) {
    this.queriesUtil = new QueriesUtil();
  }

  private readonly refreshData = new Subject<boolean>();
  /** Emits after test devices were deleted, so the grid reloads. */
  readonly refreshData$ = this.refreshData.asObservable();

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
    const query1 = this.getQueryObj(columns);
    const query2 = this.queriesUtil.addAndFilter(query1 as QueryObjectWithDedicatedFilter, {
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

  /** Returns a query object based on columns setup. */
  private getQueryObj(columns: Column[]): ColumnQuery {
    return transform<Column, ColumnQuery>(
      columns,
      (query, column) => this.addColumnQuery(query, column),
      {
        __filter: {},
        __orderby: []
      }
    );
  }

  /** Extends given query with a part based on the setup of given column. */
  private addColumnQuery(query: ColumnQuery, column: Column): void {
    let queryx: ColumnQuery | ReturnType<QueriesUtil['addAndFilter']> = query;
    // when a column is marked as filterable
    if (column.filterable) {
      // in the case of default filtering form, `filterPredicate` will contain the string entered by a user
      if (column.filterPredicate) {
        // so we use it as the expected value, * allow to search for it anywhere in the property
        (queryx as ColumnQuery).__filter[column.path] = `*${column.filterPredicate}*`;
      }

      // in the case of custom filtering form, we're storing the queryx in `externalFilterQuery.queryx`
      if (column.externalFilterQuery) {
        queryx = this.queriesUtil.addAndFilter(
          queryx as QueryObjectWithDedicatedFilter,
          column.externalFilterQuery.query
        );
      }
    }

    // when a column is sortable and has a specified sorting order
    if (column.sortable && column.sortOrder) {
      // add sorting condition for the configured column `path`
      (queryx as ColumnQuery).__orderby.push({
        [column.path]: column.sortOrder === 'asc' ? 1 : -1
      });
    }

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
    const params = {
      cascade: true
    };
    const results = await Promise.allSettled(ids.map((id) => this.inventoryService.delete(id, params)));
    const failed = results.filter((r) => r.status === 'rejected');
    this.refreshData.next(true);
    if (failed.length === 0) {
      this.alert.success(
        ids.length === 1
          ? gettext('Test device deleted.')
          : this.translateService.instant(gettext('{{count}} test devices deleted.'), { count: ids.length })
      );
    } else {
      const reason = (failed[0] as PromiseRejectedResult).reason;
      this.alert.danger(
        this.translateService.instant(gettext('{{failed}} of {{total}} test devices could not be deleted'), {
          failed: failed.length,
          total: ids.length
        }) + (reason?.message ? `: ${reason.message}` : '.')
      );
    }
  }
}
