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
import {
  API,
  AdditionalSample,
  Direction,
  ExtensionEntry,
  Mapping,
  MappingType,
  PayloadWrapper,
  Qos,
  SAMPLE_TEMPLATES_C8Y,
  SampleMessageInfo,
  SamplePayloadType,
  Substitution,
  TransformationType,
  createCustomUuid,
  nextIdAndPad
} from '../../shared';
import {
  CONTEXT_DATA_KEY_NAME,
  applyCodeTemplateSample,
  isCodeOrExtensionTransformation,
  stringToBase64
} from '../../shared/mapping/util';
import { MappingTokens } from '../../shared/mapping/processor/processor.model';
import { CodeTemplate } from '../../shared/configuration/configuration.model';
import { ExplorerSampleMessage } from '../core/explorer-mapping-handoff.service';

/** What the Message Explorer hands over when the user turns a captured message into a mapping. */
export interface ExplorerPreFill {
  sessionTopic?: string;
  topic: string;
  payload: string;
  binary?: boolean;
  payloadType?: SamplePayloadType;
  binaryFormat?: string | null;
  payloadSize?: number;
  clientId?: string;
  connectorType?: string;
  key?: string;
  metadata?: Record<string, string>;
  additionalMessages?: ExplorerSampleMessage[];
  targetAPI?: string;
  publishTopic?: string;
  publishTopicSample?: string;
}

export interface NewMappingRequest {
  direction: Direction;
  mappingType: MappingType;
  transformationType: TransformationType;
  substitutionsAsCode: boolean;
  codeTemplate?: CodeTemplate;
  extension?: Partial<ExtensionEntry>;
  /** Number of existing mappings; names the new one "Mapping - NN". */
  mappingsCount: number;
  preFill?: ExplorerPreFill | null;
}

export interface NewMappingResult {
  mapping: Mapping;
  /** What is known about the captured message. Stepper state only, never persisted. */
  sampleMessageInfo?: SampleMessageInfo;
  /** Broker metadata and key a Smart Function or Java extension receives out of band. Stepper state only. */
  sampleTransportFields?: Record<string, string>;
  /** Extra captured messages for AI generation. Stepper state only. */
  additionalSamples?: AdditionalSample[];
}

/** Shapes a captured payload like the primary sample (see addMapping): Any Payload gets the
 *  Base64 wrapper, other non-JSON raw payloads the plain {"payload": ...} wrapper. */
export function toSourceSample(payload: string, binary: boolean, mappingType: MappingType, isInbound: boolean): string {
  if (isInbound && mappingType === MappingType.ANY_PAYLOAD) {
    const base64 = binary ? payload : stringToBase64(payload);
    return JSON.stringify({ payload: base64 } as PayloadWrapper);
  }
  if (binary || [MappingType.ANY_PAYLOAD, MappingType.HEX, MappingType.FLAT_FILE].includes(mappingType)) {
    try {
      JSON.parse(payload);
    } catch {
      return JSON.stringify({ payload } as PayloadWrapper);
    }
  }
  return payload;
}

/**
 * Builds the mapping a new-mapping flow starts from: defaults per direction and transformation
 * type, the sample from the chosen code template, and, when the user came from the Message
 * Explorer, the captured topic, payload and metadata. It changes nothing outside its result; the
 * caller applies the stepper state and bumps its own mapping counter.
 */
