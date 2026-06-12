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

import { Direction } from '../../shared/mapping/mapping.model';

export enum EditorMode {
  CREATE = 'CREATE',
  UPDATE = 'UPDATE',
  READ_ONLY = 'READ_ONLY',
  COPY = 'COPY'
}

export const STEP_SELECT_CONNECTOR = 0;
export const STEP_GENERAL_SETTINGS = 1;
export const STEP_SELECT_TEMPLATES = 2;
export const STEP_DEFINE_SUBSTITUTIONS = 3;
export const STEP_TEST_MAPPING = 4;

// Type definitions for better type safety
interface BaseClass {
  name: string;
  documentation: string;
  deprecated?: boolean;
}

interface ClassDefinition extends BaseClass {
  isEnum: false;
  properties: Array<{ name: string; type: string; documentation: string }>;
  methods: Array<{ name: string; parameters: string[]; returnType: string; documentation: string }>;
}

interface EnumDefinition extends BaseClass {
  isEnum: true;
  values: string[];
}

type ClassOrEnum = ClassDefinition | EnumDefinition;

/**
 * Registers completion and hover providers for Flow Function JavaScript in Monaco Editor
 * @param {Monaco} monaco - The Monaco instance
 * @returns {{ dispose: () => void }} Combined disposable for both providers
 */
