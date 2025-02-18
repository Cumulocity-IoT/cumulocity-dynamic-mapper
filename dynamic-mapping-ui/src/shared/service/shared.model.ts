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
export enum Operation {
  ACTIVATE_MAPPING = 'ACTIVATE_MAPPING',
  CONNECT = 'CONNECT',
  DISCONNECT = 'DISCONNECT',
  REFRESH_STATUS_MAPPING = 'REFRESH_STATUS_MAPPING',
  RELOAD_EXTENSIONS = 'RELOAD_EXTENSIONS',
  RELOAD_MAPPINGS = 'RELOAD_MAPPINGS',
  RESET_STATUS_MAPPING = 'RESET_STATUS_MAPPING',
  REFRESH_NOTIFICATIONS_SUBSCRIPTIONS = 'REFRESH_NOTIFICATIONS_SUBSCRIPTIONS',
  DEBUG_MAPPING = 'DEBUG_MAPPING',
  SNOOP_MAPPING = 'SNOOP_MAPPING',
  SNOOP_RESET = 'SNOOP_RESET',
  RESET_DEPLOYMENT_MAP = 'RESET_DEPLOYMENT_MAP',
  CLEAR_CACHE = 'CLEAR_CACHE',
  APPLY_MAPPING_FILTER = 'APPLY_MAPPING_FILTER',
  UPDATE_TEMPLATE = 'UPDATE_TEMPLATE',

}

export class ServiceOperation{
  tenant?: string;
  operation:Operation;
  parameter?: any
}