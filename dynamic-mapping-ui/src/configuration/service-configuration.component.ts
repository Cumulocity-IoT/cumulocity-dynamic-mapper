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
import { HttpStatusCode } from '@angular/common/http';
import { Component, OnInit } from '@angular/core';
import { FormBuilder, FormControl, FormGroup } from '@angular/forms';
import { AlertService, gettext } from '@c8y/ngx-components';
import { BsModalService } from 'ngx-bootstrap/modal';
import packageJson from '../../package.json';
import { Feature, Operation, SharedService } from '../shared';
import { ServiceConfiguration } from './shared/configuration.model';

@Component({
  selector: 'd11r-mapping-service-configuration',
  styleUrls: ['./service-configuration.component.style.css'],
  templateUrl: 'service-configuration.component.html'
})
export class ServiceConfigurationComponent implements OnInit {
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
    outboundMappingEnabled: true,
    inboundExternalIdCacheSize: 0,
    inboundExternalIdCacheRetention: 0,
    inventoryCacheSize: 0,
    inventoryCacheRetention: 0,
  };
  editable2updated: boolean = false;

  constructor(
    public bsModalService: BsModalService,
    public alertService: AlertService,
    private sharedService: SharedService,
    private fb: FormBuilder
  ) { }

  ngOnInit() {
    // console.log('Running version', this.version);
    this.serviceForm = this.fb.group({
      logPayload: new FormControl(''),
      logSubstitution: new FormControl(''),
      logConnectorErrorInBackend: new FormControl(''),
      sendConnectorLifecycle: new FormControl(''),
      sendMappingStatus: new FormControl(''),
      sendSubscriptionEvents: new FormControl(''),
      sendNotificationLifecycle: new FormControl(''),
      outboundMappingEnabled: new FormControl(''),
      inboundExternalIdCacheSize: new FormControl(''),
      inboundExternalIdCacheRetention: new FormControl(''),
      inventoryCacheRetention: new FormControl(''),
      inventoryCacheSize: new FormControl(''),
      inventoryFragmentsToCache: new FormControl('')
    });

    this.loadData();
  }

  async loadData(): Promise<void> {
    this.serviceConfiguration =
      await this.sharedService.getServiceConfiguration();
    this.serviceForm.patchValue({
      logPayload: this.serviceConfiguration.logPayload,
      logSubstitution: this.serviceConfiguration.logSubstitution,
      logConnectorErrorInBackend:
        this.serviceConfiguration.logConnectorErrorInBackend,
      sendConnectorLifecycle: this.serviceConfiguration.sendConnectorLifecycle,
      sendMappingStatus: this.serviceConfiguration.sendMappingStatus,
      sendSubscriptionEvents: this.serviceConfiguration.sendSubscriptionEvents,
      sendNotificationLifecycle:
        this.serviceConfiguration.sendNotificationLifecycle,
      outboundMappingEnabled: this.serviceConfiguration.outboundMappingEnabled,
      inboundExternalIdCacheSize:
        this.serviceConfiguration.inboundExternalIdCacheSize,
      inboundExternalIdCacheRetention:
        this.serviceConfiguration.inboundExternalIdCacheRetention,
      inventoryCacheSize:
        this.serviceConfiguration.inventoryCacheSize,
      inventoryCacheRetention:
        this.serviceConfiguration.inventoryCacheRetention,
      inventoryFragmentsToCache:
        this.serviceConfiguration.inventoryFragmentsToCache.join(",")
    });
  }

  async clickedReconnect2NotificationEndpoint() {
    const response1 = await this.sharedService.runOperation(
      { operation: Operation.REFRESH_NOTIFICATIONS_SUBSCRIPTIONS }
    );
    // console.log('Details reconnect2NotificationEndpoint', response1);
    if (response1.status === HttpStatusCode.Created) {
      this.alertService.success(gettext('Reconnected successfully.'));
    } else {
      this.alertService.danger(gettext('Failed to reconnect!'));
    }
  }

  async clickedClearInboundExternalIdCache() {
    const response1 = await this.sharedService.runOperation(
      {
        operation: Operation.CLEAR_CACHE,
        parameter: { cacheId: 'INBOUND_ID_CACHE' }
      }
    );
    if (response1.status === HttpStatusCode.Created) {
      this.alertService.success(gettext('Cache cleared.'));
    } else {
      this.alertService.danger(gettext('Failed to clear cache!'));
    }
  }

  async clickedClearInventoryCache() {
    const response1 = await this.sharedService.runOperation(
      {
        operation: Operation.CLEAR_CACHE,
        parameter: { cacheId: 'INBOUND_CACHE' }
      }
    );
    if (response1.status === HttpStatusCode.Created) {
      this.alertService.success(gettext('Cache cleared.'));
    } else {
      this.alertService.danger(gettext('Failed to clear cache!'));
    }
  }

  async clickedResetDeploymentMapEndpoint() {
    const response1 = await this.sharedService.runOperation(
      { operation: Operation.RESET_DEPLOYMENT_MAP }
    );
    // console.log('Details reconnect2NotificationEndpoint', response1);
    if (response1.status === HttpStatusCode.Created) {
      this.alertService.success(gettext('Reset deploymentMap.'));
    } else {
      this.alertService.danger(gettext('Failed to reset deploymentMap!'));
    }
  }

  async clickedSaveServiceConfiguration() {
    const conf = this.serviceForm.value;
    conf.inventoryFragmentsToCache = this.serviceForm.value['inventoryFragmentsToCache'].split(",");
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
