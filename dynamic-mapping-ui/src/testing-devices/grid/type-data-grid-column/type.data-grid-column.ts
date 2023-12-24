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
import { Type } from '@angular/core';
import {
  Column,
  ColumnDataType,
  SortOrder,
  FilterPredicateFunction
} from '@c8y/ngx-components';
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
