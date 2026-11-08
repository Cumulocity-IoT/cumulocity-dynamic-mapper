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
import { Direction, Mapping, MappingType } from './mapping.model';
import type { CodeTemplate } from '../configuration/configuration.model';

// ─── String / template utilities ─────────────────────────────────────────────

/**
 * Marks the end of the auto-generated system section of a CodeTemplate's JSDoc
 * header (@name, @description, @templateType, @direction, @defaultTemplate,
 * @internal, @readonly). Must match
 * `ServiceConfigurationService.SYSTEM_SECTION_MARKER` on the backend, which
 * generates and rewrites this section on every template save
 * (`rectifyHeaderInCodeTemplate`) — keep the two in sync.
 */
const TEMPLATE_SYSTEM_SECTION_MARKER =
  '--- metadata above is auto-generated, add your documentation below ---';

/**
 * Removes the auto-generated system-metadata section of a template's JSDoc
 * header from a code string, so template-only concepts (@name, @templateType,
 * @direction, ...) aren't carried over when the template is used as the basis
 * for a mapping's code. Any free-form documentation the template author wrote
 * below the marker (e.g. a sample payload) is preserved.
 *
 * Templates fetched from the backend always contain the marker (it's written
 * on every create/update), but this falls back to stripping the individual
 * system tags for any pre-migration data that doesn't have it yet.
 */
export function stripTemplateMetadataTags(code: string): string {
  if (!code) return code;

  const markerIndex = code.indexOf(TEMPLATE_SYSTEM_SECTION_MARKER);
  if (markerIndex !== -1) {
    const blockStart = code.lastIndexOf('/**', markerIndex);
    const markerLineEnd = code.indexOf('\n', markerIndex);
    // The marker sits inside the JSDoc block, so the next `*/` closes that same block.
    const blockEnd = code.indexOf('*/', markerIndex);
    if (blockStart !== -1 && markerLineEnd !== -1 && blockEnd !== -1) {
      const before = code.slice(0, blockStart);
      const after = code.slice(blockEnd + 2);
      // Drop leading empty continuation lines left behind by the removed system section.
      const documentation = code
        .slice(markerLineEnd + 1, blockEnd)
        .replace(/^(?:[ \t]*\*[ \t]*\n)+/, '');

      // The system section owns the block's `/**` opener. Simply cutting up to the marker line
      // would delete that opener and leave the author's documentation as a dangling `* ... */`,
      // which is a syntax error — so re-open the block whenever there is documentation to keep,
      // and remove the block entirely when there is not.
      const hasDocumentation = documentation.replace(/^[ \t]*\*?/gm, '').trim() !== '';
      const rebuilt = hasDocumentation
        ? `${before}/**\n${documentation}*/${after}`
        : before + after;
      return rebuilt.replace(/^\n+/, '');
    }
  }

  // Legacy fallback: no marker present, strip the tags most likely to conflict.
  return code.replace(/^\s*\*\s*@(?:name|description|templateType|direction|defaultTemplate|internal|readonly)\b[^\n]*\n?/gm, '');
}

/**
 * Returns true when `code` already exports `exportName` as an ESM named or direct export (e.g.
 * `export { onMessage };` or `export function onMessage(...)`), so callers don't append a
 * duplicate export statement. Handles `export default` alongside the other direct-export forms.
 * Shared by the stepper and the unified editor's code-template insertion — these previously
 * carried two independently forked copies of this regex pair that had already drifted (the
 * unified editor's matched `export default`, the stepper's didn't).
 */
export function hasEsmExport(code: string, exportName: string): boolean {
  const escapedName = exportName.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const namedExportPattern = new RegExp(`\\bexport\\s*\\{[^}]*\\b${escapedName}\\b(?:\\s+as\\s+\\w+)?[^}]*\\}`, 'm');
  const directExportPattern = new RegExp(
    `\\bexport\\s+(?:default\\s+)?(?:async\\s+function|function|const|let|var|class)\\s+${escapedName}\\b`,
    'm'
  );
  return namedExportPattern.test(code) || directExportPattern.test(code);
}

/**
 * Seeds a new mapping's sample message from the code template it was created from.
 *
 * A code template declares the message it is written for (`samplePayload`, `sampleTopic`). Without
 * this the stepper substitutes a generic per-target sample for the empty source, and the first
 * dry run of the template's own code fails on fields that sample does not have.
 *
 * The sample is the source template (the broker message for INBOUND, the Cumulocity message for
 * OUTBOUND) and the matching topic sample. Only JSON mappings take it: the sample is a JSON
 * document, and other mapping types (raw bytes, flat file, ...) shape their source differently.
 * Fields the template does not declare are left untouched.
 */
export function applyCodeTemplateSample(
  mapping: Mapping,
  codeTemplate: CodeTemplate | undefined,
  mappingType: MappingType,
  direction: Direction
): void {
  if (!codeTemplate || mappingType !== MappingType.JSON) {
    return;
  }
  const { samplePayload, sampleTopic } = codeTemplate;
  if (samplePayload) {
    mapping.sourceTemplate = samplePayload;
  }
  if (sampleTopic) {
    if (direction === Direction.INBOUND) {
      mapping.mappingTopicSample = sampleTopic;
    } else {
      mapping.publishTopicSample = sampleTopic;
    }
  }
}
