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
  staticNodesStandalone = {
    rootNode: new NavigatorNode({
      label: gettext('Introduction'),
      icon: 'home',
      path: '/c8y-pkg-dynamic-mapper/landing',
      priority: 614,
      preventDuplicates: true
    }),
    overviewNode: new NavigatorNode({
      parent: gettext('Introduction'),
      icon: 'empty',
      label: gettext('Overview'),
      path: '/c8y-pkg-dynamic-mapper/landing/overview',
      priority: 613,
      preventDuplicates: true
    }),
    gettingStartedNode: new NavigatorNode({
      parent: gettext('Introduction'),
      icon: 'empty',
      label: gettext('Getting started'),
      path: '/c8y-pkg-dynamic-mapper/landing/getting-started',
      priority: 612,
      preventDuplicates: true
    }),
    managingConnectorsNode: new NavigatorNode({
      parent: gettext('Introduction'),
      icon: 'empty',
      label: gettext('Managing connectors'),
      path: '/c8y-pkg-dynamic-mapper/landing/managing-connectors',
      priority: 611,
      preventDuplicates: true
    }),
    definingMappingNode: new NavigatorNode({
      parent: gettext('Introduction'),
      icon: 'empty',
      label: gettext('Defining a mapping'),
      path: '/c8y-pkg-dynamic-mapper/landing/define-mapping',
      priority: 610,
      preventDuplicates: true
    }),
    subscriptionOutboundNode: new NavigatorNode({
      parent: gettext('Introduction'),
      icon: 'empty',
      label: gettext('Outbound mapping'),
      path: '/c8y-pkg-dynamic-mapper/landing/define-subscription-for-outbound',
      priority: 609,
      preventDuplicates: true
    }),
    transformationTypesNode: new NavigatorNode({
      parent: gettext('Introduction'),
      icon: 'empty',
      label: gettext('Transformation Types'),
      path: '/c8y-pkg-dynamic-mapper/landing/transformation-types',
      priority: 608,
      preventDuplicates: true
    }),
    jsonNataNode: new NavigatorNode({
      parent: gettext('Transformation Types'),
      icon: 'empty',
      label: gettext('JSONata'),
      path: '/c8y-pkg-dynamic-mapper/landing/jsonata',
      priority: 607,
      preventDuplicates: true
    }),
    smartFunctionNode: new NavigatorNode({
      parent: gettext('Transformation Types'),
      icon: 'empty',
      label: gettext('Smart Function'),
      path: '/c8y-pkg-dynamic-mapper/landing/smartfunction',
      priority: 606,
      preventDuplicates: true
    }),
    javaScriptNode: new NavigatorNode({
      parent: gettext('Transformation Types'),
      icon: 'empty',
      label: gettext('Substitution as JavaScript'),
      path: '/c8y-pkg-dynamic-mapper/landing/javascript',
      priority: 605,
      preventDuplicates: true
    }),
    codeTemplatesNode: new NavigatorNode({
      parent: gettext('Introduction'),
      icon: 'empty',
      label: gettext('Code Templates'),
      path: '/c8y-pkg-dynamic-mapper/landing/code-templates',
      priority: 604,
      preventDuplicates: true
    }),
    metadataNode: new NavigatorNode({
      parent: gettext('Introduction'),
      icon: 'empty',
      label: gettext('Metadata'),
      path: '/c8y-pkg-dynamic-mapper/landing/metadata',
      priority: 603,
      preventDuplicates: true
    }),
    unknownPayloadNode: new NavigatorNode({
      parent: gettext('Introduction'),
      icon: 'empty',
      label: gettext('Snooping'),
      path: '/c8y-pkg-dynamic-mapper/landing/unknown-payload',
      priority: 602,
      preventDuplicates: true
    }),
    reliabilitySettingsNode: new NavigatorNode({
      parent: gettext('Introduction'),
      icon: 'empty',
      label: gettext('Reliability settings'),
      path: '/c8y-pkg-dynamic-mapper/landing/reliability-settings',
      priority: 601,
      preventDuplicates: true
    }),
    accessControlNode: new NavigatorNode({
      parent: gettext('Introduction'),
      icon: 'empty',
      label: gettext('Managing permissions'),
      path: '/c8y-pkg-dynamic-mapper/landing/access-control',
      priority: 600,
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
      //   console.log(
      //     'Constructor: AppName in MappingNavigationFactory',
      //     cur,
      //     this.isStandaloneApp,
      //     _.has(cur?.manifest, 'isPackage')
      //   );
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
