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
import { Component, OnInit } from '@angular/core';
import { FormControl, FormGroup } from '@angular/forms';
import { AlertService, gettext } from '@c8y/ngx-components';
import { BsModalService } from 'ngx-bootstrap/modal';
import packageJson from '../../package.json';
import { Feature, Operation, SharedService } from '../shared';
import { ServiceConfiguration } from './shared/configuration.model';

@Component({
  selector: 'd11r-mapping-broker-configuration',
  styleUrls: ['./broker-configuration.component.style.css'],
  templateUrl: 'broker-configuration.component.html'
})
export class BrokerConfigurationComponent implements OnInit {
  version: string = packageJson.version;
  serviceForm: FormGroup;
  feature: Feature;

  serviceConfiguration: ServiceConfiguration = {
    logPayload: true,
    logSubstitution: true,
    logConnectorErrorInBackend: false,
    sendConnectorLifecycle: false,
    sendMappingStatus: true,
    sendSubscriptionEvents: false,
    sendNotificationLifecycle: false,
    outboundMappingEnabled: true
  };

  constructor(
    public bsModalService: BsModalService,
    public alertService: AlertService,
    private sharedService: SharedService
  ) {}

  async ngOnInit() {
    // console.log('Running version', this.version);
    this.serviceForm = new FormGroup({
      logPayload: new FormControl(''),
      logSubstitution: new FormControl(''),
      logConnectorErrorInBackend: new FormControl(''),
      sendConnectorLifecycle: new FormControl(''),
      sendMappingStatus: new FormControl(''),
      sendSubscriptionEvents: new FormControl(''),
      sendNotificationLifecycle: new FormControl(''),
      outboundMappingEnabled: new FormControl('')
    });
    this.feature = await this.sharedService.getFeatures();
    if (!this.feature.userHasMappingAdminRole) {
      this.alertService.warning(
        "The configuration on this tab is not editable, as you don't have Mapping ADMIN permissions. Please assign Mapping ADMIN permissions to your user."
      );
    }

    await this.loadData();
  }

  async loadData(): Promise<void> {
    this.serviceConfiguration =
      await this.sharedService.getServiceConfiguration();
  }

  async clickedReconnect2NotificationEndpoint() {
    const response1 = await this.sharedService.runOperation(
      Operation.REFRESH_NOTIFICATIONS_SUBSCRIPTIONS
    );
    // console.log('Details reconnect2NotificationEndpoint', response1);
    if (response1.status === 201) {
      this.alertService.success(gettext('Reconnected successfully.'));
    } else {
      this.alertService.danger(gettext('Failed to reconnect!'));
    }
  }

  async clickedSaveServiceConfiguration() {
    const conf: ServiceConfiguration = {
      ...this.serviceConfiguration
    };
    const response = await this.sharedService.updateServiceConfiguration(conf);
    if (response.status < 300) {
      this.alertService.success(gettext('Update successful'));
    } else {
      this.alertService.danger(
        gettext('Failed to update service configuration')
      );
    }
  }
}
