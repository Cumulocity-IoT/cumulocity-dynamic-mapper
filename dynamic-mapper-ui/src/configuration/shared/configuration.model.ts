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
  outboundExternalIdCacheSize: number;
  outboundExternalIdCacheRetention: number;
  inventoryCacheSize: number;
  inventoryCacheRetention: number;
  flowStateRetention: number;
  mappingVersionRetention?: number;
  inventoryFragmentsToCache?: string[];
  codeTemplates?: any;
  maxCPUTimeMS: number;
  pipelineTimeoutMS?: number;
  jsonataAgent: string;
  javaScriptAgent: string;
  smartFunctionAgent: string;
  suppressDeprecationWarning?: boolean;
  acceptedDeprecationNotice?: string;
  supportESM?: boolean;
  cacheAliasMaps?: boolean;
  externalIdBinding?: boolean;
  explorerSessionTTLMinutes?: number;
  engineRotationThreshold?: number;
  engineMaxAgeMinutes?: number;
  contextPoolSize?: number;
}

export enum TemplateType {
  INBOUND_SMART_FUNCTION = "INBOUND_SMART_FUNCTION",
  OUTBOUND_SMART_FUNCTION = "OUTBOUND_SMART_FUNCTION",
  SHARED = "SHARED",
  SYSTEM = "SYSTEM"
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

/** Placeholder body shown when a template's stored code is not decodable. */
export const UNDECODABLE_TEMPLATE_CODE = '// Code Template not valid!';

/**
 * Decodes one stored template's base64 body, preserving every other field.
 *
 * Both consumers previously rebuilt the object field-by-field, which silently dropped
 * `direction` and hard-coded `defaultTemplate: false`. Because the editor PUTs the decoded object
 * straight back, saving a default template cleared its own `defaultTemplate` flag — and the
 * backend reasons about that flag when deciding whether a newly shipped template of the same type
 * still needs installing. Spreading the source keeps new fields working by default too.
 */
export function decodeCodeTemplate(
  key: string,
  template: CodeTemplate,
  decode: (code: string) => string
): CodeTemplate {
  try {
    return { ...template, id: key, code: decode(template.code) };
  } catch (error) {
    console.error(`Failed to decode code template [${key}]:`, error);
    return { ...template, id: key, code: UNDECODABLE_TEMPLATE_CODE };
  }
}

/** Decodes a whole {@link CodeTemplateMap} into a Map keyed by template id. */
export function decodeCodeTemplates(
  codeTemplates: CodeTemplateMap,
  decode: (code: string) => string
): Map<string, CodeTemplate> {
  const decoded = new Map<string, CodeTemplate>();
  Object.entries(codeTemplates ?? {}).forEach(([key, template]) => {
    decoded.set(key, decodeCodeTemplate(key, template, decode));
  });
  return decoded;
}

const TEMPLATE_TYPE_LOOKUP = new Map<string, TemplateType>([
  [`${Direction.INBOUND}_${TransformationType.SMART_FUNCTION}`, TemplateType.INBOUND_SMART_FUNCTION],
  [`${Direction.OUTBOUND}_${TransformationType.SMART_FUNCTION}`, TemplateType.OUTBOUND_SMART_FUNCTION],
]);

/**
 * Non-throwing companion to {@link toTemplateType}. Code templates exist only for Smart
 * Functions, so most transformation types have no TemplateType. Use this at call sites that
 * can react to an unsupported combination — UI entry points reachable from a template
 * condition, for instance — rather than crash on one.
 */
export function tryToTemplateType(
  direction: Direction,
  transformationType: TransformationType
): TemplateType | undefined {
  return TEMPLATE_TYPE_LOOKUP.get(`${direction}_${transformationType}`);
}

/**
 * Throws when the combination has no TemplateType. Appropriate where reaching an unsupported
 * combination is a programming error; prefer {@link tryToTemplateType} where it is merely
 * possible user/config state.
 */
export function toTemplateType(direction: Direction, transformationType: TransformationType): TemplateType {
  const templateType = tryToTemplateType(direction, transformationType);
  if (!templateType) {
    throw new Error(`No TemplateType mapping for direction='${direction}' transformationType='${transformationType}'`);
  }
  return templateType;
}
