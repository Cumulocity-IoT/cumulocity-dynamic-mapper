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
import * as jsYaml from 'js-yaml';
import { DeploymentMapEntry, Mapping } from './mapping.model';
import type { JsonEditorComponent } from '../component/json-editor/json-editor.component';
import { stripTemplateMetadataTags } from './code-template.util';
import { stringToBase64 } from './encoding.util';

// ─── Mapping editor session utilities ──────────────────────────────────────────
// Shared by the stepper (mapping-stepper.component.ts) and the unified editor
// (mapping-unified-editor.component.ts), which independently duplicated these until a review
// found ~90% method-level duplication between the two components. See
// docs/planning/IMPLEMENTATION-PLAN-STEPPER-UNIFIED-EDITOR-DEDUP.md.

/** Serializes an extension parameter block to YAML for display in the parameter textarea. */
export function configurationToYaml(configuration: Record<string, any> | undefined): string {
  if (!configuration) {
    return '';
  }
  try {
    return jsYaml.dump(configuration, { indent: 2 });
  } catch {
    return '';
  }
}

/** Parses the extension parameter textarea's YAML back into an object. */
export function yamlToConfiguration(yaml: string): Record<string, any> | undefined {
  if (!yaml?.trim()) {
    return undefined;
  }
  try {
    const parsed = jsYaml.load(yaml);
    return (parsed && typeof parsed === 'object') ? parsed as Record<string, any> : undefined;
  } catch {
    return undefined;
  }
}

/** Builds the mapping snapshot sent to the Testing step / drawer, optionally including the encoded code. */
export function buildTestMapping(
  mapping: Mapping,
  sourceTemplate: any,
  targetTemplate: any,
  mappingCode: string | undefined,
  includeCode: boolean
): Mapping {
  const testMapping = structuredClone(mapping);
  // The editors load their templates asynchronously; a quick switch to the testing tab can arrive first.
  // JSON.stringify(undefined) is undefined, so keep the persisted template until the editor has one.
  if (sourceTemplate !== undefined) testMapping.sourceTemplate = JSON.stringify(sourceTemplate);
  if (targetTemplate !== undefined) testMapping.targetTemplate = JSON.stringify(targetTemplate);
  if (includeCode && mappingCode) {
    testMapping.code = stringToBase64(stripTemplateMetadataTags(mappingCode));
  }
  return testMapping;
}

/** A mapping must be bound to at least one connector before it can be saved. */
export function isConnectorSelectionEmpty(deploymentMapEntry: DeploymentMapEntry | undefined): boolean {
  return !deploymentMapEntry?.connectors || deploymentMapEntry.connectors.length === 0;
}

/**
 * What {@link updateTemplatesInEditors} needs from the template step (`MappingTemplateStepComponent`):
 * the templates as the user currently has them, or `undefined` when the editors are not there.
 * Narrowed to two methods so this util neither reaches into the step's editors nor imports it.
 */
export interface TemplateEditorRef {
  getSourceTemplate(): any;
  getTargetTemplate(): any;
}

/**
 * Reads content directly from the underlying vanilla-jsoneditor instance, bypassing the
 * `(contentChanged)` mirror (`sourceTemplateUpdated`/`targetTemplateUpdated`). That mirror only
 * updates when the library's `onChange` fires, which in practice doesn't fire for every edit
 * path (e.g. some tree-mode interactions) — `.get()` is the library's own source of truth and
 * is never stale.
 */
export function tryGetLiveEditorContent(editor: JsonEditorComponent | undefined): any {
  if (!editor) return undefined;
  try {
    return editor.get();
  } catch (error) {
    console.warn('Failed to read live editor content, falling back', error);
    return undefined;
  }
}

/**
 * Pulls the latest source/target template values out of the template step before a save or a
 * step/tab transition away from it, falling back to the host's current value when the step is not
 * rendered. The step answers with the full template, hidden metadata keys restored.
 */
export function updateTemplatesInEditors(
  templateStepRef: TemplateEditorRef | undefined,
  currentSourceTemplate: any,
  currentTargetTemplate: any
): { sourceTemplate: any; targetTemplate: any } {
  return {
    sourceTemplate: templateStepRef?.getSourceTemplate() ?? currentSourceTemplate,
    targetTemplate: templateStepRef?.getTargetTemplate() ?? currentTargetTemplate
  };
}
