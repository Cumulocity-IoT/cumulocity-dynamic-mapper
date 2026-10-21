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
import { Injectable } from '@angular/core';
import { ChatHistory } from '@c8y/ngx-components/ai';
import { Substitution } from '../../shared';
import { stripTemplateMetadataTags } from '../../shared/mapping/util';

/**
 * Everything the AI drawer needs to resume where it left off: the SDK's opaque chat snapshot plus
 * the result already extracted from it. The extracted part lives outside the chat (it is parsed
 * from assistant replies), so restoring the chat alone would leave Save disabled.
 */
export interface StoredAiSession {
  /** {@link generationInputsKey} of the mapping the conversation was held against. */
  inputsKey: string;
  /** Key of the code/substitutions the mapping held when the drawer was opened (what Save would replace). */
  targetKeyAtOpen: string;
  /** Key of {@link generatedCode}/{@link substitutions}, i.e. what Save would put in their place. */
  extractedKey: string;
  history: ChatHistory;
  generatedCode: string;
  substitutions: Substitution[];
  validationIssues: string[];
  valid: boolean;
}

/**
 * In-memory (per browser tab, lost on reload) store of AI drawer conversations, keyed by mapping
 * identifier, so closing the drawer with Cancel — or leaving the editor — doesn't throw the
 * conversation away.
 */
@Injectable({ providedIn: 'root' })
export class AiChatHistoryService {
  private readonly sessions = new Map<string, StoredAiSession>();

  /**
   * Returns the stored session. Whether its extracted result still applies (see `isStoredResultReusable`) is the
   * caller's call: a changed mapping — e.g. after the generated result was saved into it — keeps
   * the transcript useful, only the extracted result must not be reused.
   */
  get(identifier: string | undefined): StoredAiSession | undefined {
    return identifier ? this.sessions.get(identifier) : undefined;
  }

  set(identifier: string | undefined, session: StoredAiSession): void {
    if (!identifier) return;
    this.sessions.set(identifier, session);
  }

  delete(identifier: string | undefined): void {
    if (identifier) this.sessions.delete(identifier);
  }
}

/**
 * The part of the mapping that shapes what the agent generates. Deliberately NOT the whole mapping:
 * fields like `lastUpdate`, `version` or `draftDirty` change on every Save without affecting the
 * conversation, and keying on them made a session look stale right after the result was saved.
 * The code/substitutions are left out too — they are the *output*, tracked by the target keys below.
 */
export function generationInputsKey(mappingForAI: Record<string, unknown> | null | undefined): string {
  const m = mappingForAI ?? {};
  return JSON.stringify({
    sourceTemplate: m['sourceTemplate'],
    targetTemplate: m['targetTemplate'],
    direction: m['direction'],
    targetAPI: m['targetAPI'],
    useExternalId: !!m['useExternalId'],
    transformationType: m['transformationType'],
    mappingType: m['mappingType'],
    sampleTransportFields: m['sampleTransportFields'],
    supportESM: !!m['supportESM']
  });
}

/** Comparable form of a Smart Function's source: ignores line endings, surrounding whitespace and metadata tags. */
export function codeTargetKey(code: string | undefined): string {
  return stripTemplateMetadataTags(code ?? '').replace(/\r\n/g, '\n').trim();
}

/** Comparable form of a substitution list, with the same defaults the drawer applies to generated ones. */
export function substitutionsTargetKey(substitutions: Partial<Substitution>[] | undefined): string {
  return JSON.stringify(
    (substitutions ?? []).map(sub => ({
      pathSource: sub.pathSource,
      pathTarget: sub.pathTarget,
      expandArray: !!sub.expandArray,
      repairStrategy: sub.repairStrategy ?? 'DEFAULT'
    }))
  );
}

/**
 * Whether a stored session's extracted result may still be offered for Save. It may when the
 * conversation was about the same inputs AND what Save would overwrite is still either what it
 * was when the session was stored (drawer cancelled, nothing applied) or the result itself (it was
 * saved, and nothing was edited since). Anything else means the user changed the code/substitutions
 * by hand in between, and Save would silently throw those edits away.
 */
export function isStoredResultReusable(
  stored: StoredAiSession,
  inputsKey: string,
  currentTargetKey: string
): boolean {
  return (
    stored.valid &&
    stored.inputsKey === inputsKey &&
    (currentTargetKey === stored.targetKeyAtOpen || currentTargetKey === stored.extractedKey)
  );
}
