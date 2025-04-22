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
// Now update action-controls.config.ts
import { BuiltInActionType, Column } from '@c8y/ngx-components';
import { ActionControlConfig, ActionVisibilityRule } from './types'
import { LabelRendererComponent } from '../component/renderer/label.renderer.component';
import { ConnectorStatusRendererComponent } from './renderer/connector-status.renderer.component';
import { StatusEnabledRendererComponent } from './renderer/status-enabled-renderer.component';
import { ConnectorDetailCellRendererComponent } from './renderer/connector-link.renderer.component';

export const ACTION_CONTROLS: ActionControlConfig[] = [
  {
    type: BuiltInActionType.Edit,
    callbackName: 'onConfigurationUpdate',
    visibilityRules: [
      { type: 'enabled', value: false },
      { type: 'readOnly', value: false }
    ] as ActionVisibilityRule[]
  },
  {
    type: 'VIEW',
    icon: 'eye',
    callbackName: 'onConfigurationUpdate',
    visibilityRules: [
      { type: 'enabled', value: true },
      { type: 'readOnly', value: false },
      { type: 'connectorType' }
    ] as ActionVisibilityRule[]
  },
  {
    text: 'Duplicate',
    type: 'duplicate',
    icon: 'duplicate',
    callbackName: 'onConfigurationCopy',
    visibilityRules: [
      { type: 'enabled', value: false },
      { type: 'readOnly', value: false },
      { type: 'connectorType' }
    ] as ActionVisibilityRule[]
  },
  {
    type: BuiltInActionType.Delete,
    callbackName: 'onConfigurationDelete',
    visibilityRules: [
      { type: 'enabled', value: false },
      { type: 'readOnly', value: false },
      { type: 'connectorType' }
    ] as ActionVisibilityRule[]
  }
];

export const GRID_COLUMNS: Column[] = [
    {
      name: 'identifier',
      header: 'Identifier',
      path: 'identifier',
      filterable: false,
      sortOrder: 'ASC',
      visible: false,
      gridTrackSize: '10%'
    },
    {
      name: 'name',
      header: 'Name',
      path: 'name',
      filterable: false,
      sortOrder: 'ASC',
      visible: true,
      cellRendererComponent: ConnectorDetailCellRendererComponent,
      gridTrackSize: '25%'
    },
    {
      name: 'connectorType',
      header: 'Type',
      path: 'connectorType',
      filterable: false,
      sortOrder: 'ASC',
      visible: true,
      cellRendererComponent: LabelRendererComponent,
      gridTrackSize: '15%'
    },
    {
      name: 'supportedDirections',
      header: 'Directions',
      path: 'supportedDirections',
      filterable: false,
      sortOrder: 'ASC',
      visible: true,
      cellRendererComponent: LabelRendererComponent,
      gridTrackSize: '10%'
    },
    {
      name: 'status',
      header: 'Status',
      path: 'status',
      filterable: false,
      sortable: true,
      cellRendererComponent: ConnectorStatusRendererComponent,
      gridTrackSize: '17%'
    },
    {
      name: 'enabled',
      header: 'Enabled',
      path: 'enabled',
      filterable: false,
      sortable: true,
      cellRendererComponent: StatusEnabledRendererComponent,
      gridTrackSize: '16%'
    }
  ] as Column[];