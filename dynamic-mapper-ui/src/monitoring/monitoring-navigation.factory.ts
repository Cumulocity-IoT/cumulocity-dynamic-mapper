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
import { NODE2, SharedService } from '../shared';

@Injectable()
export class MonitoringNavigationFactory implements NavigatorNodeFactory {
  private static readonly APPLICATION_DYNAMIC_MAPPING_SERVICE =
    'dynamic-mapper-service';

  appName: string;
  isStandaloneApp: boolean = false;
  staticNodesStandalone = {
    monitoringMappingNode: new NavigatorNode({
      parent: gettext('Monitoring'),
      label: gettext('Statistic processed'),
      icon: 'monitoring',
      path: `c8y-pkg-dynamic-mapper/${NODE2}/monitoring/grid`,
      priority: 500,
      preventDuplicates: true
    }),
    monitoringChartNode: new NavigatorNode({
      parent: gettext('Monitoring'),
      label: gettext('Chart processed'),
      icon: 'pie-chart',
      path: `c8y-pkg-dynamic-mapper/${NODE2}/monitoring/chart`,
      priority: 500,
      preventDuplicates: true
    }),
    monitoringMappingEventsNode: new NavigatorNode({
      parent: gettext('Monitoring'),
      label: gettext('Service events'),
      icon: 'logs',
      path: `c8y-pkg-dynamic-mapper/${NODE2}/monitoring/serviceEvent`,
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

  async get(): Promise<any> {
    try {
      const feature: any = await this.sharedService.getFeatures();
      let navs;
      let copyStaticNodesPlugin;

      copyStaticNodesPlugin = _.clone(this.staticNodesStandalone);
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
              'Microservice: dynamic-mapper-service not subscribed. Please subscribe this service before using the mapping editor!'
            );
            console.error('dynamic-mapper-service microservice not subscribed!');
            return [];
          }
          return navs;
        });
    } catch (error) {
      console.error('Error getting features:', error);
      this.alertService.danger(
        'Failed to load resources from the backend service dynamic-mapper-service. Please check that this service is deployed and try again.'
      );

      // Return empty array or handle gracefully based on your needs
      return [];

      // Alternative: You could also rethrow the error if you want calling code to handle it
      // throw error;
    }
  }
}