import { Type } from '@angular/core';
import { Column, ColumnDataType, SortOrder, FilterPredicateFunction } from '@c8y/ngx-components';
import { TypeHeaderCellRendererComponent } from './type.header-cell-renderer.component';
import { TypeCellRendererComponent } from './type.cell-renderer.component';
import { TypeFilteringFormRendererComponent } from './type.filtering-form-renderer.component';

/**
 * Defines a class for custom Type column.
 * Implements `Column` interface and sets basic properties, as well as custom components.
 */
export class TypeDataGridColumn implements Column {
  name: string;
  path?: string;
  header?: string;
  dataType?: ColumnDataType;

  visible?: boolean;
  positionFixed?: boolean;
  gridTrackSize?: string;

  headerCSSClassName?: string | string[];
  headerCellRendererComponent?: Type<any>;

  cellCSSClassName?: string | string[];
  cellRendererComponent?: Type<any>;

  sortable?: boolean;
  sortOrder?: SortOrder;

  filterable?: boolean;
  filteringFormRendererComponent?: Type<any>;
  filterPredicate?: string | FilterPredicateFunction;
  externalFilterQuery?: string | object;

  constructor() {
    this.name = 'type';
    this.header = 'Type';

    this.headerCellRendererComponent = TypeHeaderCellRendererComponent;
    this.cellRendererComponent = TypeCellRendererComponent;

    this.filterable = true;
    this.filteringFormRendererComponent = TypeFilteringFormRendererComponent;

    this.sortable = false;
  }
}
