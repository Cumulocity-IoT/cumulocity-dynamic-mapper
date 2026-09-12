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

import { inject } from "@angular/core";
import { ResolveFn } from "@angular/router";
import { AlertService } from "@c8y/ngx-components";
import { gettext } from "@c8y/ngx-components/gettext";
import { HttpStatusCode } from "@angular/common/http";
import { ConnectorConfigurationService } from "../service/connector-configuration.service";
import { SharedService } from "../service/shared.service";
import { Operation } from "../service/shared.model";
import { Direction } from "../mapping/mapping.model";
import { ALERT_INFO_TIMEOUT } from "../mapping/util";

export enum ConnectorPropertyType {
  ID_STRING_PROPERTY = 'ID_STRING_PROPERTY',
  STRING_PROPERTY = 'STRING_PROPERTY',
  SENSITIVE_STRING_PROPERTY = 'SENSITIVE_STRING_PROPERTY',
  NUMERIC_PROPERTY = 'NUMERIC_PROPERTY',
  BOOLEAN_PROPERTY = 'BOOLEAN_PROPERTY',
  OPTION_PROPERTY = 'OPTION_PROPERTY',
  STRING_LARGE_PROPERTY = 'STRING_LARGE_PROPERTY',
  SENSITIVE_STRING_LARGE_PROPERTY = 'SENSITIVE_STRING_LARGE_PROPERTY',
  MAP_PROPERTY = 'MAP_PROPERTY'
}

export enum ConnectorType {
  MQTT = 'MQTT',
  CUMULOCITY_MQTT_SERVICE = 'CUMULOCITY_MQTT_SERVICE',
  KAFKA = 'KAFKA',
  HTTP = 'HTTP',
  WEB_HOOK = 'WEB_HOOK',
  WEB_HOOK_INTERNAL = 'WEB_HOOK_INTERNAL',
  PULSAR = 'PULSAR',
  CUMULOCITY_MQTT_SERVICE_PULSAR = 'CUMULOCITY_MQTT_SERVICE_PULSAR',
  AMQP_091 = 'AMQP_091',
  AMQP_10 = 'AMQP_10',
  GOOGLE_PUBSUB = 'GOOGLE_PUBSUB',
  TEST = 'TEST',
}

export interface ConnectorPropertyCondition {
  // order: number;
  key: string;
  anyOf: string[];
}
export interface ConnectorProperty {
  description: string;
  required: boolean;
  order: number;
  readonly: boolean;
  hidden: boolean;
  defaultValue?: any;
  type: ConnectorPropertyType;
  options?: { [key: string]: string };
  condition?: ConnectorPropertyCondition;
}

export interface ConnectorConfiguration {
  identifier: string;
  connectorType: ConnectorType;
  enabled: boolean;
  status?: any;
  status$?: any;
  supportedDirections?: Direction[];
  name: string;
  properties: { [name: string]: any };
}
export interface ConnectorSpecification {
  name: string;
  description: string;
  connectorType: ConnectorType;
  singleton: boolean;
  supportsMessageContext?: boolean;
  supportedDirections?: Direction[];
  properties: { [name: string]: ConnectorProperty };
}

export const connectorResolver: ResolveFn<ConnectorConfiguration> = (route) => {
  const connectorConfigurationService = inject(ConnectorConfigurationService);
  const identifier = route.paramMap.get('identifier');
  return connectorConfigurationService.getConfiguration(identifier);
};

export interface PollingInterval {
  label: string;
  value: number;
  seconds: number;
}

/** The subset of {@link ConnectorConfiguration} the create/update API accepts — identifier is
 * always populated (either from the resolved configuration being edited, or from
 * createCustomUuid() for a new one), unlike the rest of the fields. */
export type ConnectorConfigurationApiPayload =
  Partial<ConnectorConfiguration> & Pick<ConnectorConfiguration, 'identifier'>;

/** Strips a {@link ConnectorConfiguration} down to the fields the create/update API accepts. */
export function prepareConnectorConfigurationForApi(config: ConnectorConfiguration): ConnectorConfigurationApiPayload {
  return {
    identifier: config.identifier,
    connectorType: config.connectorType,
    enabled: config.enabled,
    name: config.name,
    properties: config.properties
  };
}

/**
 * Sends the connect/disconnect operation for a connector and surfaces a success/error toast.
 * Extracted here because the connector grid's cell renderer and the connector details page
 * previously each carried their own copy of this logic, with drift between them (only the
 * renderer disabled its toggle while the operation was in flight) — callers should still guard
 * against concurrent re-invocation themselves (see {@link ConnectorStatusEnabledRendererComponent}
 * for the `isLoading` pattern).
 */
export async function toggleConnectorConnection(
  sharedService: SharedService,
  alertService: AlertService,
  configuration: ConnectorConfiguration
): Promise<boolean> {
  const isConnecting = !configuration.enabled;
  const response = await sharedService.runOperation(
    configuration.enabled
      ? { operation: Operation.DISCONNECT, parameter: { connectorIdentifier: configuration.identifier } }
      : { operation: Operation.CONNECT, parameter: { connectorIdentifier: configuration.identifier } }
  );
  const queued = response.status === HttpStatusCode.Created;
  if (queued) {
    alertService.add({
      text: isConnecting
        ? gettext('Connector is connecting, please wait...')
        : gettext('Connector disconnected.'),
      type: 'info',
      timeout: ALERT_INFO_TIMEOUT
    });
  } else {
    alertService.danger(gettext('Failed to establish connection!'));
  }
  return queued;
}

/**
 * Awaits a drawer's `result` promise, treating a rejection (the drawer rejects with a reason
 * string on Cancel — see e.g. ConnectorConfigurationDrawerComponent.onCancel) as "nothing to
 * apply" instead of letting it surface as an unhandled promise rejection. Every drawer-opening
 * call site in this feature awaited `drawer.instance.result` directly with no try/catch, so
 * clicking Cancel on any connector create/edit/copy/view drawer threw an unhandled rejection
 * every time — harmless in practice (there was nothing to do with a cancel anyway) but a real,
 * constantly-reproducible error logged on every single cancel.
 */
export async function awaitDrawerResult<T>(result: Promise<T>): Promise<T | undefined> {
  try {
    return await result;
  } catch {
    return undefined;
  }
}

/**
 * Applies a connector-configuration drawer's result (create/update/delete) and refreshes the
 * shared configurations list — the common tail both ConnectorGridComponent and
 * ConnectorDetailsComponent used to duplicate as their own private `handleModalResponse`
 * (identical apart from which locally-named method they called to trigger the refresh, both of
 * which just called `connectorConfigurationService.refreshConfigurations()` anyway).
 * The refresh always runs, even for a no-op (`response` falsy, e.g. the user cancelled the
 * drawer) — matching those methods' prior behavior of unconditionally refreshing afterward.
 */
export async function applyConnectorConfigurationChange(
  alertService: AlertService,
  connectorConfigurationService: ConnectorConfigurationService,
  response: ConnectorConfiguration | undefined | null,
  successMessage: string,
  errorMessage: string,
  action: (config: ConnectorConfigurationApiPayload) => Promise<{ status: number }>
): Promise<void> {
  if (response) {
    const clonedConfiguration = prepareConnectorConfigurationForApi(response);
    const apiResponse = await action(clonedConfiguration);

    if (apiResponse.status < 300) {
      alertService.success(gettext(successMessage));
    } else {
      alertService.danger(gettext(errorMessage));
    }
  }
  connectorConfigurationService.refreshConfigurations();
}
