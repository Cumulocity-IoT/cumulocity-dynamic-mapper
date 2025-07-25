import { internalApps } from "@c8y/ngx-components";

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
export interface ServiceConfiguration {
  logPayload: boolean;
  logSubstitution: boolean;
  logConnectorErrorInBackend: boolean;
  sendConnectorLifecycle: boolean;
  sendMappingStatus: boolean;
  sendSubscriptionEvents: boolean;
  sendNotificationLifecycle: boolean;
  externalExtensionEnabled?: boolean;
  outboundMappingEnabled: boolean;
  inboundExternalIdCacheSize: number;
  inboundExternalIdCacheRetention: number;
  inventoryCacheSize: number;
  inventoryCacheRetention: number;
  inventoryFragmentsToCache?: string[];
  codeTemplates?: any;
  maxCPUTimeMS: number;
}

export enum TemplateType {
  INBOUND = "INBOUND",
  OUTBOUND = "OUTBOUND",
  SHARED = "SHARED",
  SYSTEM = "SYSTEM"
}

export interface CodeTemplate {
  id: string;
  name: string;
  description?: string;
  templateType: TemplateType;
  code: string;
  internal: boolean;
  readonly: boolean;
  defaultTemplate: boolean;
}

export interface CodeTemplateMap {
  [key: string]: CodeTemplate;
}
