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
import {
  Component,
  EventEmitter,
  Input,
  OnDestroy,
  OnInit,
  Output,
  ViewEncapsulation
} from '@angular/core';
import { AlertService, gettext } from '@c8y/ngx-components';
import { BehaviorSubject } from 'rxjs';
import {
  ConfigurationConfigurationModalComponent,
  ConnectorConfiguration,
  ConnectorSpecification,
  DeploymentMapEntry,
  Direction,
  Feature,
  StepperConfiguration,
  uuidCustom
} from '../../shared';
import { EditorMode } from '../shared/stepper-model';
import { SharedService } from '../../shared/shared.service';
import { BsModalService } from 'ngx-bootstrap/modal';
import { ConnectorConfigurationService } from '../../connector';

@Component({
  selector: 'd11r-mapping-connector',
  templateUrl: 'mapping-connector.component.html',
  styleUrls: ['../shared/mapping.style.css'],
  encapsulation: ViewEncapsulation.None
})
export class MappingConnectorComponent implements OnInit, OnDestroy {
  @Input() stepperConfiguration: StepperConfiguration;
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

  selectedResult$: BehaviorSubject<number> = new BehaviorSubject<number>(0);
  specifications: ConnectorSpecification[] = [];
  configurations: ConnectorConfiguration[];

  constructor(
    private alertService: AlertService,
    private sharedService: SharedService,
    public bsModalService: BsModalService,
    public connectorConfigurationService: ConnectorConfigurationService
  ) {}

  async ngOnInit() {
    this.feature = await this.sharedService.getFeatures();
    this.specifications =
      await this.connectorConfigurationService.getConnectorSpecifications();
    this.connectorConfigurationService
      .getConnectorConfigurationsLive()
      .subscribe((confs) => {
        this.configurations = confs;
      });
  }

  async onConfigurationAdd() {
    const configuration: Partial<ConnectorConfiguration> = {
      properties: {},
      ident: uuidCustom()
    };
    const initialState = {
      add: true,
      configuration: configuration,
      specifications: this.specifications,
      configurationsCount: this.configurations.length
    };
    const modalRef = this.bsModalService.show(
      ConfigurationConfigurationModalComponent,
      {
        initialState
      }
    );
    modalRef.content.closeSubject.subscribe(async (addedConfiguration) => {
      // console.log('Configuration after edit:', addedConfiguration);
      if (addedConfiguration) {
        this.configurations.push(addedConfiguration);
        // avoid to include status$
        const clonedConfiguration = {
          ident: addedConfiguration.ident,
          connectorType: addedConfiguration.connectorType,
          enabled: addedConfiguration.enabled,
          name: addedConfiguration.name,
          properties: addedConfiguration.properties
        };
        const response =
          await this.connectorConfigurationService.createConnectorConfiguration(
            clonedConfiguration
          );
        if (response.status < 300) {
          this.alertService.success(
            gettext('Added successfully configuration')
          );
        } else {
          this.alertService.danger(
            gettext('Failed to update connector configuration')
          );
        }
      }
    });
    await this.loadData();
  }

  loadData(): void {
    this.connectorConfigurationService.startConnectorConfigurations();
  }

  ngOnDestroy() {
    this.selectedResult$.complete();
  }
}
