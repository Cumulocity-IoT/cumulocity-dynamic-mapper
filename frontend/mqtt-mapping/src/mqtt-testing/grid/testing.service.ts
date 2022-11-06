import { Injectable, OnInit } from '@angular/core';
import { transform } from 'lodash-es';

import { IManagedObject, InventoryService, QueriesUtil } from '@c8y/client';
import {
    ActionControl,
    AlertService,
    BuiltInActionType,
    BulkActionControl,
    Column,
    Pagination
} from '@c8y/ngx-components';

import { TypeDataGridColumn } from './type-data-grid-column/type.data-grid-column';
import { Subject } from 'rxjs';
import { DeviceIdCellRendererComponent } from './type-data-grid-column/device-id.cell-renderer.component';

/** Model for custom type filtering form. */
export interface TypeFilteringModel {
    group?: boolean;
    device?: boolean;
}

/**
 * This is the example service for a data grid:
 * provides the list of columns, initial pagination object, actions;
 * as well as performs the query for data based on the current grid setup.
 */
@Injectable({ providedIn: 'root' })
export class TestingDeviceService {
    /** This will be used to build the inventory queries. */
    protected queriesUtil: QueriesUtil;

    constructor(protected inventoryService: InventoryService,
        public alert: AlertService) {
        this.queriesUtil = new QueriesUtil();
    }

    public refreshData$ = new Subject<any>();

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
                cellRendererComponent: DeviceIdCellRendererComponent
            },
            {
                name: 'name',
                header: 'Name',
                path: 'name',
                filterable: true,
                sortable: true
            },
            {
                name: 'creationTime',
                header: 'Date Created',
                path: 'creationTime',
                filterable: true,
                sortable: true
            },
            new TypeDataGridColumn()
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
           // { type: BuiltInActionType.Delete, callback: (item) => console.dir(item) },
            { type: BuiltInActionType.Delete, callback: (item) => this.onItemDelete(item) },
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

    /** Returns the total number of items (with no filters). */
    async getTotal(): Promise<number> {
        const filters = {
            pageSize: 1,
            withTotalPages: true
        };
        return (await this.inventoryService.list(filters)).paging.totalPages;
    }

    /** Returns an icon and label representing the type of the managed object. */
    getTypeIconAndLabel(mo: IManagedObject): { icon: string; label: string } {
        let icon: string = 'question';
        let label: string = 'Other';

        if (mo.type === 'c8y_DeviceGroup') {
            icon = 'c8y-group';
            label = 'Group';
        }

        if (mo.c8y_IsDevice !== undefined) {
            icon = 'exchange';
            label = 'Device';
        }

        return { icon, label };
    }

    /** Returns a query object for given settings of filtering by type. */
    getTypeQuery(model: TypeFilteringModel): any {
        let query: any = {};

        if (model.group) {
            query = this.queriesUtil.addOrFilter(query, { type: 'c8y_DeviceGroup' });
        }

        if (model.device) {
            query = this.queriesUtil.addOrFilter(query, { __has: 'c8y_IsDevice' });
        }

        return query;
    }

    /** Returns filters for given columns and pagination setup. */
    private getFilters(columns: Column[], pagination: Pagination) {
        let query1: any = this.getQueryString(columns);
        let query2 = this.queriesUtil.addAndFilter(query1, { __has: 'c8y_mqttMapping_TestDevice' });
        let queryBuilt = this.queriesUtil.buildQuery(query2);
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
        const fullQuery = this.getQueryObj(columns);
        return this.queriesUtil.buildQuery(fullQuery);
    }

    /** Returns a query object based on columns setup. */
    private getQueryObj(columns: Column[]): any {
        return transform(columns, (query, column) => this.addColumnQuery(query, column), {
            __filter: {},
            __orderby: []
        });
    }

    /** Extends given query with a part based on the setup of given column. */
    private addColumnQuery(query: any, column: Column): void {
        // when a column is marked as filterable
        if (column.filterable) {
            // in the case of default filtering form, `filterPredicate` will contain the string entered by a user
            if (column.filterPredicate) {
                // so we use it as the expected value, * allow to search for it anywhere in the property
                query.__filter[column.path] = `*${column.filterPredicate}*`;
            }

            // in the case of custom filtering form, we're storing the query in `externalFilterQuery.query`
            if (column.externalFilterQuery) {
                query = this.queriesUtil.addAndFilter(query, column.externalFilterQuery.query);
            }
        }

        // when a column is sortable and has a specified sorting order
        if (column.sortable && column.sortOrder) {
            // add sorting condition for the configured column `path`
            query.__orderby.push({
                [column.path]: column.sortOrder === 'asc' ? 1 : -1
            });
        }

        return query;
    }

    async onItemDelete(m: Partial<IManagedObject>): Promise<void> {
        const params: any = {
            cascade: true
        }
        await this.inventoryService.delete(m.id, params);
        this.refreshData$.next(true);
        this.alert.success("Test Devices are deleted!");
    }

    async onItemsDelete(ms: string[]): Promise<void> {
        const params: any = {
            cascade: true
        }

        ms.forEach (async m => {
            await this.inventoryService.delete(m, params);
        })

        this.refreshData$.next(true);
        this.alert.success("Test Devices are deleted!");
    }
}
