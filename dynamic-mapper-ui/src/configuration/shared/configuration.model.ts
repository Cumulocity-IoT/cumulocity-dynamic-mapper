import { Direction, TransformationType } from "../../shared";

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
  deviceIsolationMQTTServiceEnabled: boolean;
  inboundExternalIdCacheSize: number;
  inboundExternalIdCacheRetention: number;
  inventoryCacheSize: number;
  inventoryCacheRetention: number;
  flowStateRetention: number;
  inventoryFragmentsToCache?: string[];
  codeTemplates?: any;
  maxCPUTimeMS: number;
  jsonataAgent: string;
  javaScriptAgent: string;
  smartFunctionAgent: string;
  suppressDeprecationWarning?: boolean;
  acceptedDeprecationNotice?: string;
  supportESM?: boolean;
}

export enum TemplateType {
  INBOUND = "INBOUND", // deprecated, use INBOUND_SUBSTITUTION_AS_CODE instead
  OUTBOUND = "OUTBOUND", // deprecated, use OUTBOUND_SUBSTITUTION_AS_CODE instead
  INBOUND_SUBSTITUTION_AS_CODE = "INBOUND_SUBSTITUTION_AS_CODE",
  OUTBOUND_SUBSTITUTION_AS_CODE = "OUTBOUND_SUBSTITUTION_AS_CODE",
  SHARED = "SHARED",
  SYSTEM = "SYSTEM",
  INBOUND_SMART_FUNCTION = "INBOUND_SMART_FUNCTION",
  OUTBOUND_SMART_FUNCTION = "OUTBOUND_SMART_FUNCTION"
}

export interface CodeTemplate {
  id: string;
  name: string;
  description?: string;
  templateType: TemplateType;
  direction?: Direction;
  code: string;
  internal: boolean;
  readonly: boolean;
  defaultTemplate: boolean;
}

export interface CodeTemplateMap {
  [key: string]: CodeTemplate;
}

const TEMPLATE_TYPE_LOOKUP = new Map<string, TemplateType>([
  [`${Direction.INBOUND}_${TransformationType.SUBSTITUTION_AS_CODE}`, TemplateType.INBOUND_SUBSTITUTION_AS_CODE],
  [`${Direction.OUTBOUND}_${TransformationType.SUBSTITUTION_AS_CODE}`, TemplateType.OUTBOUND_SUBSTITUTION_AS_CODE],
  [`${Direction.INBOUND}_${TransformationType.SMART_FUNCTION}`, TemplateType.INBOUND_SMART_FUNCTION],
  [`${Direction.OUTBOUND}_${TransformationType.SMART_FUNCTION}`, TemplateType.OUTBOUND_SMART_FUNCTION],
]);

export function toTemplateType(direction: Direction, transformationType: TransformationType): TemplateType {
  const key = `${direction}_${transformationType}`;
  const templateType = TEMPLATE_TYPE_LOOKUP.get(key);
  if (!templateType) {
    throw new Error(`No TemplateType mapping for direction='${direction}' transformationType='${transformationType}'`);
  }
  return templateType;
}
