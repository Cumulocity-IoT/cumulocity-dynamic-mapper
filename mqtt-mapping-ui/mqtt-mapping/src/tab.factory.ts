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
import { Injectable } from "@angular/core";
import { TabFactory, Tab } from "@c8y/ngx-components";
import { Router } from "@angular/router";
import { BrokerConfigurationService } from "./mqtt-configuration/broker-configuration.service";
import { Feature } from "./shared/mapping.model";

@Injectable()
export class MappingTabFactory implements TabFactory {
  _feature: Feature;
  constructor(
    public router: Router,
    private configurationService: BrokerConfigurationService
  ) {}

  async get() {
    //console.log("MappingTabFactory",this.router.url, this.router.url.match(/sag-ps-pkg-mqtt-mapping/g));
    //console.log("Feature: ", this._feature)
    if (!this._feature) {
      this._feature = await this.configurationService.getFeatures();
      //console.log("Feature reload: ", this._feature)
    }

    const tabs: Tab[] = [];
    if (this.router.url.match(/sag-ps-pkg-mqtt-mapping/g)) {
      if (this._feature.userHasMappingAdminRole) {
        tabs.push({
          path: "sag-ps-pkg-mqtt-mapping/configuration",
          priority: 930,
          label: "Configuration",
          icon: "cog",
          orientation: "horizontal",
        } as Tab);
      }
      tabs.push({
        path: "sag-ps-pkg-mqtt-mapping/mappings/inbound",
        priority: 920,
        label: "Mapping Inbound",
        icon: "swipe-right",
        orientation: "horizontal",
      } as Tab);
      this.configurationService.getFeatures();
      if (this._feature.outputMappingEnabled) {
        tabs.push({
          path: "sag-ps-pkg-mqtt-mapping/mappings/outbound",
          priority: 920,
          label: "Mapping Outbound",
          icon: "swipe-left",
          orientation: "horizontal",
        } as Tab);
      }
      tabs.push({
        path: "sag-ps-pkg-mqtt-mapping/monitoring",
        priority: 910,
        label: "Monitoring",
        icon: "monitoring",
        orientation: "horizontal",
      } as Tab);
      tabs.push({
        path: "sag-ps-pkg-mqtt-mapping/testing",
        priority: 900,
        label: "Test Devices",
        icon: "reflector-bulb",
        orientation: "horizontal",
      } as Tab);
      tabs.push({
        path: "sag-ps-pkg-mqtt-mapping/tree",
        priority: 890,
        label: "Mapping Tree Inbound",
        icon: "tree-structure",
        orientation: "horizontal",
      } as Tab);
      if (this._feature.userHasMappingAdminRole) {
        tabs.push({
          path: "sag-ps-pkg-mqtt-mapping/extensions",
          priority: 880,
          label: "Processor Extension",
          icon: "plugin",
          orientation: "horizontal",
        } as Tab);
      }

      // this tab is used to develop the migration from json library:
      //     "vanilla-jsoneditor": "^0.17.9"
      // to 
      //     "jsoneditor": "^9.9.2"
      // Do NOT DELETE

      // tabs.push({
      //   path: "sag-ps-pkg-mqtt-mapping/editor2-test",
      //   priority: 870,
      //   label: "Editor2",
      //   icon: "file",
      //   orientation: "horizontal",
      // } as Tab);
      
    }

    return tabs;
  }
}
