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

import {
  Component,
  EventEmitter,
  Input,
  OnInit,
  Output,
  ViewChild,
  ViewEncapsulation
} from '@angular/core';
import {
  ConnectorConfiguration,
  ConnectorGridComponent,
  DeploymentMapEntry,
  Direction,
  StepperConfiguration
} from '../../shared';
import { EditorMode } from '../shared/stepper.model';

@Component({
  selector: 'd11r-mapping-connector',
  templateUrl: 'mapping-connector.component.html',
  styleUrls: ['../shared/mapping.style.css', './mapping-connector.style.css'],
  encapsulation: ViewEncapsulation.None,
  standalone: true,
  imports: [ConnectorGridComponent]
})
export class MappingConnectorComponent implements OnInit {
  @ViewChild(ConnectorGridComponent) connectorGrid!: ConnectorGridComponent;
  @Input() showHeader: boolean = false;
  @Input() stepperConfiguration: StepperConfiguration;
  @Input() directions: Direction[] = [Direction.INBOUND, Direction.OUTBOUND];
  @Input() deploymentMapEntry: DeploymentMapEntry;
  @Output() deploymentMapEntryChange = new EventEmitter<DeploymentMapEntry>();

  readonly Direction = Direction;
  readonly EditorMode = EditorMode;

  readOnly: boolean;

  ngOnInit(): void {
    this.readOnly = this.stepperConfiguration.editorMode === EditorMode.READ_ONLY;
  }

  onDeploymentMapEntryChanged(value: DeploymentMapEntry): void {
    this.deploymentMapEntry = value;
    this.deploymentMapEntryChange.emit(value);
  }

  onConfigurationAddOrUpdate(config: ConnectorConfiguration): void {
    this.connectorGrid.onConfigurationAddOrUpdate(config);
  }

  refresh(): void {
    this.connectorGrid.refresh();
  }
}
