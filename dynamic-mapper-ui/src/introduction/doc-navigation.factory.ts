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
  NavigatorNode,
  NavigatorNodeFactory
} from '@c8y/ngx-components';
import { Router } from '@angular/router';
import { gettext } from '@c8y/ngx-components/gettext';
import { SharedService } from '../shared';

@Injectable()
export class DocNavigationFactory implements NavigatorNodeFactory {
  private static readonly APPLICATION_DYNAMIC_MAPPING_SERVICE =
    'dynamic-mapper-service';
  appName: string;
  isStandaloneApp: boolean = false;
  /**
   * The documentation navigator, grouped into three tiers so a reader can tell what they need
   * now from what they need later:
   *
   *   Start     — the path for someone who has never used the mapper (overview, quickstart, concepts)
   *   Guides    — task-oriented pages, read when doing that task
   *   Reference — per-feature detail, read on demand
   *
   * This replaces a flat list of 16 siblings that all pointed at the same combined page.
   * Priorities descend within each group; the groups themselves are spaced 100 apart so pages
   * can be inserted without renumbering neighbours.
   */
  staticNodesStandalone = {
    rootNode: new NavigatorNode({
      label: gettext('Home'),
      icon: 'home',
      path: '/c8y-pkg-dynamic-mapper/introduction',
      priority: 1000,
      preventDuplicates: true
    }),

    // ---- Start ---------------------------------------------------------------
    startNode: new NavigatorNode({
      parent: gettext('Home'),
      icon: 'rocket',
      label: gettext('Start'),
      priority: 900,
      preventDuplicates: true
    }),
    overviewNode: new NavigatorNode({
      parent: gettext('Start'),
      icon: 'book',
      label: gettext('Overview'),
      path: '/c8y-pkg-dynamic-mapper/introduction/overview',
      priority: 893,
      preventDuplicates: true
    }),
    quickstartNode: new NavigatorNode({
      parent: gettext('Start'),
      icon: 'flash',
      label: gettext('Quickstart'),
      path: '/c8y-pkg-dynamic-mapper/introduction/quickstart',
      priority: 892,
      preventDuplicates: true
    }),
    conceptsNode: new NavigatorNode({
      parent: gettext('Start'),
      icon: 'lightbulb-o',
      label: gettext('Core concepts'),
      path: '/c8y-pkg-dynamic-mapper/introduction/concepts',
      priority: 891,
      preventDuplicates: true
    }),

    // ---- Guides --------------------------------------------------------------
    guidesNode: new NavigatorNode({
      parent: gettext('Home'),
      icon: 'map-o',
      label: gettext('Guides'),
      priority: 800,
      preventDuplicates: true
    }),
    managingConnectorsNode: new NavigatorNode({
      parent: gettext('Guides'),
      icon: 'plug',
      label: gettext('Managing connectors'),
      path: '/c8y-pkg-dynamic-mapper/introduction/managing-connectors',
      priority: 793,
      preventDuplicates: true
    }),
    connectorReferenceNode: new NavigatorNode({
      parent: gettext('Managing connectors'),
      icon: 'image',
      label: gettext('Connector reference'),
      path: '/c8y-pkg-dynamic-mapper/introduction/connectors',
      priority: 792,
      preventDuplicates: true
    }),
    definingMappingNode: new NavigatorNode({
      parent: gettext('Guides'),
      icon: 'exchange',
      label: gettext('Defining a mapping'),
      path: '/c8y-pkg-dynamic-mapper/introduction/define-mapping',
      priority: 791,
      preventDuplicates: true
    }),
    subscriptionOutboundNode: new NavigatorNode({
      parent: gettext('Guides'),
      icon: 'upload',
      label: gettext('Outbound mapping'),
      path: '/c8y-pkg-dynamic-mapper/introduction/define-subscription-for-outbound',
      priority: 790,
      preventDuplicates: true
    }),
    transformationTypesNode: new NavigatorNode({
      parent: gettext('Guides'),
      icon: 'sitemap',
      label: gettext('Transformation types'),
      path: '/c8y-pkg-dynamic-mapper/introduction/transformation-types',
      priority: 789,
      preventDuplicates: true
    }),
    jsonNataNode: new NavigatorNode({
      parent: gettext('Transformation types'),
      icon: 'terminal',
      label: gettext('JSONata'),
      path: '/c8y-pkg-dynamic-mapper/introduction/jsonata',
      priority: 788,
      preventDuplicates: true
    }),
    smartFunctionNode: new NavigatorNode({
      parent: gettext('Transformation types'),
      icon: 'code',
      label: gettext('Smart Function'),
      path: '/c8y-pkg-dynamic-mapper/introduction/smartfunction',
      priority: 787,
      preventDuplicates: true
    }),
    javaExtensionNode: new NavigatorNode({
      parent: gettext('Transformation types'),
      icon: 'java',
      label: gettext('Java Extension'),
      path: '/c8y-pkg-dynamic-mapper/introduction/javaextension',
      priority: 786,
      preventDuplicates: true
    }),
    customRoutingNode: new NavigatorNode({
      parent: gettext('Transformation types'),
      icon: 'random',
      label: gettext('Custom routing'),
      path: '/c8y-pkg-dynamic-mapper/introduction/custom-routing',
      priority: 785,
      preventDuplicates: true
    }),
    messageExplorerNode: new NavigatorNode({
      parent: gettext('Guides'),
      icon: 'search',
      label: gettext('Message Explorer'),
      path: '/c8y-pkg-dynamic-mapper/introduction/message-explorer',
      priority: 784,
      preventDuplicates: true
    }),
    // Label must NOT be plain 'Monitoring'. Cumulocity's NavigatorNode nests by label string,
    // and the application's own Monitoring node (shared/misc/navigation.factory.ts) is the parent
    // of six real pages — Statistic processed, Service events, Cache statistic, Versions,
    // Test device and Hierarchy mapping. Naming this node 'Monitoring' makes those six app pages
    // reparent themselves under this documentation entry.
    monitoringNode: new NavigatorNode({
      parent: gettext('Guides'),
      icon: 'line-chart',
      label: gettext('Monitoring overview'),
      path: '/c8y-pkg-dynamic-mapper/introduction/monitoring',
      priority: 783,
      preventDuplicates: true
    }),
    troubleshootingNode: new NavigatorNode({
      parent: gettext('Guides'),
      icon: 'wrench',
      label: gettext('Troubleshooting'),
      path: '/c8y-pkg-dynamic-mapper/introduction/troubleshooting',
      priority: 782,
      preventDuplicates: true
    }),

    // ---- Reference -----------------------------------------------------------
    referenceNode: new NavigatorNode({
      parent: gettext('Home'),
      icon: 'list-alt',
      label: gettext('Reference'),
      priority: 700,
      preventDuplicates: true
    }),
    payloadTypesNode: new NavigatorNode({
      parent: gettext('Reference'),
      icon: 'file-code-o',
      label: gettext('Payload types'),
      path: '/c8y-pkg-dynamic-mapper/introduction/payload-types',
      priority: 699,
      preventDuplicates: true
    }),
    sparkPlugBNode: new NavigatorNode({
      parent: gettext('Reference'),
      icon: 'bolt',
      label: gettext('SparkPlug B'),
      path: '/c8y-pkg-dynamic-mapper/introduction/sparkplugb',
      priority: 698,
      preventDuplicates: true
    }),
    metadataNode: new NavigatorNode({
      parent: gettext('Reference'),
      icon: 'tags',
      label: gettext('Metadata'),
      path: '/c8y-pkg-dynamic-mapper/introduction/metadata',
      priority: 697,
      preventDuplicates: true
    }),
    flowStateNode: new NavigatorNode({
      parent: gettext('Reference'),
      icon: 'flow-chart',
      label: gettext('Flow state'),
      path: '/c8y-pkg-dynamic-mapper/introduction/flow-state',
      priority: 696,
      preventDuplicates: true
    }),
    codeTemplatesNode: new NavigatorNode({
      parent: gettext('Reference'),
      icon: 'file-text',
      label: gettext('Code templates'),
      path: '/c8y-pkg-dynamic-mapper/introduction/code-templates',
      priority: 695,
      preventDuplicates: true
    }),
    versioningNode: new NavigatorNode({
      parent: gettext('Reference'),
      icon: 'history',
      label: gettext('Versioning mappings'),
      path: '/c8y-pkg-dynamic-mapper/introduction/versioning',
      priority: 694,
      preventDuplicates: true
    }),
    reliabilitySettingsNode: new NavigatorNode({
      parent: gettext('Reference'),
      icon: 'shield',
      label: gettext('Reliability settings'),
      path: '/c8y-pkg-dynamic-mapper/introduction/reliability-settings',
      priority: 693,
      preventDuplicates: true
    }),
    aiAssistedNode: new NavigatorNode({
      parent: gettext('Reference'),
      icon: 'magic',
      label: gettext('AI-assisted mapping'),
      path: '/c8y-pkg-dynamic-mapper/introduction/ai-assisted',
      priority: 692,
      preventDuplicates: true
    }),
    serviceConfigurationNode: new NavigatorNode({
      parent: gettext('Reference'),
      icon: 'cog',
      label: gettext('Service configuration'),
      path: '/c8y-pkg-dynamic-mapper/introduction/service-configuration',
      priority: 691,
      preventDuplicates: true
    }),
    accessControlNode: new NavigatorNode({
      parent: gettext('Reference'),
      icon: 'lock',
      label: gettext('Managing permissions'),
      path: '/c8y-pkg-dynamic-mapper/introduction/access-control',
      priority: 690,
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
      const navs = Object.values(this.staticNodesStandalone) as NavigatorNode[];

      return this.applicationService
        .isAvailable(DocNavigationFactory.APPLICATION_DYNAMIC_MAPPING_SERVICE)
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
