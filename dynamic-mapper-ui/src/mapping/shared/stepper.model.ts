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

export enum EditorMode {
  CREATE = 'CREATE',
  UPDATE = 'UPDATE',
  READ_ONLY = 'READ_ONLY',
  COPY = 'COPY'
}

export enum AdvisorAction {
  CONTINUE = 'CONTINUE',
  CANCEL = 'CANCEL',
  STOP_SNOOPING_AND_EDIT = 'STOP_SNOOPING_AND_EDIT',
  CONTINUE_SNOOPING = 'CONTINUE_SNOOPING',
  EDIT = 'EDIT',
  VIEW = 'VIEW',
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
 * Creates a completion provider for custom JavaScript classes in Monaco Editor for Flow Functions
 * @param {Monaco} monaco - The Monaco instance
 * @returns {Object} The completion provider
 */
export function createCompletionProviderFlowFunction(monaco) {
  // Register flow-specific classes and interfaces
  const customClasses: ClassOrEnum[] = [
    {
      name: 'CumulocityObject',
      isEnum: false,
      properties: [
        { name: 'payload', type: 'object', documentation: 'The same payload that would be used in the C8Y REST/SmartREST API.' },
        { name: 'cumulocityType', type: 'string', documentation: 'Which type in the C8Y api is being modified. e.g. "measurement".' },
        { name: 'action', type: '"create" | "update"', documentation: 'What kind of operation is being performed on this type.' },
        { name: 'externalSource', type: 'ExternalId[] | ExternalId', documentation: 'External Id to lookup and optionally create. Use ExternalId for simple lookups.' },
        { name: 'destination', type: '"cumulocity" | "iceflow" | "streaming-analytics"', documentation: 'Destination for the message.' }
      ],
      methods: [],
      documentation: 'A request going to or coming from Cumulocity core (or IceFlow/offloading).'
    },
    {
      name: 'CumulocityMessage',
      isEnum: false,
      deprecated: true,
      properties: [
        { name: 'payload', type: 'object', documentation: '**DEPRECATED** - Use CumulocityObject instead.' },
        { name: 'cumulocityType', type: 'string', documentation: '**DEPRECATED** - Use CumulocityObject instead.' },
        { name: 'action', type: '"create" | "update"', documentation: '**DEPRECATED** - Use CumulocityObject instead.' },
        { name: 'externalSource', type: 'ExternalSource[] | ExternalSource', documentation: '**DEPRECATED** - Use CumulocityObject with ExternalId instead.' },
        { name: 'internalSource', type: 'CumulocitySource[] | CumulocitySource', documentation: '**DEPRECATED** - Use externalSource or specify id in payload directly.' },
        { name: 'destination', type: '"cumulocity" | "iceflow" | "streaming-analytics"', documentation: '**DEPRECATED** - Use CumulocityObject instead.' }
      ],
      methods: [],
      documentation: '**DEPRECATED** - Use CumulocityObject instead. Will be removed in version 6.2.0.'
    },
    {
      name: 'DeviceMessage',
      isEnum: false,
      properties: [
        { name: 'payload', type: 'Uint8Array', documentation: 'Message payload as Uint8Array. Use TextEncoder/TextDecoder for string conversion. (Changed from ArrayBuffer in v2.0)' },
        { name: 'transportId', type: 'string', documentation: 'Identifier for the source/dest transport e.g. "mqtt", "opc-ua".' },
        { name: 'topic', type: 'string', documentation: 'The topic on the transport. Use _externalId_ placeholder to auto-reference device external ID.' },
        { name: 'clientId', type: 'string', documentation: 'Transport/MQTT client Id.' },
        { name: 'transportFields', type: 'Record<string, any>', documentation: 'Dictionary of transport-specific fields/properties/headers. For Kafka, use "key" to define record key.' },
        { name: 'time', type: 'Date', documentation: 'Timestamp of incoming message; does nothing when sending.' },
        { name: 'externalSource', type: 'Array<{type: string}>', documentation: 'External source config for resolving _externalId_ placeholder. Defines which external ID type to use.' }
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
      properties: [],
      methods: [
        { name: 'setState', parameters: ['key', 'value'], returnType: 'void', documentation: 'Sets a value in the context\'s state.' },
        { name: 'getState', parameters: ['key'], returnType: 'any', documentation: 'Retrieves a value from the context\'s state.' },
        { name: 'getConfig', parameters: [], returnType: 'Record<string, any>', documentation: 'Retrieves the entire configuration map for the context.' },
        { name: 'getDTMAsset', parameters: ['assetId'], returnType: 'Record<string, any>', documentation: 'Lookup DTM Asset properties by asset ID.' },
        { name: 'getManagedObjectByDeviceId', parameters: ['deviceId'], returnType: 'any', documentation: 'Lookup a device from inventory cache by internal device ID.' },
        { name: 'getManagedObject', parameters: ['externalId'], returnType: 'any', documentation: 'Lookup a device from inventory cache by external ID.' }
      ],
      documentation: 'Context object providing state management, configuration access, and device lookup capabilities (renamed from FlowContext in v2.0).'
    },
    {
      name: 'FlowContext',
      isEnum: false,
      deprecated: true,
      properties: [],
      methods: [
        { name: 'setState', parameters: ['key', 'value'], returnType: 'void', documentation: '**DEPRECATED** - Use DataPrepContext.setState() instead.' },
        { name: 'getState', parameters: ['key'], returnType: 'any', documentation: '**DEPRECATED** - Use DataPrepContext.getState() instead.' },
        { name: 'getConfig', parameters: [], returnType: 'Record<string, any>', documentation: '**DEPRECATED** - Use DataPrepContext.getConfig() instead.' },
        { name: 'logMessage', parameters: ['msg'], returnType: 'void', documentation: '**DEPRECATED** - This method has been removed. Use console.log() instead.' },
        { name: 'lookupDTMAssetProperties', parameters: ['assetId'], returnType: 'Record<string, any>', documentation: '**DEPRECATED** - Use DataPrepContext.getDTMAsset() instead.' }
      ],
      documentation: '**DEPRECATED** - Use DataPrepContext instead. Will be removed in version 6.2.0.'
    },
    {
      name: 'InputMessage',
      isEnum: false,
      properties: [
        { name: 'sourcePath', type: 'string', documentation: 'An unique source path, example: MQTT Topic.' },
        { name: 'sourceId', type: 'string', documentation: 'The source id, example: MQTT client id.' },
        { name: 'payload', type: 'any', documentation: 'The payload of the message.' },
        { name: 'properties', type: 'Record<string, any>', documentation: 'A map of properties associated with the message.' }
      ],
      methods: [],
      documentation: 'Input message received by the flow function.'
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
    }
  ];

  // Add enums for specific values
  const enums: ClassOrEnum[] = [
    {
      name: 'CumulocityAction',
      isEnum: true,
      values: ['create', 'update'],
      documentation: 'Actions that can be performed on Cumulocity types.'
    },
    {
      name: 'CumulocityType',
      isEnum: true,
      values: ['measurement', 'alarm', 'event', 'managedObject', 'operation'],
      documentation: 'Common Cumulocity types (singular form).'
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
      parameters: ['payload', 'topic'],
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
    }
  ];

  // Return the provider object
  return {
    triggerCharacters: ['.', ' ', '('],
    provideCompletionItems: function (model, position, context, token) {
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
        const commonVars = [
          { name: 'msg', type: 'InputMessage', desc: 'Input message variable' },
          { name: 'outputMsg', type: 'OutputMessage', desc: 'Output message variable' },
          { name: 'c8yMsg', type: 'CumulocityObject', desc: 'Cumulocity message variable (use CumulocityObject, not deprecated CumulocityMessage)' },
          { name: 'deviceMsg', type: 'DeviceMessage', desc: 'Device message variable' },
          { name: 'dataPrepContext', type: 'DataPrepContext', desc: 'Data preparation context variable (renamed from FlowContext)' },
          { name: 'externalId', type: 'ExternalId', desc: 'External ID reference variable (v2.0+)' }
        ];

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
  };
}

/**
 * Creates a completion provider for custom JavaScript classes in Monaco Editor
 * @param {Monaco} monaco - The Monaco instance
 * @returns {Object} The completion provider
 */
export function createCompletionProviderSubstitutionAsCode(monaco) {
  // Register our custom classes and enums
  const customClasses: ClassOrEnum[] = [
    {
      name: 'RepairStrategy',
      isEnum: true,
      values: [
        'DEFAULT',
        'USE_FIRST_VALUE_OF_ARRAY',
        'USE_LAST_VALUE_OF_ARRAY',
        'IGNORE',
        'REMOVE_IF_MISSING_OR_NULL',
        'CREATE_IF_MISSING'
      ],
      documentation: 'Specifies how to repair a value during substitution.'
    },
    {
      name: 'TYPE',
      isEnum: true,
      values: [
        'ARRAY',
        'IGNORE',
        'NUMBER',
        'OBJECT',
        'TEXTUAL'
      ],
      documentation: 'Defines the data type for a substitute value.'
    },
    {
      name: 'SubstituteValue',
      isEnum: false,
      properties: [
        { name: 'value', type: 'any', documentation: 'The value to substitute.' },
        { name: 'type', type: 'TYPE', documentation: 'The type of the substitute value.' },
        { name: 'repairStrategy', type: 'RepairStrategy', documentation: 'Strategy for repairing missing or invalid values.' },
        { name: 'expandArray', type: 'boolean', documentation: 'Whether to expand arrays.' }
      ],
      methods: [
        { name: 'clone', parameters: [], returnType: 'SubstituteValue', documentation: 'Creates a clone of this SubstituteValue.' }
      ],
      documentation: 'Represents a value to substitute in the payload.'
    },
    {
      name: 'ArrayList',
      isEnum: false,
      properties: [
        { name: 'items', type: 'Array', documentation: 'The array of items.' }
      ],
      methods: [
        { name: 'add', parameters: ['item'], returnType: 'boolean', documentation: 'Adds an item to the list.' },
        { name: 'get', parameters: ['index'], returnType: 'any', documentation: 'Gets the item at the specified index.' },
        { name: 'size', parameters: [], returnType: 'number', documentation: 'Returns the number of items in the list.' },
        { name: 'isEmpty', parameters: [], returnType: 'boolean', documentation: 'Returns whether the list is empty.' }
      ],
      documentation: 'A JavaScript implementation of Java ArrayList.'
    },
    {
      name: 'HashMap',
      isEnum: false,
      properties: [
        { name: 'map', type: 'Object', documentation: 'The underlying map object.' }
      ],
      methods: [
        { name: 'put', parameters: ['key', 'value'], returnType: 'any', documentation: 'Adds a key-value pair to the map.' },
        { name: 'get', parameters: ['key'], returnType: 'any', documentation: 'Gets the value for the specified key.' },
        { name: 'containsKey', parameters: ['key'], returnType: 'boolean', documentation: 'Returns whether the map contains the specified key.' },
        { name: 'keySet', parameters: [], returnType: 'Array', documentation: 'Returns an array of keys in the map.' }
      ],
      documentation: 'A JavaScript implementation of Java HashMap.'
    },
    {
      name: 'SubstitutionResult',
      isEnum: false,
      properties: [
        { name: 'substitutions', type: 'HashMap', documentation: 'The substitutions map.' }
      ],
      methods: [
        { name: 'getSubstitutions', parameters: [], returnType: 'HashMap', documentation: 'Gets the substitutions map.' },
        { name: 'toString', parameters: [], returnType: 'string', documentation: 'Returns a string representation of the substitution result.' }
      ],
      documentation: 'Represents the result of a substitution operation.'
    },
    {
      name: 'JsonObject',
      isEnum: false,
      properties: [
        { name: 'data', type: 'Object', documentation: 'The underlying data object.' }
      ],
      methods: [
        { name: 'get', parameters: ['key'], returnType: 'any', documentation: 'Gets the value for the specified key.' }
      ],
      documentation: 'A simple wrapper for JSON objects.'
    },
    {
      name: 'SubstitutionContext',
      isEnum: false,
      properties: [
        { name: 'IDENTITY', type: 'string', documentation: 'The identity key (_IDENTITY_).' },
        { name: 'IDENTITY_EXTERNAL', type: 'string', documentation: 'The external identity key.' },
        { name: 'IDENTITY_C8Y', type: 'string', documentation: 'The C8Y identity key.' }
      ],
      methods: [
        { name: 'getGenericDeviceIdentifier', parameters: [], returnType: 'string', documentation: 'Gets the generic device identifier.' },
        { name: 'getExternalIdentifier', parameters: [], returnType: 'string', documentation: 'Gets the external identifier from the payload.' },
        { name: 'getC8YIdentifier', parameters: [], returnType: 'string', documentation: 'Gets the C8Y identifier from the payload.' },
        { name: 'getPayload', parameters: [], returnType: 'Object', documentation: 'Gets the JSON payload.' },
        { name: 'getTopic', parameters: [], returnType: 'string', documentation: 'Gets subscribe or publish topic of this mapping.' }
      ],
      documentation: 'Context for substitution operations.'
    }
  ];

  // Add the utility functions
  const utilityFunctions = [
    {
      name: 'tracePayload',
      parameters: ['ctx'],
      returnType: 'void',
      documentation: 'Logs payload details to the console for debugging purposes. Shows all keys in the payload and the identifiers from the context.',
      description: 'Trace payload contents'
    },
    {
      name: 'addSubstitution',
      parameters: ['result', 'key', 'value'],
      returnType: 'void',
      documentation: 'Adds a substitution value to the result for a specific key. Creates a new ArrayList for the key if it doesn\'t exist yet.',
      description: 'Add value to substitution result'
    },
    {
      name: 'addError',
      parameters: ['result', 'message'],
      returnType: 'void',
      documentation: 'Adds an error to the set of error messages. Creates a new HashSet for the key if it doesn\'t exist yet.',
      description: 'Add error message to substitution result'
    }
  ];

  // Return the provider object
  return {
    triggerCharacters: ['.', ' ', '('],
    provideCompletionItems: function (model, position, context, token) {
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
        const matchedClass = customClasses.find(cls => cls.name === objectName);

        // Object property/method completion
        if (matchedClass) {
          if (matchedClass.isEnum) {
            // Enum value completion
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
                sortText: `00-${index.toString().padStart(2, '0')}` // High priority sorting
              });
            });
          } else {
            // Class property and method completion
            const classObject = matchedClass as ClassDefinition;
            
            classObject.properties.forEach((prop, index) => {
              suggestions.push({
                label: prop.name,
                kind: monaco.languages.CompletionItemKind.Field,
                documentation: {
                  value: `**${prop.type}**\n\n${prop.documentation}`
                },
                insertText: prop.name,
                range: range,
                sortText: `01-${index.toString().padStart(2, '0')}` // High priority sorting
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
                  value: `**${method.returnType}** ${method.name}(${params})\n\n${method.documentation}`
                },
                insertText: method.parameters.length > 0
                  ? `${method.name}(${method.parameters.map((_, i) => `\${${i + 1}}`).join(', ')})`
                  : `${method.name}()`,
                insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
                range: range,
                sortText: `02-${index.toString().padStart(2, '0')}` // High priority sorting
              });
            });
          }
          return {
            suggestions,
            incomplete: false // Mark as complete to avoid other providers
          };
        }

