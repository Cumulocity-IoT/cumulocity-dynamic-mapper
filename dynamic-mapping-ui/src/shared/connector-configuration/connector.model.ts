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

import { inject } from "@angular/core";
import { ResolveFn } from "@angular/router";
import { ConnectorConfigurationService } from "../service/connector-configuration.service";

export enum ConnectorPropertyType {
  ID_STRING_PROPERTY = 'ID_STRING_PROPERTY',
  STRING_PROPERTY = 'STRING_PROPERTY',
  SENSITIVE_STRING_PROPERTY = 'SENSITIVE_STRING_PROPERTY',
  NUMERIC_PROPERTY = 'NUMERIC_PROPERTY',
  BOOLEAN_PROPERTY = 'BOOLEAN_PROPERTY',
  OPTION_PROPERTY = 'OPTION_PROPERTY',
  STRING_LARGE_PROPERTY = 'STRING_LARGE_PROPERTY'
}

export enum ConnectorType {
  MQTT = 'MQTT',
  CUMULOCITY_MQTT_SERVICE = 'CUMULOCITY_MQTT_SERVICE',
  KAFKA = 'KAFKA'
}
export interface ConnectorProperty {
  required: boolean;
  order: number;
  readonly: boolean;
  hidden: boolean;
  defaultValue?: any;
  type: ConnectorPropertyType;
}

export interface ConnectorConfiguration {
  identifier: string;
  connectorType: string;
  enabled: boolean;
  status?: any;
  status$?: any;
  name: string;
  properties: { [name: string]: any };
}

export interface ConnectorSpecification {
  name: string;
  description: string;
  connectorType: string;
  supportsWildcardInTopic: boolean;
  properties: { [name: string]: ConnectorProperty };
}

export const connectorResolver: ResolveFn<ConnectorConfiguration> = (route) => {
  const connectorConfigurationService = inject(ConnectorConfigurationService);
  const identifier = route.paramMap.get('identifier');
  return connectorConfigurationService.getConnectorConfiguration(identifier);
};
