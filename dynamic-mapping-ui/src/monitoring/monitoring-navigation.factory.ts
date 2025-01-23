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

import { Injectable } from '@angular/core';
import { ApplicationService } from '@c8y/client';
import * as _ from 'lodash';
import {
  AlertService,
  AppStateService,
  gettext,
  NavigatorNode,
  NavigatorNodeFactory
} from '@c8y/ngx-components';
import { Router } from '@angular/router';
import {  NODE2, SharedService } from '../shared';

@Injectable()
export class MonitoringNavigationFactory implements NavigatorNodeFactory {
  private static readonly APPLICATION_DYNAMIC_MAPPING_SERVICE =
    'dynamic-mapping-service';

  appName: string;
  isStandaloneApp: boolean = false;
  staticNodes = {
    monitoringMappingNode: new NavigatorNode({
      parent: gettext('Monitoring'),
      label: gettext('Statistic processed'),
      icon: 'monitoring',
      path: `sag-ps-pkg-dynamic-mapping/${NODE2}/monitoring/grid`,
      priority: 500,
      preventDuplicates: true
    }),
    monitoringChartNode: new NavigatorNode({
      parent: gettext('Monitoring'),
      label: gettext('Chart processed'),
      icon: 'pie-chart',
      path: `sag-ps-pkg-dynamic-mapping/${NODE2}/monitoring/chart`,
      priority: 500,
      preventDuplicates: true
    }),
    monitoringMappingEventsNode: new NavigatorNode({
      parent: gettext('Monitoring'),
      label: gettext('Service events'),
      icon: 'logs',
      path: `sag-ps-pkg-dynamic-mapping/${NODE2}/monitoring/serviceEvent`,
      priority: 500,
      preventDuplicates: true
    }),

  } as const;

  constructor(
    private applicationService: ApplicationService,
    private alertService: AlertService,
    private sharedService: SharedService,
    private appStateService: AppStateService,
    public router: Router
  ) {
    this.appStateService.currentApplication.subscribe((cur) => {
      this.isStandaloneApp =
        _.has(cur?.manifest, 'isPackage') || _.has(cur?.manifest, 'blueprint');
      this.appName = cur.name;
    });
  }

  async get() {
    const feature: any = await this.sharedService.getFeatures();
    let navs;
    let copyStaticNodesPlugin;

    copyStaticNodesPlugin = _.clone(this.staticNodes);
    if (!feature?.outputMappingEnabled) {
      delete copyStaticNodesPlugin.mappingOutboundNode;
      delete copyStaticNodesPlugin.subscriptionOutboundNode;
    }
    navs = Object.values(copyStaticNodesPlugin) as NavigatorNode[];

    return this.applicationService
      .isAvailable(MonitoringNavigationFactory.APPLICATION_DYNAMIC_MAPPING_SERVICE)
      .then((data) => {
        if (!data.data || !feature) {
          this.alertService.danger(
            'Microservice:dynamic-mapping-service not subscribed. Please subscribe this service before using the mapping editor!'
          );
          console.error('dynamic-mapping-service microservice not subscribed!');
          return [];
        }
        return navs;
      });
  }
}