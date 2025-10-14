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
import { LabelTaggedRendererComponent } from '../component/renderer/label-tagged.renderer.component';
import { LabelRendererComponent } from '../component/renderer/label.renderer.component';
import { ConnectorStatusRendererComponent } from './renderer/connector-status.renderer.component';
import { ConnectorStatusEnabledRendererComponent } from './renderer/status-enabled-renderer.component';
import { ConnectorDetailCellRendererComponent } from './renderer/connector-link.renderer.component';

export const ACTION_CONTROLS: ActionControlConfig[] = [
  // Edit action for admin users on disabled connectors
  {
    type: BuiltInActionType.Edit,
    callbackName: 'onConfigurationAddOrUpdate',
    visibilityRules: [
      { type: 'enabled', value: false },
      { type: 'readOnly', value: false },
      { type: 'userRole', value: true }
    ] as ActionVisibilityRule[]
  },
  {
    type: 'VIEW',
    icon: 'eye',
    callbackName: 'onConfigurationAddOrUpdate',
    visibilityRules: [
      { type: 'readOnly', value: false },
      { type: 'connectorType' },
      { type: 'userRole', value: 'viewLogic' } // Custom logic
    ] as ActionVisibilityRule[]
  },
  {
    type: 'duplicate',
    text: 'Duplicate',
    icon: 'duplicate',
    callbackName: 'onConfigurationCopy',
    visibilityRules: [
      { type: 'enabled', value: false },
      { type: 'readOnly', value: false },
      { type: 'connectorType' },
      { type: 'userRole', value: true } // Admin user
    ] as ActionVisibilityRule[]
  },
  {
    type: BuiltInActionType.Delete,
    callbackName: 'onConfigurationDelete',
    visibilityRules: [
      { type: 'enabled', value: false },
      { type: 'readOnly', value: false },
      { type: 'connectorType' },
      { type: 'userRole', value: true } // Admin user
    ] as ActionVisibilityRule[]
  }
];

export const GRID_COLUMNS: Column[] = [
  {
    name: 'name',
    header: 'Name',
    path: 'name',
    filterable: false,
    sortOrder: 'ASC',
    visible: true,
    cellRendererComponent: ConnectorDetailCellRendererComponent,
    gridTrackSize: '30%'
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
    name: 'identifier',
    header: 'Identifier',
    path: 'identifier',
    filterable: false,
    sortOrder: 'ASC',
    visible: false,
    gridTrackSize: '10%'
  },
  {
    name: 'connectorType',
    header: 'Type',
    path: 'connectorType',
    filterable: false,
    sortOrder: 'ASC',
    visible: true,
    cellRendererComponent: LabelTaggedRendererComponent,
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
    name: 'enabled',
    header: 'Enabled',
    path: 'enabled',
    filterable: false,
    sortable: true,
    cellRendererComponent: ConnectorStatusEnabledRendererComponent,
    gridTrackSize: '11%'
  }
] as Column[];