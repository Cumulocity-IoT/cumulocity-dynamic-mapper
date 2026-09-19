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

import { Direction, StepperConfiguration, Substitution } from '../../shared/mapping/mapping.model';
import { ClassDefinition, ClassOrEnum, EnumDefinition } from './smart-function-api.model';
import { SMART_FUNCTION_API } from './generated/smart-function-api.generated';

export enum EditorMode {
  CREATE = 'CREATE',
  UPDATE = 'UPDATE',
  READ_ONLY = 'READ_ONLY',
  COPY = 'COPY'
}

/** The in-progress substitution being edited in the stepper/unified-editor's transformation step. */
export interface SubstitutionModel extends Partial<Substitution> {
  stepperConfiguration?: StepperConfiguration;
  pathSourceIsExpression?: boolean;
  pathTargetIsExpression?: boolean;
  targetExpression?: { result?: string; resultType?: string; valid: boolean };
  sourceExpression?: { result?: string; resultType?: string; valid: boolean };
}

export const STEP_SELECT_CONNECTOR = 0;
export const STEP_GENERAL_SETTINGS = 1;
export const STEP_SELECT_TEMPLATES = 2;
export const STEP_DEFINE_SUBSTITUTIONS = 3;
export const STEP_TEST_MAPPING = 4;

// The shape these providers consume, and the table itself — generated from the Smart Function
// type definitions so the editor cannot describe an API the types do not have. Regenerate with
// `npm run generate:editor-api` in dynamic-mapper-smart-function; CI fails on a stale file.


/**
 * Registers completion and hover providers for Flow Function JavaScript in Monaco Editor
 * @param {Monaco} monaco - The Monaco instance
 * @returns {{ dispose: () => void }} Combined disposable for both providers
 */
