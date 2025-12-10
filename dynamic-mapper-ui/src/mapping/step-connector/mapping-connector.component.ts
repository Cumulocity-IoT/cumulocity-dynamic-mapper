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
  OnDestroy,
  OnInit,
  Output,
  ViewChild,
  ViewEncapsulation
} from '@angular/core';
import { BehaviorSubject } from 'rxjs';
import {
  ConnectorConfiguration,
  DeploymentMapEntry,
  Direction,
  Feature,
  StepperConfiguration
} from '../../shared';
import { EditorMode } from '../shared/stepper.model';
import { ConnectorGridComponent, SharedService } from '../../shared';
import { BsModalService } from 'ngx-bootstrap/modal';
import { ConnectorConfigurationService } from '../../connector';

@Component({
  selector: 'd11r-mapping-connector',
  templateUrl: 'mapping-connector.component.html',
  styleUrls: ['../shared/mapping.style.css', './mapping-connector.style.css'],
  encapsulation: ViewEncapsulation.None,
  standalone: true,
  imports:[ConnectorGridComponent]
})
export class MappingConnectorComponent implements OnInit, OnDestroy {
  @ViewChild(ConnectorGridComponent)
  connectorGrid!: ConnectorGridComponent;
  @Input() stepperConfiguration: StepperConfiguration;
  @Input() directions: Direction[] = [Direction.INBOUND, Direction.OUTBOUND];
  private _deploymentMapEntry: DeploymentMapEntry;
  @Input()
  get deploymentMapEntry(): DeploymentMapEntry {
    return this._deploymentMapEntry;
  }
  set deploymentMapEntry(value: DeploymentMapEntry) {
    this._deploymentMapEntry = value;
    this.deploymentMapEntryChange.emit(value);
  }
  @Output() deploymentMapEntryChange = new EventEmitter<any>();
  Direction = Direction;
  EditorMode = EditorMode;
  feature: Feature;
  readOnly: boolean;

  selectedResult$: BehaviorSubject<number> = new BehaviorSubject<number>(0);

  constructor(
    private sharedService: SharedService,
    public bsModalService: BsModalService,
    public connectorConfigurationService: ConnectorConfigurationService
  ) { }

  async ngOnInit() {
    this.readOnly =
      this.stepperConfiguration.editorMode == EditorMode.READ_ONLY;
    this.feature = await this.sharedService.getFeatures();
  }

  async onConfigurationAddOrUpdate(config:ConnectorConfiguration) {
    this.connectorGrid.onConfigurationAddOrUpdate(config);
  }

  refresh() {
    this.connectorGrid.refresh();
  }

  ngOnDestroy() {
    this.selectedResult$.complete();
  }
}
