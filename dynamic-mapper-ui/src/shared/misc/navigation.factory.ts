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
import { SharedService } from '../service/shared.service';
import { NODE1, NODE2, NODE3 } from '../mapping/util';
import { Router } from '@angular/router';
import { ConnectorConfigurationService } from '../service/connector-configuration.service';
import { ConnectorConfiguration } from '..';

@Injectable()
export class MappingNavigationFactory implements NavigatorNodeFactory {
  private static readonly APPLICATION_DYNAMIC_MAPPING_SERVICE =
    'dynamic-mapper-service';

  appName: string;
  isStandaloneApp: boolean = false;
  configurations: ConnectorConfiguration[] = [];
  staticNodesStandalone = {
    rootNode: new NavigatorNode({
      label: gettext('Home'),
      icon: 'home',
      path: '/c8y-pkg-dynamic-mapper/landing',
      priority: 600,
      preventDuplicates: true
    }),
    configurationNode: new NavigatorNode({
      label: gettext('Configuration'),
      icon: 'cog',
      // path: `/c8y-pkg-dynamic-mapper/${NODE3}/serviceConfiguration`,
      priority: 500,
      preventDuplicates: true
    }),
    connectorNode: new NavigatorNode({
      parent: gettext('Configuration'),
      label: gettext('Connectors'),
      icon: 'c8y-device-management',
      path: `/c8y-pkg-dynamic-mapper/${NODE3}/connectorConfiguration`,
      priority: 480,
      preventDuplicates: true
    }),
    serviceConfigurationNode: new NavigatorNode({
      parent: gettext('Configuration'),
      label: gettext('Service configuration'),
      icon: 'cog',
      path: `/c8y-pkg-dynamic-mapper/${NODE3}/serviceConfiguration`,
      priority: 470,
      preventDuplicates: true
    }),
    sharedCodeNode: new NavigatorNode({
      parent: gettext('Configuration'),
      label: gettext('Code template'),
      icon: 'source-code',
      path: `/c8y-pkg-dynamic-mapper/${NODE3}/codeTemplate/inbound`,
      priority: 460,
      preventDuplicates: true
    }),
    processorExtensionNode: new NavigatorNode({
      parent: gettext('Configuration'),
      label: gettext('Processor extension'),
      icon: 'extension',
      path: `/c8y-pkg-dynamic-mapper/${NODE3}/processorExtension`,
      priority: 450,
      preventDuplicates: true
    }),
    mappingNode: new NavigatorNode({
      label: gettext('Mapping'),
      icon: 'rules',
      // path: `/c8y-pkg-dynamic-mapper/${NODE1}/mappings/inbound`,
      priority: 400,
      preventDuplicates: true
    }),
    mappingInboundNode: new NavigatorNode({
      parent: gettext('Mapping'),
      label: gettext('Inbound'),
      icon: 'swipe-right',
      path: `/c8y-pkg-dynamic-mapper/${NODE1}/mappings/inbound`,
      priority: 390,
      preventDuplicates: true
    }),
    mappingOutboundNode: new NavigatorNode({
      parent: gettext('Mapping'),
      label: gettext('Outbound'),
      icon: 'swipe-left',
      path: `/c8y-pkg-dynamic-mapper/${NODE1}/mappings/outbound`,
      priority: 380,
      preventDuplicates: true
    }),
    subscriptionStaticNode: new NavigatorNode({
      parent: gettext('Mapping'),
      label: gettext('Subscription outbound'),
      icon: 'mail',
      path: `/c8y-pkg-dynamic-mapper/${NODE1}/mappings/subscription/static`,
      priority: 380,
      preventDuplicates: true
    }),
    monitoringNode: new NavigatorNode({
      label: gettext('Monitoring'),
      icon: 'pie-chart',
      path: `/c8y-pkg-dynamic-mapper/${NODE2}/monitoring/grid`,
      priority: 300,
      preventDuplicates: true
    })
  } as const;

  constructor(
    private applicationService: ApplicationService,
    private alertService: AlertService,
    private sharedService: SharedService,
    private connectorConfigurationService: ConnectorConfigurationService,
    private appStateService: AppStateService,
    public router: Router
  ) {
    this.appStateService.currentApplication.subscribe((cur) => {
      this.isStandaloneApp =
        _.has(cur?.manifest, 'isPackage') || _.has(cur?.manifest, 'blueprint');
      //   console.log(
      //     'Constructor: AppName in MappingNavigationFactory',
      //     cur,
      //     this.isStandaloneApp,
      //     _.has(cur?.manifest, 'isPackage')
      //   );
      this.appName = cur.name;
    });

    this.connectorConfigurationService.getConfigurations().subscribe(configs => {
      let connectorsNavNode;

      connectorsNavNode = this.staticNodesStandalone['connectorNode'];

      // lets clear the array
      connectorsNavNode.children.length = 0;
      configs.forEach(config => {
        connectorsNavNode.add(new NavigatorNode({
          parent: gettext('Connectors'),
          label: gettext(config.name),
          icon: 'connected',
          path: `/c8y-pkg-dynamic-mapper/${NODE3}/connectorConfiguration/details/${config.identifier}`,
          priority: 500,
          preventDuplicates: true
        }));
      });
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
        .isAvailable(MappingNavigationFactory.APPLICATION_DYNAMIC_MAPPING_SERVICE)
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