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
import {
  getGenericDeviceIdentifier,
  isSubstitutionsAsCode,
  Mapping,
  SAMPLE_TEMPLATES_C8Y
} from '../../shared';

/**
 * The mapping as the agent sees it: templates are parsed (the stored ones are JSON strings), substitutions are
 * dropped, and the code and ESM flag are added.
 */
export interface MappingForAi extends Record<string, unknown> {
  sourceTemplate?: unknown;
  targetTemplate?: unknown;
  additionalSamples?: { payload: unknown; [key: string]: unknown }[];
  substitutions?: unknown;
  code?: string;
  supportESM?: boolean;
}

/** Messages the AI drawer sends to the agent: suggestion prompts and the first generation request. */

/**
 * "I want to provide a sample target payload" suggestion prompt (Smart Function, CREATE mode).
 * Asks the agent to wait for the user's next message — a pasted JSON sample — before
 * generating, rather than proceeding on generic assumptions.
 */
export function buildProvideSampleMessage(): string {
  return "I'd like to provide a sample of the expected target payload before you generate the" +
    " Smart Function. Ask me to paste it, then wait for my next message and use it as the" +
    " authoritative shape to produce — do not generate code yet.\n";
}

export function buildReviewMessage(mapping: Mapping): string {
  const direction = mapping.direction ?? 'INBOUND';
  const targetAPI = mapping.targetAPI ?? '';

  if (isSubstitutionsAsCode(mapping)) {
    return `Review the existing ${direction} Smart Function` +
      (targetAPI ? ` (target: ${targetAPI})` : '') +
      " and suggest improvements.\n";
  }
  return `Review the existing substitutions for this ${direction}` +
    (targetAPI ? ` ${targetAPI}` : '') + " mapping" +
    " and suggest improvements.\n";
}

export function buildGenerateMessage(mapping: Mapping): string {
  const direction = mapping.direction ?? 'INBOUND';
  const targetAPI = mapping.targetAPI ?? '';

  if (isSubstitutionsAsCode(mapping)) {
    const isOutbound = direction === 'OUTBOUND';
    // Outbound Smart Functions never have a fixed targetTemplate — it is always "{}" by design,
    // because the function body itself defines whatever message structure the target broker/
    // protocol expects (there's no fixed Cumulocity-side schema to map into, as there is for
    // inbound). Treating that as "missing context" made the AI always stop and ask the user a
    // clarifying question instead of generating code, so the editor stayed empty on every attempt.
    const inboundSample = !isOutbound && targetAPI ? SAMPLE_TEMPLATES_C8Y[targetAPI] : undefined;
    const missingContextHint = isOutbound
      ? "This is an OUTBOUND mapping: there is no fixed targetTemplate/schema — the Smart Function" +
        " itself builds whatever payload structure the target broker/protocol expects. Use the" +
        " sourceTemplate (the Cumulocity object being published) plus the topic/description in the" +
        " mapping context to infer reasonable field names and generate the code directly — do not" +
        " ask the user for a target format before generating.\n"
      : !targetAPI
        ? "The targetAPI is not set — ask the user what kind of Cumulocity object to produce" +
          " (e.g. MEASUREMENT, ALARM, EVENT, INVENTORY) before generating code.\n"
        // INBOUND Smart Functions also always carry an empty targetTemplate ("{}") by design —
        // the function body constructs the Cumulocity API payload itself instead of mapping into
        // a fixed template. Without a concrete example the AI has no shape to aim for, so give it
        // the standard sample payload for the mapping's targetAPI as a reference.
        : inboundSample
          ? `There is no fixed targetTemplate — the Smart Function itself builds the Cumulocity` +
            ` ${targetAPI} API payload from the sourceTemplate fields. A typical ${targetAPI} object` +
            " looks like:\n```json\n" + inboundSample + "\n```\n" +
            " Generate the code directly — do not ask the user for a target format before generating.\n"
          : "";
    return `Generate a ${direction} Smart Function for the following mapping` +
      (targetAPI ? ` (target: ${targetAPI})` : '') + ".\n" +
      missingContextHint;
  }
  const identityTarget = getGenericDeviceIdentifier(mapping);
  return `Generate JSONata substitutions for this ${direction}` +
    (targetAPI ? ` ${targetAPI}` : '') + " mapping.\n" +
    `Map the device identity to \`${identityTarget}\` — this mapping has` +
    ` useExternalId=${!!mapping.useExternalId} — do not use the other` +
    " `_IDENTITY_` field.\n";
}

export function buildMappingForAi(mapping: Mapping, supportESM: boolean): MappingForAi {
  const m: MappingForAi = { ...mapping } as unknown as MappingForAi;
  delete m.substitutions;
  m.sourceTemplate = JSON.parse(m.sourceTemplate as string);
  m.targetTemplate = JSON.parse(m.targetTemplate as string);
  // Further samples of the same payload type (Message Explorer multi-select), so the agent can
  // generalize instead of fitting a single message. Payloads are parsed like sourceTemplate;
  // raw text that is not JSON stays a string.
  if (Array.isArray(m.additionalSamples) && m.additionalSamples.length > 0) {
    m.additionalSamples = m.additionalSamples.map((sample) => {
      let payload = sample.payload;
      try { payload = JSON.parse(sample.payload as string); } catch { /* keep as string */ }
      return { ...sample, payload };
    });
  } else {
    delete m.additionalSamples;
  }
  // Tell the AI whether ESM exports are required in the generated code
  m.supportESM = supportESM;
  return m;
}
