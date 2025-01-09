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
    'dynamic-mapping-service';

  appName: string;
  isStandaloneApp: boolean = false;
  configurations: ConnectorConfiguration[] = [];
  staticNodesStandalone = {
    rootNode: new NavigatorNode({
      label: gettext('Home'),
      icon: 'home',
      path: '/sag-ps-pkg-dynamic-mapping/landing',
      priority: 600,
      preventDuplicates: true
    }),
    configurationNode: new NavigatorNode({
      label: gettext('Configuration'),
      icon: 'cog',
      // path: `/sag-ps-pkg-dynamic-mapping/${NODE3}/serviceConfiguration`,
      priority: 500,
      preventDuplicates: true
    }),
    connectorNode: new NavigatorNode({
      parent: gettext('Configuration'),
      label: gettext('Connectors'),
      icon: 'c8y-device-management',
      path: `/sag-ps-pkg-dynamic-mapping/${NODE3}/connectorConfiguration`,
      priority: 480,
      preventDuplicates: true
    }),
    serviceConfigurationNode: new NavigatorNode({
      parent: gettext('Configuration'),
      label: gettext('Service configuration'),
      icon: 'cog',
      path: `/sag-ps-pkg-dynamic-mapping/${NODE3}/serviceConfiguration`,
      priority: 470,
      preventDuplicates: true
    }),
    processorExtensionNode: new NavigatorNode({
      parent: gettext('Configuration'),
      label: gettext('Processor extension'),
      icon: 'extension',
      path: `/sag-ps-pkg-dynamic-mapping/${NODE3}/processorExtension`,
      priority: 460,
      preventDuplicates: true
    }),
    mappingNode: new NavigatorNode({
      label: gettext('Mapping'),
      icon: 'rules',
      // path: `/sag-ps-pkg-dynamic-mapping/${NODE1}/mappings/inbound`,
      priority: 400,
      preventDuplicates: true
    }),
    mappingInboundNode: new NavigatorNode({
      parent: gettext('Mapping'),
      label: gettext('Inbound'),
      icon: 'swipe-right',
      path: `/sag-ps-pkg-dynamic-mapping/${NODE1}/mappings/inbound`,
      priority: 390,
      preventDuplicates: true
    }),
    mappingOutboundNode: new NavigatorNode({
      parent: gettext('Mapping'),
      label: gettext('Outbound'),
      icon: 'swipe-left',
      path: `/sag-ps-pkg-dynamic-mapping/${NODE1}/mappings/outbound`,
      priority: 380,
      preventDuplicates: true
    }),
    subscriptionOutboundNode: new NavigatorNode({
      parent: gettext('Mapping'),
      label: gettext('Subscription outbound'),
      icon: 'mail',
      path: `/sag-ps-pkg-dynamic-mapping/${NODE1}/mappings/subscriptionOutbound`,
      priority: 380,
      preventDuplicates: true
    }),
    monitoringNode: new NavigatorNode({
      label: gettext('Monitoring'),
      icon: 'pie-chart',
      path: `/sag-ps-pkg-dynamic-mapping/${NODE2}/monitoring/grid`,
      priority: 300,
      preventDuplicates: true
    })
  } as const;

  staticNodesPlugin = {
    rootNode: new NavigatorNode({
      label: gettext('Dynamic Data Mapper'),
      icon: 'home',
      path: '/sag-ps-pkg-dynamic-mapping/landing',
      priority: 600,
      preventDuplicates: true
    }),
    configurationNode: new NavigatorNode({
      label: gettext('Configuration'),
      icon: 'cog',
      // path: `/sag-ps-pkg-dynamic-mapping/${NODE3}/serviceConfiguration`,
      priority: 500,
      preventDuplicates: true
    }),
    connectorNode: new NavigatorNode({
      parent: gettext('Configuration'),
      label: gettext('Connectors'),
      icon: 'c8y-device-management',
      path: `/sag-ps-pkg-dynamic-mapping/${NODE3}/connectorConfiguration`,
      priority: 480,
      preventDuplicates: true
    }),
    serviceConfigurationNode: new NavigatorNode({
      parent: gettext('Configuration'),
      label: gettext('Service configuration'),
      icon: 'cog',
      path: `/sag-ps-pkg-dynamic-mapping/${NODE3}/serviceConfiguration`,
      priority: 470,
      preventDuplicates: true
    }),
    processorExtensionNode: new NavigatorNode({
      parent: gettext('Configuration'),
      label: gettext('Processor extension'),
      icon: 'extension',
      path: `/sag-ps-pkg-dynamic-mapping/${NODE3}/processorExtension`,
      priority: 460,
      preventDuplicates: true
    }),
    mappingNode: new NavigatorNode({
      label: gettext('Mapping'),
      icon: 'rules',
      // path: `/sag-ps-pkg-dynamic-mapping/${NODE1}/mappings/inbound`,
      priority: 400,
      preventDuplicates: true
    }),
    mappingInboundNode: new NavigatorNode({
      parent: gettext('Mapping'),
      label: gettext('Inbound'),
      icon: 'swipe-right',
      path: `/sag-ps-pkg-dynamic-mapping/${NODE1}/mappings/inbound`,
      priority: 390,
      preventDuplicates: true
    }),
    mappingOutboundNode: new NavigatorNode({
      parent: gettext('Mapping'),
      label: gettext('Outbound'),
      icon: 'swipe-left',
      path: `/sag-ps-pkg-dynamic-mapping/${NODE1}/mappings/outbound`,
      priority: 380,
      preventDuplicates: true
    }),
    subscriptionOutboundNode: new NavigatorNode({
      parent: gettext('Mapping'),
      label: gettext('Subscription outbound'),
      icon: 'mail',
      path: `/sag-ps-pkg-dynamic-mapping/${NODE1}/mappings/subscriptionOutbound`,
      priority: 380,
      preventDuplicates: true
    }),
    monitoringNode: new NavigatorNode({
      label: gettext('Monitoring'),
      icon: 'pie-chart',
      path: `/sag-ps-pkg-dynamic-mapping/${NODE2}/monitoring/grid`,
      priority: 300,
      preventDuplicates: true
    })
  } as const;

  staticNodes = {};

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

    this.connectorConfigurationService.getConnectorConfigurationsAsObservable().subscribe(configs => {
      let connectorsNavNode;
      if (this.isStandaloneApp) {
        connectorsNavNode = this.staticNodesStandalone['connectorNode'];
      } else {
        connectorsNavNode = new NavigatorNode({
          // parent: gettext('Configuration'),
          // parent: gettext('Dynamic Data Mapper'),
          label: gettext('Connectors'),
          icon: 'c8y-device-management',
          path: `/sag-ps-pkg-dynamic-mapping/${NODE3}/connectorConfiguration`,
          priority: 500,
          preventDuplicates: true
        });
        const configurationNode = this.staticNodesPlugin['configurationNode'];
        configurationNode.add(connectorsNavNode);
      }
      // lets clear the array
      connectorsNavNode.children.length = 0;
      configs.forEach(config => {
        connectorsNavNode.add(new NavigatorNode({
          parent: gettext('Connectors'),
          label: gettext(config.name),
          icon: 'connected',
          path: `/sag-ps-pkg-dynamic-mapping/${NODE3}/connectorConfiguration/details/${config.identifier}`,
          priority: 500,
          preventDuplicates: true
        }));
      });
    });
  }

  async get() {
    const feature: any = await this.sharedService.getFeatures();
    let navs;
    let copyStaticNodesPlugin;
    if (this.isStandaloneApp) {
      copyStaticNodesPlugin = _.clone(this.staticNodesStandalone);
      if (!feature?.outputMappingEnabled) {
        delete copyStaticNodesPlugin.mappingOutboundNode;
        delete copyStaticNodesPlugin.subscriptionOutboundNode;
      }
      navs = Object.values(copyStaticNodesPlugin) as NavigatorNode[];
    } else {
      copyStaticNodesPlugin = _.clone(this.staticNodesPlugin);
      if (!feature?.outputMappingEnabled) {
        delete copyStaticNodesPlugin.mappingOutboundNode;
        delete copyStaticNodesPlugin.subscriptionOutboundNode;
      }
    }
    navs = Object.values(copyStaticNodesPlugin) as NavigatorNode[];

    return this.applicationService
      .isAvailable(MappingNavigationFactory.APPLICATION_DYNAMIC_MAPPING_SERVICE)
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