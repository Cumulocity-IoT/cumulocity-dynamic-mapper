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
import { ChangeDetectorRef, Component, OnInit, ViewEncapsulation } from '@angular/core';
import {
  AlertService,
  CellRendererContext,
  CoreModule
} from '@c8y/ngx-components';
import { Direction, Feature, Operation, SharedService } from '../..';
import { ConnectorConfigurationService } from '../../service/connector-configuration.service';
import { HttpStatusCode } from '@angular/common/http';
import { gettext } from '@c8y/ngx-components/gettext';
import { TranslatePipe } from '@ngx-translate/core';

/**
 * The example component for custom cell renderer.
 * It gets `context` with the current row item and the column.
 * Additionally, a service is injected to provide a helper method.
 * The template displays the icon and the label with additional styling.
 */
@Component({
  encapsulation: ViewEncapsulation.None,
  template: `
    <div>
      <label
        title="{{ 'Toggle connection activation' | translate }}"
        class="c8y-switch"
      >
        <input
          type="checkbox"
          [checked]="context.value"
          [disabled]="isInputDisabled || isLoading"
          (change)="onConfigurationToggle()"
        />
        <span></span>
      </label>
    </div>
  `,
  standalone: true,
  imports: [
    CoreModule
  ]

})
export class ConnectorStatusEnabledRendererComponent implements OnInit {
  constructor(
    public context: CellRendererContext,
    public alertService: AlertService,
    public sharedService: SharedService,
    private connectorConfigurationService: ConnectorConfigurationService,
    private cdr: ChangeDetectorRef
  ) {
    this.featurePromise = this.sharedService.getFeatures();
    // console.log('Status', context, context.value);
  }

  feature: Feature | null = null;
  isLoading = false;
  private featurePromise: Promise<Feature>;

  async ngOnInit() {
    try {
      this.feature = await this.featurePromise;
      this.cdr.detectChanges();
    } catch (error) {
      console.error('Error loading features in component', error);
    }
  }

  get isInputDisabled(): boolean {
    if (!this.feature) {
      return true;
    }
    return this.context.item.readOnly || !this.feature?.userHasMappingAdminRole;
  }

  async onConfigurationToggle() {
    this.isLoading = true;
    this.cdr.detectChanges();
    try {
      const configuration = this.context.item;
      const response1 = await this.sharedService.runOperation(
        configuration.enabled
          ? { operation: Operation.DISCONNECT, parameter: { connectorIdentifier: configuration.identifier } }
          : { operation: Operation.CONNECT, parameter: { connectorIdentifier: configuration.identifier } }
      );
      if (response1.status === HttpStatusCode.Created) {
        this.alertService.success(gettext('Connection updated successfully.'));
      } else {
        this.alertService.danger(gettext('Failed to establish connection!'));
      }
    } finally {
      this.isLoading = false;
      this.cdr.detectChanges();
    }
    this.reloadData();
    this.sharedService.refreshMappings(Direction.INBOUND);
    this.sharedService.refreshMappings(Direction.OUTBOUND);
  }
  reloadData(): void {
    this.connectorConfigurationService.refreshConfigurations();
  }
}