export function createCompletionProviderFlowFunction(monaco: any, direction: Direction = Direction.INBOUND): { dispose: () => void } {
  // Register flow-specific classes and interfaces
  const customClasses: ClassOrEnum[] = [
    {
      name: 'CumulocityObject',
      isEnum: false,
      properties: [
        { name: 'payload', type: 'object', documentation: 'The same payload that would be used in the C8Y REST/SmartREST API.' },
        { name: 'cumulocityType', type: '"measurement" | "event" | "alarm" | "operation" | "managedObject" | "custom"', documentation: 'Which type in the C8Y API is being modified. Singular not plural. Serves as discriminator for CumulocityObject. Use "custom" to call a tenant-local microservice — set targetPath to the "/service/…" path.' },
        { name: 'action', type: '"create" | "update"| "delete" | "patch"', documentation: 'What kind of operation is being performed on this type.' },
        { name: 'externalSource', type: 'ExternalId[] | ExternalId | ExternalSource[]', documentation: 'External ID configuration for device resolution. Use ExternalId[] for simple lookups, ExternalSource[] for advanced device creation scenarios.' },
        { name: 'targetPath', type: 'string', documentation: 'Target microservice path used when cumulocityType is "custom". Must start with "/service/" to stay within the tenant. Example: "/service/my-microservice/api/process".' },
        { name: 'destination', type: '"cumulocity" | "iceflow" | "streaming-analytics"', documentation: 'Destination for the message. Default: "cumulocity".' },
        { name: 'contextData', type: 'Record<string, any>', documentation: 'Additional context data for device creation (deviceName, deviceType, processingMode, deviceFragments, deviceGroups, attachment fields).' },
        { name: 'sourceId', type: 'string', documentation: 'Internal Cumulocity source/device ID. Set this to override automatic device resolution.' }
      ],
      methods: [],
      documentation: 'A request going to or coming from Cumulocity core (or IceFlow/offloading).'
    },
    {
      name: 'DeviceMessage',
      isEnum: false,
      properties: [
        { name: 'payload', type: 'Record<string, any> | Uint8Array', documentation: 'Message payload. Prefer a plain JSON object — the runtime serializes it automatically. Use Uint8Array only for binary/non-JSON protocols (e.g. SparkPlug B). TextEncoder/TextDecoder are available.' },
        { name: 'action', type: '"create" | "update"| "delete" | "patch"', documentation: 'What kind of operation is being performed on this type.' },
        { name: 'cumulocityType', type: '"measurement" | "event" | "alarm" | "operation" | "managedObject"', documentation: 'Optional: Target Cumulocity API type (measurement, event, alarm, operation, managedObject). If not specified, derived from topic or mapping.' },
        { name: 'transportId', type: 'string', documentation: 'Identifier for the source/dest transport e.g. "mqtt", "opc-ua".' },
        { name: 'topic', type: 'string', documentation: 'The topic on the transport. Use _externalId_ placeholder to auto-reference device external ID.' },
        { name: 'clientId', type: 'string', documentation: 'Transport/MQTT client Id.' },
        { name: 'transportFields', type: 'Record<string, any>', documentation: 'Dictionary of transport-specific fields/properties/headers. For Kafka, use "key" to define record key.' },
        { name: 'time', type: 'Date', documentation: 'Timestamp of incoming message; does nothing when sending.' },
        { name: 'externalSource', type: 'Array<{type: string; externalId?: string}>', documentation: 'External source config for resolving _externalId_ placeholder. Defines which external ID type to use. Provide externalId explicitly when the value is known upfront.' },
        { name: 'retain', type: 'boolean', documentation: 'Whether the message should be retained on the broker (MQTT retain flag).' },
        { name: 'sourceId', type: 'string', documentation: 'Internal Cumulocity source/device ID associated with this device message.' }
      ],
      methods: [],
      documentation: 'A message received from a device or sent to a device. Payload is now Uint8Array (changed in v2.0).'
    },
    {
      name: 'ExternalId',
      isEnum: false,
      properties: [
        { name: 'externalId', type: 'string', documentation: 'External Id to be looked up.' },
        { name: 'type', type: 'string', documentation: 'External ID type, e.g. "c8y_Serial".' }
      ],
      methods: [],
      documentation: 'Simple external ID reference for lookups (introduced in v2.0). Use this for basic external ID references.'
    },
    {
      name: 'ExternalSource',
      isEnum: false,
      properties: [
        { name: 'externalId', type: 'string', documentation: 'External Id to be looked up and/or created to get C8Y "id".' },
        { name: 'type', type: 'string', documentation: 'External ID type, e.g. "c8y_Serial".' },
        { name: 'autoCreateDeviceMO', type: 'boolean', documentation: 'Default true. Set false for advanced users who want to create somewhere deeper in the hierarchy.' },
        { name: 'parentId', type: 'string', documentation: 'To support adding child assets/devices.' },
        { name: 'childReference', type: '"device" | "asset" | "addition"', documentation: 'If creating a child, what kind to create.' },
        { name: 'clientId', type: 'string', documentation: 'Transport/MQTT client Id, stored on the MO for outbound messages.' }
      ],
      methods: [],
      documentation: 'Advanced external ID with device creation capabilities. For simple lookups, use ExternalId instead.'
    },
    {
      name: 'CumulocitySource',
      isEnum: false,
      deprecated: true,
      properties: [
        { name: 'internalId', type: 'string', documentation: '**DEPRECATED** - Use externalSource with ExternalId or specify id in payload directly. Will be removed in v6.2.0.' }
      ],
      methods: [],
      documentation: '**DEPRECATED** - Use externalSource with ExternalId instead, or specify the id directly in the payload. Will be removed in version 6.2.0.'
    },
    {
      name: 'DataPrepContext',
      isEnum: false,
      properties: [
        { name: 'runtime', type: 'string', documentation: 'Runtime identifier. Always "dynamic-mapper" in the Dynamic Mapper context.' }
      ],
      methods: [
        { name: 'getState', parameters: ['key', 'defaultValue?'], returnType: 'any', documentation: 'Retrieves a persisted state value. Returns the value stored by a previous invocation, or the defaultValue (if provided) on first call.' },
        { name: 'setState', parameters: ['key', 'value'], returnType: 'void', documentation: 'Persists a value by key. State survives across message invocations for the same mapping and is cleared when the mapping is deleted.' }
      ],
      documentation: 'Base context interface providing persistent state (getState/setState). Extended by SmartFunctionContext with the full Smart Function API.'
    },
    {
      name: 'SmartFunctionContext',
      isEnum: false,
      properties: [
        { name: 'runtime', type: '"dynamic-mapper"', documentation: 'Runtime identifier — always "dynamic-mapper".' }
      ],
      methods: [
        { name: 'getState', parameters: ['key', 'defaultValue?'], returnType: 'any', documentation: 'Retrieves a persisted state value. Returns the value stored by a previous invocation, or the defaultValue (if provided) on first call.' },
        { name: 'setState', parameters: ['key', 'value'], returnType: 'void', documentation: 'Persists a value by key. State survives across message invocations for the same mapping and is cleared when the mapping is deleted.' },
        { name: 'getStateAll', parameters: [], returnType: 'Record<string, any>', documentation: 'Returns all persisted state entries for the current mapping as a plain object.' },
        { name: 'getStateKeySet', parameters: [], returnType: 'string[]', documentation: 'Returns the set of all keys currently stored in the mapping state.' },
        { name: 'clearState', parameters: [], returnType: 'void', documentation: 'Clears all persisted state for the current mapping. On clearState the state is flushed to the persistent store.' },
        { name: 'getConfig', parameters: [], returnType: 'Record<string, any>', documentation: 'Returns the mapping configuration object (mappingId, mappingName, tenant, topic, targetAPI, debug, clientId, etc.).' },
        { name: 'getClientId', parameters: [], returnType: 'string | undefined', documentation: 'Returns the transport/MQTT client ID associated with this mapping, or undefined if not set.' },
        { name: 'getExternalId', parameters: [], returnType: 'string | undefined', documentation: 'Returns the resolved external ID of the source device (requires useExternalId enabled and externalIdType configured), or undefined.' },
        { name: 'getTesting', parameters: [], returnType: 'boolean', documentation: 'Returns true if the mapping is currently being tested (not in production).' },
        { name: 'getManagedObject', parameters: ['c8ySourceId'], returnType: 'C8yManagedObject | null', documentation: 'Lookup a device from inventory cache by internal Cumulocity device ID. Returns null if not found.' },
        { name: 'getManagedObjectByExternalId', parameters: ['externalId: ExternalId'], returnType: 'C8yManagedObject | null', documentation: 'Lookup a device from inventory cache by ExternalId object ({ externalId, type }). Returns null if not found.' },
        { name: 'getDTMAsset', parameters: ['assetId'], returnType: 'C8yManagedObject | null', documentation: 'Lookup DTM Asset properties by asset ID. Returns null if not found.' },
        { name: 'addLogMessage', parameters: ['message'], returnType: 'void', documentation: 'Adds a log message stored under _LOGS_ in state. Visible in the processing result for debugging.' },
        { name: 'logMessage', parameters: ['message'], returnType: 'void', documentation: 'Alias for addLogMessage(). Adds a log message stored under _LOGS_ in state.' },
        { name: 'addWarning', parameters: ['warning'], returnType: 'void', documentation: 'Adds a warning message to the processing context. Surfaced for debugging non-fatal issues (e.g. fallback logic applied, optional field missing).' }
      ],
      documentation: 'Smart Function runtime context. Extends DataPrepContext with state management, config access, device lookups, and mapping utilities.'
    },
    {
      name: 'DynamicMapperDeviceMessage',
      isEnum: false,
      properties: [
        { name: 'payload', type: 'Record<string, any>', documentation: 'Pre-deserialized JSON payload. Dynamic Mapper automatically deserializes JSON payloads to objects. Use bracket notation to access properties: payload["key"].\n\n**ANY_PAYLOAD (SparkPlugB, Protobuf, XML):** Base64-encoded binary string. Decode with a pure-JS Base64 decoder, for example:\n```js\nfunction decodeBase64(base64) {\n  const chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";\n  const clean = base64.replace(/=+$/, "");\n  const bytes = [];\n  let buffer = 0;\n  let bits = 0;\n\n  for (let i = 0; i < clean.length; i++) {\n    const value = chars.indexOf(clean.charAt(i));\n    if (value < 0) { continue; }\n    buffer = (buffer << 6) | value;\n    bits += 6;\n    if (bits >= 8) { bits -= 8; bytes.push((buffer >> bits) & 0xff); }\n  }\n  return new Uint8Array(bytes);\n}\nconst bytes = decodeBase64(msg.payload);\n```' },
        { name: 'topic', type: 'string', documentation: 'The broker topic on which the message arrived (e.g. MQTT topic).' },
        { name: 'clientId', type: 'string', documentation: 'Transport/MQTT client ID of the sender.' },
        { name: 'transportId', type: 'string', documentation: 'Identifier for the source/destination transport (e.g. "mqtt", "kafka").' },
        { name: 'transportFields', type: 'Record<string, string>', documentation: 'Transport-specific fields/properties/headers.' },
        { name: 'time', type: 'Date', documentation: 'Timestamp of the incoming message.' }
      ],
      methods: [],
      documentation: 'Inbound device message passed to the Smart Function as the first argument (`msg`). Payloads are pre-deserialized from JSON for convenience — use bracket notation: msg.payload["key"].'
    },
    {
      name: 'OutputMessage',
      isEnum: false,
      properties: [
        { name: 'sinkType', type: 'string', documentation: 'An unique sink type, example: C8Y Core.' },
        { name: 'deviceIdentifier', type: 'Record<string, any>', documentation: 'The unique device identifier, example: External Id.' },
        { name: 'payload', type: 'any', documentation: 'The payload of the message.' },
        { name: 'properties', type: 'Record<string, any>', documentation: 'A map of properties associated with the message.' }
      ],
      methods: [],
      documentation: 'Output message to be sent by the flow function.'
    },
    {
      name: 'MappingError',
      isEnum: false,
      properties: [
        { name: 'errorDetails', type: 'string[]', documentation: 'Array of error detail strings.' },
        { name: 'payload', type: 'any', documentation: 'Optional payload that resulted in this error.' }
      ],
      methods: [],
      documentation: 'Error information for mapping operations.'
    },
    {
      name: 'OutboundMessage',
      isEnum: false,
      properties: [
        { name: 'payload', type: 'object', documentation: 'Pre-deserialized Cumulocity domain object payload (e.g. C8yMeasurement). Known fields are typed; custom fragments use bracket notation.' },
        { name: 'cumulocityType', type: 'string | undefined', documentation: 'Cumulocity API type of the triggering event, e.g. "measurement", "event", "alarm".' },
        { name: 'sourceId', type: 'string | undefined', documentation: 'Internal Cumulocity device ID of the originating device, if available.' }
      ],
      methods: [],
      documentation: 'Outbound message passed to the Smart Function as the first argument (`msg`). Contains the pre-deserialized Cumulocity domain object that triggered the outbound mapping.'
    }
  ];

  // Add enums for specific values
  const enums: ClassOrEnum[] = [
    {
      name: 'CumulocityAction',
      isEnum: true,
      values: ['create', 'update', 'delete', 'patch'],
      documentation: 'HTTP actions: create=POST, update=PUT, delete=DELETE, patch=PATCH.'
    },
    {
      name: 'CumulocityType',
      isEnum: true,
      values: ['measurement', 'alarm', 'event', 'managedObject', 'operation', 'custom'],
      documentation: 'Cumulocity API types (singular form). Use "custom" to target a tenant-local microservice via targetPath.'
    },
    {
      name: 'Destination',
      isEnum: true,
      values: ['cumulocity', 'iceflow', 'streaming-analytics'],
      documentation: 'Available message destinations.'
    },
    {
      name: 'ChildReference',
      isEnum: true,
      values: ['device', 'asset', 'addition'],
      documentation: 'Types of child references when creating hierarchies.'
    }
  ];

  // Combine classes and enums
  const allClasses: ClassOrEnum[] = [...customClasses, ...enums];

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

  // Register completion and hover providers
  const completionDisposable = monaco.languages.registerCompletionItemProvider('javascript', {
    triggerCharacters: ['.', ' ', '('],
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
      const dotMatch = textUntilPosition.match(/(\w+)\.\s*$/);
      if (dotMatch) {
        const objectName = dotMatch[1];
        const matchedClass = allClasses.find(cls => cls.name === objectName);

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
                insertText: method.parameters.length > 0
                  ? `${method.name}(${method.parameters.map((_, i) => `\${${i + 1}}`).join(', ')})`
                  : `${method.name}()`,
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
          insertText: `${func.name}(${func.parameters.map((_, i) => `\${${i + 1}}`).join(', ')})`,
          insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
          range: range,
          sortText: `04-${index.toString().padStart(2, '0')}`
        });
      });

      // Provide new object creation completions
      const newMatch = textUntilPosition.match(/new\s+(\w*)$/);
      if (newMatch) {
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
            `  - \`msg.clientId\` — Transport/MQTT client ID of the sender\n` +
            `  - \`msg.transportId\` — Source transport identifier (e.g. \`"mqtt"\`, \`"kafka"\`)\n` +
            `  - \`msg.transportFields\` — Transport-specific headers/properties\n` +
            `  - \`msg.time\` — Timestamp of the incoming message\n` +
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