/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
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

/**
 * Snapshot of a mapping-editing session's initial state, taken once when the editor is opened,
 * for comparison against the live editing state via {@link hasMappingContentChanged}.
 */
export interface MappingContentSnapshot {
  mappingJson: string;
  sourceTemplateJson: string;
  targetTemplateJson: string;
  mappingCode: string;
}

/**
 * The mapping as compared by the unsaved-changes check. Two normalisations keep the comparison to
 * what the user can actually change:
 *  - `extension.loaded` and `extension.message` are left out: they report whether the tenant
 *    currently has the extension loaded, and the editor (re)fills them from the tenant when the
 *    extension's event is selected on opening — after the snapshot is taken.
 *  - `null` counts as "not set". The backend returns `null` for unset properties, while the editor
 *    clears the same properties to `undefined` (for example `extension.parameter` when its text
 *    field is initialised empty), which JSON.stringify drops. Counting that made a mapping look
 *    edited the moment it was opened.
 */
function mappingContentJson(mapping: unknown): string {
  const extension = (mapping as { extension?: Record<string, unknown> } | null | undefined)?.extension;
  const comparable =
    extension && typeof extension === 'object' && ('loaded' in extension || 'message' in extension)
      ? (() => {
          const { loaded: _loaded, message: _message, ...rest } = extension;
          return { ...(mapping as object), extension: rest };
        })()
      : mapping;
  return JSON.stringify(comparable, (_key, value) => (value === null ? undefined : value)) ?? 'null';
}

export function captureMappingContentSnapshot(
  mapping: unknown,
  sourceTemplate: unknown,
  targetTemplate: unknown,
  mappingCode: string | undefined
): MappingContentSnapshot {
  return {
    mappingJson: mappingContentJson(mapping),
    sourceTemplateJson: JSON.stringify(sourceTemplate),
    targetTemplateJson: JSON.stringify(targetTemplate),
    mappingCode: mappingCode ?? ''
  };
}

/**
 * Returns true when any mapping content field has changed since {@link captureMappingContentSnapshot}
 * was taken. Shared by the stepper and the unified editor so an UPDATE-mode commit only creates a
 * draft when the user actually changed the mapping's content — a connector-only reassignment (which
 * doesn't touch any of these fields) must not create a no-op draft.
 */
export function hasMappingContentChanged(
  mapping: unknown,
  sourceTemplate: unknown,
  targetTemplate: unknown,
  mappingCode: string | undefined,
  initial: MappingContentSnapshot
): boolean {
  return mappingContentJson(mapping) !== initial.mappingJson
    || JSON.stringify(sourceTemplate) !== initial.sourceTemplateJson
    || JSON.stringify(targetTemplate) !== initial.targetTemplateJson
    || (mappingCode ?? '') !== initial.mappingCode;
}

/**
 * Names what differs between the opening snapshot and the current editing state, for the console
 * when the unsaved-changes prompt appears. Each entry is a mapping property (with its value before
 * and after) or the name of the template/code part that differs.
 */
export function describeMappingContentChanges(
  mapping: unknown,
  sourceTemplate: unknown,
  targetTemplate: unknown,
  mappingCode: string | undefined,
  initial: MappingContentSnapshot
): string[] {
  const differences: string[] = [];
  const before = JSON.parse(initial.mappingJson ?? 'null') ?? {};
  const after = JSON.parse(mappingContentJson(mapping) ?? 'null') ?? {};
  for (const key of new Set([...Object.keys(before), ...Object.keys(after)])) {
    if (JSON.stringify(before[key]) !== JSON.stringify(after[key])) {
      differences.push(`mapping.${key}: ${JSON.stringify(before[key])} -> ${JSON.stringify(after[key])}`);
    }
  }
  if (JSON.stringify(sourceTemplate) !== initial.sourceTemplateJson) differences.push('source template');
  if (JSON.stringify(targetTemplate) !== initial.targetTemplateJson) differences.push('target template');
  if ((mappingCode ?? '') !== initial.mappingCode) differences.push('code');
  return differences;
}