export function buildNewMapping(req: NewMappingRequest): NewMappingResult {
  const { direction, mappingType, transformationType, substitutionsAsCode, codeTemplate, extension, preFill } = req;
  let sampleMessageInfo: SampleMessageInfo | undefined;
  let sampleTransportFields: Record<string, string> | undefined;
  let additionalSamples: AdditionalSample[] | undefined;
  const identifier = createCustomUuid();
  const sub: Substitution[] = [];

  // Only the Cumulocity-side field has a well-known, schema-valid sample (SAMPLE_TEMPLATES_C8Y)
  // — the broker/external-side field is always blank, since no generic schema exists for an
  // arbitrary device payload. Which physical field ("sourceTemplate" vs "targetTemplate") is
  // the Cumulocity side flips with direction: target for INBOUND, source for OUTBOUND — matching
  // the "Source/Target Template - Cumulocity/Broker" panel labels and
  // MappingStepperService.expandTemplates()'s direction-aware swap.
  // Code/extension-based transformations (Smart Function, Java extension) get '{}' even on the
  // Cumulocity side: the actual shape is produced by code, not a template, and seeding a generic
  // sample there previously misled both users and the AI generation flow.
  const cumulocitySample = isCodeOrExtensionTransformation(transformationType)
    ? '{}'
    : SAMPLE_TEMPLATES_C8Y[API.MEASUREMENT.name];
  const defaultSourceTemplate = direction === Direction.OUTBOUND ? cumulocitySample : '{}';
  const defaultTargetTemplate = direction === Direction.OUTBOUND ? '{}' : cumulocitySample;

  let mapping: Mapping;
  if (direction == Direction.INBOUND) {
    let code;
    if (substitutionsAsCode) code = codeTemplate?.code;
    mapping = {
      // name: `Mapping - ${identifier.substring(0, 7)}`,
      name: `Mapping - ${nextIdAndPad(req.mappingsCount, 2)}`,
      id: 'any',
      identifier: identifier,
      mappingTopic: '',
      mappingTopicSample: '',
      targetAPI: API.MEASUREMENT.name,
      sourceTemplate: defaultSourceTemplate,
      targetTemplate: defaultTargetTemplate,
      active: false,
      maxFailureCount: 0,
      qos: Qos.AT_LEAST_ONCE,
      substitutions: sub,
      useExternalId: false,
      createNonExistingDevice: false,
      mappingType: mappingType,
      transformationType: transformationType,
      updateExistingDevice: false,
      externalIdType: 'c8y_Serial',
      code,
      direction: direction,
      autoAckOperation: true,
      debug: false,
      filterMapping: 'true',
      lastUpdate: Date.now()
    };
  } else {
    let code;
    if (substitutionsAsCode) code = codeTemplate?.code;
    mapping = {
      name: `Mapping - ${nextIdAndPad(req.mappingsCount, 2)}`,
      id: identifier,
      identifier: identifier,
      // publishTopic: '',
      // publishTopicSample: '',
      targetAPI: API.MEASUREMENT.name,
      sourceTemplate: defaultSourceTemplate,
      targetTemplate: defaultTargetTemplate,
      active: false,
      maxFailureCount: 0,
      qos: Qos.AT_LEAST_ONCE,
      substitutions: sub,
      useExternalId: false,
      createNonExistingDevice: false,
      mappingType: mappingType,
      transformationType: transformationType,
      updateExistingDevice: false,
      externalIdType: 'c8y_Serial',
      code,
      direction: direction,
      autoAckOperation: true,
      debug: false,
      filterMapping: 'true',
      lastUpdate: Date.now()
    };
  }
  if (mappingType == MappingType.FLAT_FILE) {
    const sampleSource = JSON.stringify({
      payload: '10,temp,1666963367'
    } as PayloadWrapper);
    mapping = {
      ...mapping,
      sourceTemplate: sampleSource
    };
  }

  // Seed the sample from the chosen code template (see applyCodeTemplateSample); the Message
  // Explorer pre-fill below still takes precedence.
  if (substitutionsAsCode) {
    applyCodeTemplateSample(mapping, codeTemplate, mappingType, direction);
  }

  // Apply pre-fill from Message Explorer (topic + payload + targetAPI)
  if (preFill) {
    const isInbound = direction === Direction.INBOUND;
    if (isInbound) {
      // sessionTopic is the subscription pattern (e.g. fridge/#); topic is the
      // concrete received topic (e.g. fridge/sensor-ny-99). Use the pattern as
      // mappingTopic so wildcards are preserved, and the concrete topic as sample.
      mapping.mappingTopic = preFill.sessionTopic ?? preFill.topic;
      mapping.mappingTopicSample = preFill.topic;
    }
    // The Message Explorer always captures the "source" side of the mapping — the broker/
    // device payload for INBOUND sessions, or the real Cumulocity event for OUTBOUND
    // sessions (sourceSystem/targetSystem swap the same way in mapping-stepper.component.ts).
    // So the captured payload always belongs on sourceTemplate, regardless of direction.
    mapping.sourceTemplate = preFill.payload;
    // Raw-payload mapping types keep their sample as {"payload": "<text or base64>"}; the
    // captured payload is not JSON in that case, so wrap it instead of storing it as is.
    if (isInbound && mapping.mappingType === MappingType.ANY_PAYLOAD) {
      // At runtime an Any Payload mapping always receives the raw bytes as Base64, text and JSON
      // included, so the sample must be the Base64 of the bytes too. Binary payloads already
      // arrive Base64-encoded; text ones are encoded here (as UTF-8, as the broker sent them).
      const base64 = preFill.binary
        ? preFill.payload
        : stringToBase64(preFill.payload);
      mapping.sourceTemplate = JSON.stringify({ payload: base64 } as PayloadWrapper);
    } else if (
      preFill.binary ||
      [MappingType.ANY_PAYLOAD, MappingType.HEX, MappingType.FLAT_FILE].includes(mapping.mappingType)
    ) {
      let isJson = true;
      try {
        JSON.parse(mapping.sourceTemplate);
      } catch {
        isJson = false;
      }
      if (!isJson) {
        mapping.sourceTemplate = JSON.stringify({ payload: preFill.payload } as PayloadWrapper);
      }
    }
    // Remember what is known about the captured message (type, size, format, sender) so AI
    // generation does not have to guess how to read it. Stepper state only, never persisted.
    sampleMessageInfo = {
      payloadType: preFill.payloadType,
      binaryFormat: preFill.binaryFormat ?? undefined,
      payloadSize: preFill.payloadSize,
      clientId: preFill.clientId,
      topic: preFill.topic,
      connectorType: preFill.connectorType
    };
    // Seed the captured broker message key (e.g. a Kafka record key) so the sample mirrors what
    // the mapping actually sees at runtime. Only for JSONata-style transformations, where the
    // backend injects the key into the payload as _CONTEXT_DATA_.key: Smart Functions and Java
    // extensions instead receive it out-of-band via the message's transport fields, so putting
    // it in the payload sample there would misrepresent the input (and mislead AI generation).
    // Other broker metadata (Kafka headers, Pulsar properties, AMQP routing key, ...) reaches
    // Smart Functions and Java extensions as transport fields next to the key, so it is carried
    // the same way. JSONata mappings do not receive it, so it is not seeded there.
    if (preFill.metadata && Object.keys(preFill.metadata).length > 0
        && isCodeOrExtensionTransformation(mapping.transformationType)) {
      sampleTransportFields = { ...preFill.metadata };
    }
    if (preFill.key) {
      if (isCodeOrExtensionTransformation(mapping.transformationType)) {
        // Code/extension transformations read the key from msg.transportFields, which is not part
        // of the payload — carry it as stepper state so AI generation still sees a real example.
        sampleTransportFields = {
          ...sampleTransportFields,
          [CONTEXT_DATA_KEY_NAME]: preFill.key
        };
      } else {
        try {
          const parsed = JSON.parse(mapping.sourceTemplate);
          if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
            parsed[MappingTokens.CONTEXT_DATA] = {
              ...(parsed[MappingTokens.CONTEXT_DATA] ?? {}),
              [CONTEXT_DATA_KEY_NAME]: preFill.key
            };
            mapping.sourceTemplate = JSON.stringify(parsed);
          }
        } catch {
          // sample is not a JSON object — nothing to attach the key to, leave it untouched
        }
      }
    }
    // Extra messages of the same payload type (Message Explorer multi-select): shaped like the
    // primary sample so AI generation sees consistent examples. Stepper state only.
    if (preFill.additionalMessages?.length) {
      additionalSamples = preFill.additionalMessages.map(m => {
        const transportFields: Record<string, string> = {};
        if (isCodeOrExtensionTransformation(mapping.transformationType)) {
          Object.assign(transportFields, m.metadata ?? {});
          if (m.key) transportFields[CONTEXT_DATA_KEY_NAME] = m.key;
        }
        return {
          topic: m.topic,
          payload: toSourceSample(m.payload, !!m.binary, mapping.mappingType, isInbound),
          transportFields: Object.keys(transportFields).length ? transportFields : undefined
        };
      });
    }
    if (preFill.targetAPI) {
      mapping.targetAPI = preFill.targetAPI;
      // Only INBOUND's target side (Cumulocity) has a generic schema sample to seed with.
      // OUTBOUND's target side (Broker) has no generic schema, and its source already holds
      // the real captured Cumulocity payload — nothing further needs seeding there.
      if (isInbound) {
        const sample = isCodeOrExtensionTransformation(mapping.transformationType)
          ? '{}'
          : SAMPLE_TEMPLATES_C8Y[preFill.targetAPI];
        mapping.targetTemplate = sample ?? mapping.targetTemplate;
      }
    }
    if (preFill.publishTopic) {
      mapping.publishTopic = preFill.publishTopic;
      mapping.useExternalId = true;
    }
    if (preFill.publishTopicSample) {
      mapping.publishTopicSample = preFill.publishTopicSample;
    }
    // For OUTBOUND, pre-fill filterMapping with 'true' so at least one execution filter is set
    if (direction === Direction.OUTBOUND && !mapping.filterMapping) {
      mapping.filterMapping = 'true';
    }
  }

  if (extension) {
    mapping.extension = extension as ExtensionEntry;
  }

  return { mapping, sampleMessageInfo, sampleTransportFields, additionalSamples };
}
