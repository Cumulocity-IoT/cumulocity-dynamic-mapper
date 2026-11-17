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
import { gettext } from '@c8y/ngx-components/gettext';
import {
  Direction,
  getGenericDeviceIdentifier,
  Mapping,
  RepairStrategy,
  Substitution
} from '../../shared';
import { MappingTokens } from '../../shared/mapping/processor/processor.model';

/**
 * Translates a `gettext` key and fills in its `{{name}}` parameters. The problems found in an AI result
 * are shown to the user, so they are translated when they are created.
 */
export type TranslateFn = (key: string, params?: Record<string, unknown>) => string;

/**
 * Two substitutions writing the same target are never both meaningful, and which one survives
 * depends on where the generated set is applied — the bulk replace used by the transformation
 * step keeps both, while the stepper's per-item add silently collapses them.
 */
export function duplicateTargetIssues(substitutions: Substitution[], t: TranslateFn): string[] {
  const seen = new Set<string>();
  const duplicates = new Set<string>();
  for (const sub of substitutions) {
    if (seen.has(sub.pathTarget)) duplicates.add(sub.pathTarget);
    seen.add(sub.pathTarget);
  }
  return [...duplicates].map(pathTarget =>
    t(gettext('More than one substitution writes to "{{pathTarget}}". Only one of them takes effect.'), {
      pathTarget
    })
  );
}

/**
 * The mapping's `useExternalId` decides which identity token is correct, and the generation
 * prompt says which one to use — but both the frontend and backend validators accept either,
 * so picking the wrong one is silently allowed and resolves the wrong device.
 */
export function identityTokenIssue(
  substitutions: Substitution[],
  mapping: Mapping,
  t: TranslateFn
): string | null {
  const expected = getGenericDeviceIdentifier(mapping);
  const other = expected.endsWith('externalId')
    ? `${MappingTokens.IDENTITY}.c8ySourceId`
    : `${MappingTokens.IDENTITY}.externalId`;
  const isInbound = (mapping.direction ?? Direction.INBOUND) === Direction.INBOUND;

  const usesWrongToken = substitutions.some(sub => {
    const path = isInbound ? sub.pathTarget : sub.pathSource;
    return typeof path === 'string' && path.includes(other) && !path.includes(expected);
  });
  return usesWrongToken
    ? t(
        gettext(
          'The device identity is mapped via "{{other}}", but this mapping has useExternalId={{useExternalId}}, so it must use "{{expected}}".'
        ),
        { other, expected, useExternalId: !!mapping.useExternalId }
      )
    : null;
}

/**
 * Syntax-checks generated Smart Function code. Nothing else does: the backend persists
 * `code` untouched, and the only compile is an opportunistic warm-up on activation that runs
 * on a virtual thread and swallows failures into a log line — so broken code saves *and*
 * activates silently and first fails per message.
 */
export function validateSmartFunctionCode(code: string, t: TranslateFn): string[] {
  // `new Function` parses a function *body*, so module syntax has to go first — the agent may
  // already have emitted the ESM export that the component would otherwise add. The backend
  // strips the same constructs via JavaScriptModuleStripper before compiling.
  const plainScript = code
    .replace(/^\s*export\s+\{[^}]*\}\s*;?\s*$/gm, '')
    .replace(/^\s*import\s+.*$/gm, '')
    .replace(/^\s*export\s+(?=(?:function|const|let|var|class)\b)/gm, '');

  try {
    new Function(plainScript);
    return [];
  } catch (error) {
    if (error instanceof SyntaxError) {
      return [
        t(
          gettext(
            'The generated code is not valid JavaScript: {{message}}. It was most likely truncated before the function was complete. Ask the agent to regenerate it.'
          ),
          { message: error.message }
        )
      ];
    }
    // Anything else (e.g. a Content-Security-Policy block on Function()) means the check could
    // not run at all, which says nothing about the code — so report nothing.
    return [];
  }
}

/** Maps an LLM-supplied repairStrategy onto the enum, falling back to DEFAULT when it is
 *  missing, null, or not a real strategy name. */
export function coerceRepairStrategy(value: unknown): RepairStrategy {
  return typeof value === 'string' && Object.values(RepairStrategy).includes(value as RepairStrategy)
    ? (value as RepairStrategy)
    : RepairStrategy.DEFAULT;
}