        // For enum value access (e.g. RepairStrategy.DEFAULT)
        const enumClass = customClasses.find(cls => cls.isEnum && cls.name === objectName);
        if (enumClass) {
          const enumDef = enumClass as EnumDefinition;
          enumDef.values.forEach((value, index) => {
            suggestions.push({
              label: value,
              kind: monaco.languages.CompletionItemKind.EnumMember,
              documentation: {
                value: `${enumDef.name}.${value}`
              },
              insertText: value,
              range: range,
              sortText: `00-${index.toString().padStart(2, '0')}` // High priority sorting
            });
          });
          return {
            suggestions,
            incomplete: false // Mark as complete to avoid other providers 
          };
        }
      }

      // Global class/enum completion
      customClasses.forEach((cls, index) => {
        suggestions.push({
          label: cls.name,
          kind: cls.isEnum
            ? monaco.languages.CompletionItemKind.Enum
            : monaco.languages.CompletionItemKind.Class,
          documentation: {
            value: cls.documentation
          },
          insertText: cls.name,
          range: range,
          sortText: `03-${index.toString().padStart(2, '0')}` // High priority sorting
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
          sortText: `04-${index.toString().padStart(2, '0')}` // High priority sorting
        });
      });

      // Provide new object creation completions
      const newMatch = textUntilPosition.match(/new\s+(\w*)$/);
      if (newMatch) {
        customClasses.forEach((cls, index) => {
          if (!cls.isEnum) {
            const classObject = cls as ClassDefinition;
            const constructorParams = classObject.properties
              .map(p => p.name)
              .join(', ');

            suggestions.push({
              label: {
                label: cls.name,
                description: `new ${cls.name}(${constructorParams})`
              },
              kind: monaco.languages.CompletionItemKind.Constructor,
              documentation: {
                value: `Create a new ${cls.name} instance:\n\n\`\`\`javascript\nnew ${cls.name}(${constructorParams})\n\`\`\``
              },
              insertText: cls.name + (
                classObject.properties.length > 0
                  ? `(${classObject.properties.map((_, i) => `\${${i + 1}}`).join(', ')})`
                  : '()'
              ),
              insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
              range: range,
              sortText: `05-${index.toString().padStart(2, '0')}` // High priority sorting
            });
          }
        });

        return {
          suggestions,
          incomplete: false // Mark as complete to avoid other providers when dealing with constructors
        };
      }

      // Check if we're inside a function call for better parameter suggestions
      const funcCallMatch = textUntilPosition.match(/(\w+)\s*\(\s*$/);
      if (funcCallMatch) {
        const funcName = funcCallMatch[1];
        const matchedFunc = utilityFunctions.find(f => f.name === funcName);

        if (matchedFunc) {
          // We're inside a function call, offer parameter suggestions
          if (matchedFunc.name === 'tracePayload') {
            suggestions.push({
              label: 'ctx',
              kind: monaco.languages.CompletionItemKind.Variable,
              documentation: 'The SubstitutionContext object',
              insertText: 'ctx',
              range: range,
              sortText: '00-01' // High priority sorting
            });
          } else if (matchedFunc.name === 'addError') {
            suggestions.push({
              label: 'result',
              kind: monaco.languages.CompletionItemKind.Variable,
              documentation: 'The SubstitutionResult object',
              insertText: 'result',
              range: range,
              sortText: '00-01' // High priority sorting
            });
            suggestions.push({
              label: 'message',
              kind: monaco.languages.CompletionItemKind.Variable,
              documentation: 'The error message',
              insertText: '"${1:errorMessage}"',
              insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
              range: range,
              sortText: '00-02' // High priority sorting
            });
          } else if (matchedFunc.name === 'addSubstitution') {
            suggestions.push({
              label: 'result',
              kind: monaco.languages.CompletionItemKind.Variable,
              documentation: 'The SubstitutionResult object',
              insertText: 'result',
              range: range,
              sortText: '00-01' // High priority sorting
            });

            suggestions.push({
              label: 'key',
              kind: monaco.languages.CompletionItemKind.Variable,
              documentation: 'The key for the substitution',
              insertText: '"${1:keyName}"',
              insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
              range: range,
              sortText: '00-02' // High priority sorting
            });

            suggestions.push({
              label: 'new SubstituteValue()',
              kind: monaco.languages.CompletionItemKind.Value,
              documentation: 'Create a new SubstituteValue',
              insertText: 'new SubstituteValue(${1:value}, ${2:TYPE.TEXTUAL}, ${3:RepairStrategy.DEFAULT}, ${4:false})',
              insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
              range: range,
              sortText: '00-03' // High priority sorting
            });
          }

          if (suggestions.length > 0) {
            return {
              suggestions,
              incomplete: false // Mark as complete to avoid other providers 
            };
          }
        }
      }

      // If we have any suggestions, return them with a signal that they're complete
      if (suggestions.length > 0) {
        return {
          suggestions,
          incomplete: false // Mark as complete to avoid other providers
        };
      }

      // For other contexts, don't provide any suggestions
      return { suggestions: [] };
    }
  };
}