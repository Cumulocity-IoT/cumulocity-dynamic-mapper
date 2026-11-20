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
  isSmartFunction,
  Mapping
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

/** For outbound, targetAPI names the Cumulocity API of the source object, not the output. */
function apiLabel(direction: string, targetAPI: string): string {
  return direction === 'OUTBOUND' ? `source: Cumulocity ${targetAPI}` : `target: ${targetAPI}`;
}

export function buildReviewMessage(mapping: Mapping): string {
  const direction = mapping.direction ?? 'INBOUND';
  const targetAPI = mapping.targetAPI ?? '';

  if (isSmartFunction(mapping)) {
    return `Review the existing ${direction} Smart Function` +
      (targetAPI ? ` (${apiLabel(direction, targetAPI)})` : '') +
      " and suggest improvements.\n";
  }
  return `Review the existing substitutions for this ${direction}` +
    (targetAPI ? ` ${targetAPI}` : '') + " mapping" +
    " and suggest improvements.\n";
}

const SOURCE_MAX_CHARS = 2000;

/** The source template as pretty-printed JSON for the chat message, capped so a large payload does not flood it. */
function sourceText(mapping: Mapping): string {
  let text = String(mapping.sourceTemplate ?? '');
  try {
    text = JSON.stringify(JSON.parse(text), null, 2);
  } catch { /* not JSON: show the raw text */ }
  return text.length > SOURCE_MAX_CHARS
    ? text.slice(0, SOURCE_MAX_CHARS) + '\n… (truncated; the full message is in the mapping context)'
    : text;
}

export function buildGenerateMessage(mapping: Mapping): string {
  const direction = mapping.direction ?? 'INBOUND';
  const targetAPI = mapping.targetAPI ?? '';

  if (isSmartFunction(mapping)) {
    const isOutbound = direction === 'OUTBOUND';
    // Outbound is the mirror image of inbound: the source is a Cumulocity object (the notification payload, for
    // which targetAPI names the API — it is NOT the output type), and the target is whatever the receiving broker
    // expects, which nothing in the mapping describes. targetTemplate is therefore always "{}" by design, and
    // treating that as "missing context" made the AI stop and ask instead of generating code, so the editor
    // stayed empty on every attempt. Show the source, say the receiver's format is unknown, and have the agent
    // state the structure it chose instead of silently inventing one.
    const missingContextHint = isOutbound
      ? "The source is the Cumulocity" + (targetAPI ? ` ${targetAPI}` : '') + " object below that triggers this mapping" +
        " (it is also the sourceTemplate in the mapping context). Its fields follow the Cumulocity data model.\n" +
        "```json\n" + sourceText(mapping) + "\n```\n" +
        "There is no fixed targetTemplate and the receiver's format is not known — the Smart Function itself" +
        " builds whatever the target broker/protocol expects. Use only values the source carries (and" +
        " `msg.sourceId` or `context.getExternalId()` for the device identity). Unless the user names a format," +
        " choose a simple JSON structure (or raw text/binary when the mapping context's mappingType is" +
        " ANY_PAYLOAD) and state the structure you chose in your answer. Generate the code directly — do not ask" +
        " the user for a target format before generating.\n"
      : !targetAPI
        ? "The targetAPI is not set — ask the user what kind of Cumulocity object to produce" +
          " (e.g. MEASUREMENT, ALARM, EVENT, INVENTORY) before generating code.\n"
        // INBOUND Smart Functions also always carry an empty targetTemplate ("{}") by design — the function
        // body constructs the Cumulocity API payload itself. No example of the target is sent: the agent's
        // system prompt already defines the Cumulocity payload shape per targetAPI, and a canned example
        // (with made-up values such as type "c8y_TestEvent") competes with the real source message.
        // The source is shown as-is, without guessing whether it is a captured message or a sample: it is
        // the broker's own structure, which generally does not follow the Cumulocity data model.
        : "The source is the incoming broker message below (it is also the sourceTemplate in the mapping" +
          " context, together with its transport fields). Its structure is arbitrary and generally does not" +
          " follow the Cumulocity data model: do not assume its field names match the target's.\n" +
          "```json\n" + sourceText(mapping) + "\n```\n" +
          `There is no fixed targetTemplate — the Smart Function itself builds the Cumulocity ${targetAPI} API` +
          " payload. Derive the device identity, type, text, time and values from whatever the message and its" +
          " transport fields (such as the record key) carry. If it does not carry a field the target needs, use a" +
          " sensible constant or derive it from the topic — do not invent data, and state the choice in your" +
          " answer.\n Generate the code directly — do not ask the user for a target format before generating.\n";
    return `Generate a ${direction} Smart Function for the following mapping` +
      (targetAPI ? ` (${apiLabel(direction, targetAPI)})` : '') + ".\n" +
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
