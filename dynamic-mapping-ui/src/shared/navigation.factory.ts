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
import {
  AlertService,
  AppStateService,
  gettext,
  NavigatorNode,
  NavigatorNodeFactory
} from '@c8y/ngx-components';
import { SharedService } from './shared.service';
import { NODE1, NODE2, NODE3 } from './model/util';
import { Router } from '@angular/router';

@Injectable()
export class MappingNavigationFactory implements NavigatorNodeFactory {
  private static readonly APPLICATION_DYNAMIC_MAPPING_SERVICE =
    'dynamic-mapping-service';

  appName: string;
  isPackage: boolean = false;

  constructor(
    private applicationService: ApplicationService,
    private alertService: AlertService,
    private sharedService: SharedService,
    private appStateService: AppStateService,
    public router: Router
  ) {
    this.appStateService.currentApplication.subscribe((cur) => {
      console.log('AppName in MappingNavigationFactory', cur, this.router.url);
      if (cur?.manifest?.isPackage) {
        this.isPackage = cur?.manifest?.isPackage;
      }
      this.appName = cur.name;
    });
  }

  get() {
    let navs;
    if (this.isPackage) {
      const parentMapping = new NavigatorNode({
        label: gettext('Home'),
        icon: 'home',
        path: '/sag-ps-pkg-dynamic-mapping/landing',
        priority: 600,
        preventDuplicates: true
      });
      const mappingConfiguration = new NavigatorNode({
        label: gettext('Configuration'),
        icon: 'cog',
        path: `/sag-ps-pkg-dynamic-mapping/${NODE3}/connectorConfiguration`,
        priority: 500,
        preventDuplicates: true
      });
      const mapping = new NavigatorNode({
        label: gettext('Mapping'),
        icon: 'file-type-document',
        path: `/sag-ps-pkg-dynamic-mapping/${NODE1}/mappings/inbound`,
        priority: 400,
        preventDuplicates: true
      });
      const mappingMonitoring = new NavigatorNode({
        label: gettext('Service monitoring'),
        icon: 'pie-chart',
        path: `/sag-ps-pkg-dynamic-mapping/${NODE2}/monitoring/grid`,
        priority: 300,
        preventDuplicates: true
      });
      navs = [parentMapping, mapping, mappingMonitoring, mappingConfiguration];
    } else {
      const parentMapping = new NavigatorNode({
        label: gettext('Dynamic Data Mapper'),
        icon: 'compare',
        path: '/sag-ps-pkg-dynamic-mapping/landing',
        priority: 99,
        preventDuplicates: true
      });
      const mappingConfiguration = new NavigatorNode({
        parent: gettext('Dynamic Data Mapper'),
        label: gettext('Configuration'),
        icon: 'cog',
        path: `/sag-ps-pkg-dynamic-mapping/${NODE3}/connectorConfiguration`,
        priority: 500,
        preventDuplicates: true
      });
      const mapping = new NavigatorNode({
        parent: gettext('Dynamic Data Mapper'),
        label: gettext('Mapping'),
        icon: 'file-type-document',
        path: `/sag-ps-pkg-dynamic-mapping/${NODE1}/mappings/inbound`,
        priority: 400,
        preventDuplicates: true
      });
      const mappingMonitoring = new NavigatorNode({
        parent: gettext('Dynamic Data Mapper'),
        label: gettext('Service monitoring'),
        icon: 'pie-chart',
        path: `/sag-ps-pkg-dynamic-mapping/${NODE2}/monitoring/grid`,
        priority: 300,
        preventDuplicates: true
      });
      navs = [parentMapping, mapping, mappingMonitoring, mappingConfiguration];
    }

    const feature: any = this.sharedService.getFeatures();
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