export function createCompletionProviderFlowFunction(monaco: any, direction: Direction = Direction.INBOUND): { dispose: () => void } {
  // Register flow-specific classes and interfaces
  const allClasses: ClassOrEnum[] = SMART_FUNCTION_API;

  // Add utility functions specific to flow functions
  const utilityFunctions = [
    {
      name: 'createCumulocityObject',
      parameters: ['payload', 'cumulocityType', 'action'],
      returnType: 'CumulocityObject',
      documentation: 'Creates a new CumulocityObject with the specified payload, type, and action.',
      description: 'Create new Cumulocity message'
    },
    {
      name: 'createDeviceMessage',
      parameters: ['topic', 'payload'],
      returnType: 'DeviceMessage',
      documentation: 'Creates a new DeviceMessage with Uint8Array payload and topic. Use TextEncoder for string conversion.',
      description: 'Create new device message'
    },
    {
      name: 'createExternalId',
      parameters: ['externalId', 'type'],
      returnType: 'ExternalId',
      documentation: 'Creates a new ExternalId for simple device lookup (v2.0+).',
      description: 'Create external ID reference'
    },
    {
      name: 'createExternalSource',
      parameters: ['externalId', 'type'],
      returnType: 'ExternalSource',
      documentation: 'Creates a new ExternalSource for advanced device creation scenarios.',
      description: 'Create external source with creation capabilities'
    },
    {
      name: 'createMappingError',
      parameters: ['errorDetails'],
      returnType: 'MappingError',
      documentation: 'Creates a new MappingError with the specified error details.',
      description: 'Create mapping error'
    },
    {
      name: 'encodePayload',
      parameters: ['obj'],
      returnType: 'Uint8Array',
      documentation: 'Converts a JavaScript object to Uint8Array using TextEncoder (for DeviceMessage payload).',
      description: 'Encode object to Uint8Array'
    },
    {
      name: 'decodePayload',
      parameters: ['uint8Array'],
      returnType: 'string',
      documentation: 'Converts Uint8Array to string using TextDecoder (for reading DeviceMessage payload).',
      description: 'Decode Uint8Array to string'
    },
    {
      name: 'CumulocityObject.measurement',
      parameters: ['payload'],
      returnType: 'CumulocityObject',
      documentation: 'Builder shortcut: creates a CumulocityObject with cumulocityType="measurement" and action="create".',
      description: 'Build measurement CumulocityObject'
    },
    {
      name: 'CumulocityObject.event',
      parameters: ['payload'],
      returnType: 'CumulocityObject',
      documentation: 'Builder shortcut: creates a CumulocityObject with cumulocityType="event" and action="create".',
      description: 'Build event CumulocityObject'
    },
    {
      name: 'CumulocityObject.alarm',
      parameters: ['payload'],
      returnType: 'CumulocityObject',
      documentation: 'Builder shortcut: creates a CumulocityObject with cumulocityType="alarm" and action="create".',
      description: 'Build alarm CumulocityObject'
    },
    {
      name: 'CumulocityObject.operation',
      parameters: ['payload'],
      returnType: 'CumulocityObject',
      documentation: 'Builder shortcut: creates a CumulocityObject with cumulocityType="operation" and action="create".',
      description: 'Build operation CumulocityObject'
    },
    {
      name: 'CumulocityObject.managedObject',
      parameters: ['payload'],
      returnType: 'CumulocityObject',
      documentation: 'Builder shortcut: creates a CumulocityObject with cumulocityType="managedObject" and action="create".',
      description: 'Build managedObject CumulocityObject'
    },
    {
      name: 'DeviceMessage.forTopic',
      parameters: ['topic', 'payload'],
      returnType: 'DeviceMessage',
      documentation: 'Builder shortcut: creates a DeviceMessage for the given topic with an encoded payload.',
      description: 'Build DeviceMessage for topic'
    },
    {
      name: 'DeviceMessage.create',
      parameters: ['payload'],
      returnType: 'DeviceMessage',
      documentation: 'Builder shortcut: creates a DeviceMessage with an encoded payload.',
      description: 'Build DeviceMessage'
    }
  ];

  // Common variable names and their associated types — shared by completion and hover providers
  const isOutbound = direction === Direction.OUTBOUND;
  const commonVars = [
    {
      name: 'msg',
      type: isOutbound ? 'OutboundMessage' : 'DynamicMapperDeviceMessage',
      desc: isOutbound
        ? 'Outbound message: pre-deserialized Cumulocity domain object (payload, cumulocityType?, sourceId?)'
        : 'Inbound device message (pre-deserialized JSON payload)'
    },
    { name: 'context', type: 'SmartFunctionContext', desc: 'Smart Function runtime context providing state, config, device lookups, and mapping utilities' },
    { name: 'outputMsg', type: 'OutputMessage', desc: 'Output message variable' },
    { name: 'c8yMsg', type: 'CumulocityObject', desc: 'Cumulocity message variable' },
    { name: 'deviceMsg', type: 'DeviceMessage', desc: 'Device message variable' },
    { name: 'externalId', type: 'ExternalId', desc: 'External ID reference variable (v2.0+)' }
  ];

  /**
   * Resolves the thing on the left of a dot to a definition.
   *
   * Users type `context.` and `msg.`, i.e. *variables* — not `SmartFunctionContext.`. Matching
   * only on class names meant the member lists (15 documented methods on SmartFunctionContext
   * alone) were unreachable in practice, so the receiver is resolved through commonVars first and
   * only then treated as a literal class name.
   */
  const resolveDefinition = (name: string): ClassOrEnum | undefined => {
    const variable = commonVars.find(v => v.name === name);
    if (variable) {
      const byType = allClasses.find(c => c.name === variable.type);
      if (byType) return byType;
    }
    return allClasses.find(c => c.name === name);
  };

  /** `foo.bar|` -> { receiver: 'foo', typed: 'bar' }. The trailing partial word matters: without
   *  it the member list vanished as soon as the user typed the first character after the dot. */
  /** `['key', 'defaultValue?']` -> `${1:key}, ${2:defaultValue}`. Named placeholders let the user
   *  tab through arguments and see what each one is; the previous empty `${1}` gave no hint. */
  const snippetArgs = (parameters: string[]): string =>
    parameters.map((raw, i) => `\${${i + 1}:${raw.replace(/\?$/, '').trim()}}`).join(', ');

  const matchMemberAccess = (text: string): { receiver: string; typed: string } | null => {
    const m = text.match(/(\w+)\s*\.\s*(\w*)$/);
    return m ? { receiver: m[1], typed: m[2] } : null;
  };

  // Register completion and hover providers
  const completionDisposable = monaco.languages.registerCompletionItemProvider('javascript', {
    // No ' ': a space triggered the widget on essentially every keystroke of prose inside strings
    // and comments, which made the editor feel noisy without ever offering anything useful.
    triggerCharacters: ['.', '('],
    provideCompletionItems: function (model: any, position: any, _context: any, _token: any) {
      const textUntilPosition = model.getValueInRange({
        startLineNumber: position.lineNumber,
        startColumn: 1,
        endLineNumber: position.lineNumber,
        endColumn: position.column
      });

      const wordAtPosition = model.getWordUntilPosition(position);
      const range = {
        startLineNumber: position.lineNumber,
        endLineNumber: position.lineNumber,
        startColumn: wordAtPosition.startColumn,
        endColumn: wordAtPosition.endColumn
      };

      const suggestions = [];

      // Check for specific contexts
      const memberAccess = matchMemberAccess(textUntilPosition);
      if (memberAccess) {
        const objectName = memberAccess.receiver;
        const matchedClass = resolveDefinition(objectName);

        // Object property/method completion
        if (matchedClass) {
          if (matchedClass.isEnum) {
            // Enum value completion - use type guard
            const enumClass = matchedClass as EnumDefinition;
            enumClass.values.forEach((value, index) => {
              suggestions.push({
                label: value,
                kind: monaco.languages.CompletionItemKind.EnumMember,
                documentation: {
                  value: `${enumClass.name}.${value}`
                },
                insertText: value,
                range: range,
                sortText: `00-${index.toString().padStart(2, '0')}`
              });
            });
          } else {
            // Class property and method completion - use type guard
            const classObject = matchedClass as ClassDefinition;
            
            // Add deprecation warning if class is deprecated
            const deprecationWarning = classObject.deprecated 
              ? '⚠️ **DEPRECATED** - ' 
              : '';

            classObject.properties.forEach((prop, index) => {
              suggestions.push({
                label: prop.name,
                kind: monaco.languages.CompletionItemKind.Field,
                documentation: {
                  value: `${deprecationWarning}**${prop.type}**\n\n${prop.documentation}`
                },
                insertText: prop.name,
                range: range,
                sortText: classObject.deprecated 
                  ? `99-${index.toString().padStart(2, '0')}` // Lower priority for deprecated
                  : `01-${index.toString().padStart(2, '0')}`,
                tags: classObject.deprecated ? [monaco.languages.CompletionItemTag.Deprecated] : undefined
              });
            });

            classObject.methods.forEach((method, index) => {
              const params = method.parameters.join(', ');
              suggestions.push({
                label: {
                  label: `${method.name}(${params})`,
                  description: method.returnType
                },
                kind: monaco.languages.CompletionItemKind.Method,
                documentation: {
                  value: `${deprecationWarning}**${method.returnType}** ${method.name}(${params})\n\n${method.documentation}`
                },
                insertText: `${method.name}(${snippetArgs(method.parameters)})`,
                insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
                range: range,
                sortText: classObject.deprecated 
                  ? `99-${index.toString().padStart(2, '0')}` // Lower priority for deprecated
                  : `02-${index.toString().padStart(2, '0')}`,
                tags: classObject.deprecated ? [monaco.languages.CompletionItemTag.Deprecated] : undefined
              });
            });
          }
          return {
            suggestions,
            incomplete: false
          };
        }

        // The receiver is a dot-expression we know nothing about (`payload.`, `someLocal.`).
        // Returning nothing lets Monaco's own JavaScript worker answer instead; previously this
        // fell through and offered every global class and helper, which is never right after a dot.
        return { suggestions: [] };
      }

      // Global class/enum completion
      allClasses.forEach((cls, index) => {
        const deprecationWarning = cls.deprecated ? '⚠️ **DEPRECATED** - ' : '';
        
        suggestions.push({
          label: cls.name,
          kind: cls.isEnum
            ? monaco.languages.CompletionItemKind.Enum
            : monaco.languages.CompletionItemKind.Class,
          documentation: {
            value: `${deprecationWarning}${cls.documentation}`
          },
          insertText: cls.name,
          range: range,
          sortText: cls.deprecated 
            ? `99-${index.toString().padStart(2, '0')}` // Lower priority for deprecated
            : `03-${index.toString().padStart(2, '0')}`,
          tags: cls.deprecated ? [monaco.languages.CompletionItemTag.Deprecated] : undefined
        });
      });

      // Utility function completion
      utilityFunctions.forEach((func, index) => {
        suggestions.push({
          label: {
            label: `${func.name}(${func.parameters.join(', ')})`,
            description: func.description
          },
          kind: monaco.languages.CompletionItemKind.Function,
          documentation: {
            value: `**${func.returnType}** ${func.name}(${func.parameters.join(', ')})\n\n${func.documentation}`
          },
          insertText: `${func.name}(${snippetArgs(func.parameters)})`,
          insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
          range: range,
          sortText: `04-${index.toString().padStart(2, '0')}`
        });
      });

      // Provide new object creation completions
      const newMatch = textUntilPosition.match(/new\s+(\w*)$/);
      if (newMatch) {
        // Only constructors are meaningful after `new`. The global classes and helpers pushed
        // above would otherwise be returned alongside them, listing every class twice.
        suggestions.length = 0;
        allClasses.forEach((cls, index) => {
          if (!cls.isEnum) {
            const classObject = cls as ClassDefinition;
            const constructorParams = classObject.properties
              .filter(p => p.name !== 'time')
              .map(p => p.name)
              .join(', ');

            const deprecationWarning = classObject.deprecated ? '⚠️ **DEPRECATED** - ' : '';

            suggestions.push({
              label: {
                label: cls.name,
                description: `new ${cls.name}(${constructorParams})`
              },
              kind: monaco.languages.CompletionItemKind.Constructor,
              documentation: {
                value: `${deprecationWarning}Create a new ${cls.name} instance:\n\n\`\`\`javascript\nnew ${cls.name}(${constructorParams})\n\`\`\``
              },
              insertText: cls.name + (
                classObject.properties.length > 0
                  ? `(${classObject.properties.filter(p => p.name !== 'time').map((_, i) => `\${${i + 1}}`).join(', ')})`
                  : '()'
              ),
              insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
              range: range,
              sortText: classObject.deprecated 
                ? `99-${index.toString().padStart(2, '0')}` // Lower priority for deprecated
                : `05-${index.toString().padStart(2, '0')}`,
              tags: classObject.deprecated ? [monaco.languages.CompletionItemTag.Deprecated] : undefined
            });
          }
        });

        return {
          suggestions,
          incomplete: false
        };
      }

      // Function parameter suggestions for common patterns
      const funcCallMatch = textUntilPosition.match(/(\w+)\s*\(\s*$/);
      if (funcCallMatch) {
        const funcName = funcCallMatch[1];
        const matchedFunc = utilityFunctions.find(f => f.name === funcName);

        if (matchedFunc) {
          if (matchedFunc.name === 'createCumulocityObject') {
            suggestions.push({
              label: 'payload object',
              kind: monaco.languages.CompletionItemKind.Variable,
              documentation: 'The payload object for the Cumulocity message',
              insertText: '${1:payload}',
              insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
              range: range,
              sortText: '00-01'
            });
          } else if (matchedFunc.name === 'createDeviceMessage') {
            suggestions.push({
              label: 'Uint8Array payload',
              kind: monaco.languages.CompletionItemKind.Variable,
              documentation: 'The message payload as Uint8Array. Use new TextEncoder().encode(JSON.stringify(obj))',
              insertText: 'new TextEncoder().encode(JSON.stringify(${1:payload}))',
              insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
              range: range,
              sortText: '00-01'
            });
          } else if (matchedFunc.name === 'createExternalId' || matchedFunc.name === 'createExternalSource') {
            suggestions.push({
              label: '"externalId"',
              kind: monaco.languages.CompletionItemKind.Value,
              documentation: 'External ID string',
              insertText: '"${1:deviceId}"',
              insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
              range: range,
              sortText: '00-01'
            });
            suggestions.push({
              label: '"c8y_Serial"',
              kind: monaco.languages.CompletionItemKind.Value,
              documentation: 'Common external ID type',
              insertText: ', "c8y_Serial"',
              range: range,
              sortText: '00-02'
            });
          } else if (matchedFunc.name === 'encodePayload') {
            suggestions.push({
              label: 'object to encode',
              kind: monaco.languages.CompletionItemKind.Variable,
              documentation: 'JavaScript object to convert to Uint8Array',
              insertText: '${1:obj}',
              insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
              range: range,
              sortText: '00-01'
            });
          } else if (matchedFunc.name === 'decodePayload') {
            suggestions.push({
              label: 'Uint8Array to decode',
              kind: monaco.languages.CompletionItemKind.Variable,
              documentation: 'Uint8Array to convert to string',
              insertText: '${1:uint8Array}',
              insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
              range: range,
              sortText: '00-01'
            });
          }

          if (suggestions.length > 0) {
            return {
              suggestions,
              incomplete: false
            };
          }
        }
      }

      // Common variable name suggestions for flow functions
      if (textUntilPosition.match(/\b(let|const|var)\s+\w*$/)) {
        commonVars.forEach((variable, index) => {
          suggestions.push({
            label: {
              label: variable.name,
              description: variable.type
            },
            kind: monaco.languages.CompletionItemKind.Variable,
            documentation: {
              value: `**${variable.type}**\n\n${variable.desc}`
            },
            insertText: variable.name,
            range: range,
            sortText: `06-${index.toString().padStart(2, '0')}`
          });
        });
      }

      if (suggestions.length > 0) {
        return {
          suggestions,
          incomplete: false
        };
      }

      return { suggestions: [] };
    }
  });

  const hoverDisposable = monaco.languages.registerHoverProvider('javascript', {
    provideHover: function(model: any, position: any) {
      const word = model.getWordAtPosition(position);
      if (!word) return null;
      const w = word.word;
      const range = {
        startLineNumber: position.lineNumber,
        endLineNumber: position.lineNumber,
        startColumn: word.startColumn,
        endColumn: word.endColumn
      };

      // onMessage entry-point — direction-aware full signature
      if (w === 'onMessage') {
        const content = isOutbound
          ? `\`\`\`typescript\nfunction onMessage(\n  msg: OutboundMessage,\n  context: SmartFunctionContext\n): Array<DeviceMessage> | DeviceMessage | null\n\`\`\`\n\n` +
            `**Outbound Smart Function** — called for each Cumulocity platform event that matches the mapping.\n\n` +
            `**Parameters:**\n` +
            `- \`msg: OutboundMessage\` — Pre-deserialized Cumulocity domain object:\n` +
            `  - \`msg.payload\` — C8Y domain object payload. Access with bracket notation: \`msg.payload["c8y_Temperature"]["T"]["value"]\`\n` +
            `  - \`msg.cumulocityType\` — Triggering event type: \`"measurement"\`, \`"event"\`, \`"alarm"\`, \`"operation"\`, \`"managedObject"\`\n` +
            `  - \`msg.sourceId\` — Internal Cumulocity device ID of the originating device\n` +
            `- \`context: SmartFunctionContext\` — Runtime context with state, config, and device lookups\n\n` +
            `**Returns** \`DeviceMessage | DeviceMessage[]\` with these fields:\n` +
            `- \`topic\` — Broker topic to publish to (omit to use the mapping's fixed publish topic)\n` +
            `- \`payload\` — Message body: plain JSON object (auto-serialized) or \`Uint8Array\` for binary protocols\n` +
            `- \`transportFields\` — Transport-specific metadata, e.g. \`{ "key": externalId }\` for Kafka partition key\n` +
            `- \`transportId\` — Target transport identifier (e.g. \`"mqtt"\`, \`"kafka"\`)\n` +
            `- \`clientId\` — MQTT/transport client ID for the outgoing message\n\n` +
            `Return \`null\` to suppress publishing (e.g. when the device is offline).\n\n` +
            `**Example:**\n` +
            `\`\`\`js\nfunction onMessage(msg, context) {\n  const externalId = context.getExternalId();\n  return {\n    topic: \`measurements/\${externalId}\`,\n    payload: { temperature: msg.payload["c8y_Temperature"]["T"]["value"] }\n  };\n}\n\`\`\``
          : `\`\`\`typescript\nfunction onMessage(\n  msg: DynamicMapperDeviceMessage,\n  context: SmartFunctionContext\n): CumulocityObject | CumulocityObject[] | void\n\`\`\`\n\n` +
            `**Inbound Smart Function** — called for each broker message that matches the mapping.\n\n` +
            `**Parameters:**\n` +
            `- \`msg: DynamicMapperDeviceMessage\` — Pre-deserialized broker message:\n` +
            `  - \`msg.payload\` — JSON payload as a plain object. Access fields: \`msg.payload["temperature"]\`\n` +
            `  - \`msg.topic\` — The broker topic on which the message arrived\n` +
            `  - \`msg.clientId\` — Transport/MQTT client ID of the sender (inbound only)\n` +
            `  - \`msg.time\` — ISO-8601 receive timestamp set by the connector\n` +
            `  - \`msg.transportId\` — Connector identifier (e.g. \`"my-mqtt-connector"\`, \`"kafka-prod"\`)\n` +
            `  - \`msg.transportFields\` — Transport-specific key/value pairs; for Kafka the record key as \`msg.transportFields["key"]\`\n` +
            `- \`context: SmartFunctionContext\` — Runtime context with state, config, and device lookups\n\n` +
            `**Returns** \`CumulocityObject | CumulocityObject[]\` with these fields:\n` +
            `- \`cumulocityType\` — Target C8Y API: \`"measurement"\`, \`"event"\`, \`"alarm"\`, \`"operation"\`, \`"managedObject"\`, \`"custom"\`\n` +
            `- \`action\` — HTTP verb: \`"create"\` (POST), \`"update"\` (PUT), \`"delete"\`, \`"patch"\`\n` +
            `- \`payload\` — C8Y REST API payload matching the \`cumulocityType\` shape\n` +
            `- \`externalSource\` — Device identity for resolution: \`[{ type: "c8y_Serial", externalId: "..." }]\`\n` +
            `- \`sourceId\` — Override target device (e.g. route child-device data to parent)\n` +
            `- \`targetPath\` — Microservice path when \`cumulocityType\` is \`"custom"\`, must start with \`/service/\`\n` +
            `- \`destination\` — \`"cumulocity"\` (default), \`"iceflow"\`, \`"streaming-analytics"\`\n` +
            `- \`contextData\` — Implicit device creation: \`{ deviceName, deviceType, deviceGroups, deviceFragments }\`\n\n` +
            `Return \`void\` or \`[]\` to suppress output.\n\n` +
            `**Example:**\n` +
            `\`\`\`js\nfunction onMessage(msg, context) {\n  return [{\n    cumulocityType: "measurement",\n    action: "create",\n    payload: { type: "c8y_Temp", time: new Date().toISOString(),\n               c8y_Temp: { T: { value: msg.payload["temp"], unit: "C" } } },\n    externalSource: [{ type: "c8y_Serial", externalId: context.getClientId() }]\n  }];\n}\n\`\`\``;
        return { range, contents: [{ value: content, isTrusted: true }] };
      }

      // Receiver-aware member hover. `payload` exists on CumulocityObject, DeviceMessage and
      // OutboundMessage; the name-only search below returns whichever class is declared first, so
      // hovering `msg.payload` could describe a completely different type. Resolve the receiver
      // when there is one and answer from that class.
      const before = model.getValueInRange({
        startLineNumber: position.lineNumber,
        startColumn: 1,
        endLineNumber: position.lineNumber,
        endColumn: word.startColumn
      });
      const receiverMatch = before.match(/(\w+)\s*\.\s*$/);
      if (receiverMatch) {
        const owner = resolveDefinition(receiverMatch[1]);
        if (owner) {
          if (owner.isEnum) {
            const enumDef = owner as EnumDefinition;
            if (enumDef.values.includes(w)) {
              return { range, contents: [{ value: `\`\`\`typescript\n(enum member) ${owner.name}.${w}\n\`\`\`\n\n${owner.documentation}`, isTrusted: true }] };
            }
          } else {
            const ownerDef = owner as ClassDefinition;
            const ownProp = ownerDef.properties.find(pr => pr.name === w);
            if (ownProp) {
              return { range, contents: [{ value: `\`\`\`typescript\n(property) ${owner.name}.${ownProp.name}: ${ownProp.type}\n\`\`\`\n\n${ownProp.documentation}`, isTrusted: true }] };
            }
            const ownMethod = ownerDef.methods.find(mt => mt.name === w);
            if (ownMethod) {
              return { range, contents: [{ value: `\`\`\`typescript\n(method) ${owner.name}.${ownMethod.name}(${ownMethod.parameters.join(', ')}): ${ownMethod.returnType}\n\`\`\`\n\n${ownMethod.documentation}`, isTrusted: true }] };
            }
          }
          // Known receiver, unknown member: say nothing rather than describe an unrelated class.
          return null;
        }
      }

      const func = utilityFunctions.find(f => f.name === w);
      if (func) {
        return { range, contents: [{ value: `\`\`\`typescript\n(function) ${func.name}(${func.parameters.join(', ')}): ${func.returnType}\n\`\`\`\n\n${func.documentation}`, isTrusted: true }] };
      }

      for (const cls of allClasses) {
        if (cls.name === w) {
          const kind = cls.isEnum ? 'enum' : 'class';
          const dep = (cls as any).deprecated ? '\n\n⚠️ **DEPRECATED**' : '';
          return { range, contents: [{ value: `\`\`\`typescript\n${kind} ${cls.name}\n\`\`\`\n\n${cls.documentation}${dep}`, isTrusted: true }] };
        }
        if (cls.isEnum) {
          const enumDef = cls as EnumDefinition;
          if (enumDef.values.includes(w)) {
            return { range, contents: [{ value: `\`\`\`typescript\n(enum member) ${cls.name}.${w}\n\`\`\`\n\n${cls.documentation}`, isTrusted: true }] };
          }
        } else {
          const classDef = cls as ClassDefinition;
          const prop = classDef.properties.find(p => p.name === w);
          if (prop) {
            return { range, contents: [{ value: `\`\`\`typescript\n(property) ${cls.name}.${prop.name}: ${prop.type}\n\`\`\`\n\n${prop.documentation}`, isTrusted: true }] };
          }
          const method = classDef.methods.find(m => m.name === w);
          if (method) {
            return { range, contents: [{ value: `\`\`\`typescript\n(method) ${cls.name}.${method.name}(${method.parameters.join(', ')}): ${method.returnType}\n\`\`\`\n\n${method.documentation}`, isTrusted: true }] };
          }
        }
      }

      // Common variable names — look up the type and show its class documentation
      const varEntry = commonVars.find(v => v.name === w);
      if (varEntry) {
        const typeCls = allClasses.find(c => c.name === varEntry.type);
        if (typeCls && !typeCls.isEnum) {
          const classDef = typeCls as ClassDefinition;
          let content = `\`\`\`typescript\n(variable) ${w}: ${varEntry.type}\n\`\`\`\n\n${typeCls.documentation}`;
          if (classDef.properties.length > 0) {
            content += '\n\n**Properties:**\n' + classDef.properties
              .map(p => `- \`${p.name}: ${p.type}\` — ${p.documentation.split('\n')[0]}`)
              .join('\n');
          }
          if (classDef.methods.length > 0) {
            content += '\n\n**Methods:**\n' + classDef.methods
              .map(m => `- \`${m.name}(${m.parameters.join(', ')}): ${m.returnType}\` — ${m.documentation.split('\n')[0]}`)
              .join('\n');
          }
          return { range, contents: [{ value: content, isTrusted: true }] };
        }
        return { range, contents: [{ value: `\`\`\`typescript\n(variable) ${w}: ${varEntry.type}\n\`\`\`\n\n${varEntry.desc}`, isTrusted: true }] };
      }

      return null;
    }
  });

  return {
    dispose: () => {
      completionDisposable.dispose();
      hoverDisposable.dispose();
    }
  };
}