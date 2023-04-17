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
import { ApplicationService } from "@c8y/client";
import {
  gettext,
  NavigatorNode,
  NavigatorNodeFactory,
} from "@c8y/ngx-components";

@Injectable()
export class MappingNavigationFactory implements NavigatorNodeFactory {
  private static readonly APPLICATION_MQTT_GENERIC = "mqtt-mapping-service";

  private readonly NAVIGATION_NODE_MQTT = new NavigatorNode({
    parent: gettext("Settings"),
    label: gettext("MQTT Mapping"),
    icon: "ftp-server",
    path: "/mqtt-mapping/mappings/inbound",
    priority: 99,
    preventDuplicates: true,
  });

  constructor(private applicationService: ApplicationService) {}

  get() {
    return this.applicationService
      .isAvailable(MappingNavigationFactory.APPLICATION_MQTT_GENERIC)
      .then((result) => {
        if (!(result && result.data)) {
          console.error("MQTT Generic Microservice not subscribed!");
          return [];
        }
        //console.log('navigation node: ', this.NAVIGATION_NODE_MQTT);
        return this.NAVIGATION_NODE_MQTT;
      });
  }
}
