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
import { CommonModule } from '@angular/common';
import {
  Component,
  EventEmitter,
  Input,
  OnDestroy,
  OnInit,
  Output,
  ViewChild,
  ViewEncapsulation
} from '@angular/core';
import { BsModalService } from 'ngx-bootstrap/modal';
import { BehaviorSubject } from 'rxjs';
import { ConnectorConfigurationService } from '../../connector';
import {
  ConnectorConfiguration,
  ConnectorGridComponent,
  DeploymentMapEntry,
  Direction,
  Feature,
  SharedService,
  StepperConfiguration
} from '../../shared';
import { EditorMode } from '../shared/stepper.model';

@Component({
  selector: 'd11r-mapping-connector',
  templateUrl: 'mapping-connector.component.html',
  styleUrls: ['../shared/mapping.style.css', './mapping-connector.style.css'],
  encapsulation: ViewEncapsulation.None,
  standalone: true,
  imports: [ConnectorGridComponent, CommonModule]
})
export class MappingConnectorComponent implements OnInit, OnDestroy {
  @ViewChild(ConnectorGridComponent) connectorGrid!: ConnectorGridComponent;
  @Input() stepperConfiguration: StepperConfiguration;
  @Input() directions: Direction[] = [Direction.INBOUND, Direction.OUTBOUND];
  @Output() deploymentMapEntryChange = new EventEmitter<DeploymentMapEntry>();

  readonly Direction = Direction;
  readonly EditorMode = EditorMode;
  readonly selectedResult$ = new BehaviorSubject<number>(0);

  feature: Feature;
  readOnly: boolean;

  private _deploymentMapEntry: DeploymentMapEntry;

  constructor(
    private readonly sharedService: SharedService,
    public readonly bsModalService: BsModalService,
    public readonly connectorConfigurationService: ConnectorConfigurationService
  ) {}

  @Input()
  get deploymentMapEntry(): DeploymentMapEntry {
    return this._deploymentMapEntry;
  }

  set deploymentMapEntry(value: DeploymentMapEntry) {
    this._deploymentMapEntry = value;
    this.deploymentMapEntryChange.emit(value);
  }

  async ngOnInit(): Promise<void> {
    this.readOnly = this.stepperConfiguration.editorMode === EditorMode.READ_ONLY;
    this.feature = await this.sharedService.getFeatures();
  }

  ngOnDestroy(): void {
    this.selectedResult$.complete();
  }

  async onConfigurationAddOrUpdate(config: ConnectorConfiguration): Promise<void> {
    this.connectorGrid.onConfigurationAddOrUpdate(config);
  }

  refresh(): void {
    this.connectorGrid.refresh();
  }
}
