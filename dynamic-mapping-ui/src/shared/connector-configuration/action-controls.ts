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
      gridTrackSize: '30%'
    },
    {
      name: 'connectorType',
      header: 'Type',
      path: 'connectorType',
      filterable: false,
      sortOrder: 'ASC',
      visible: true,
      cellRendererComponent: LabelRendererComponent,
      gridTrackSize: '25%'
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