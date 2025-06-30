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


/**
 * Creates a completion provider for custom JavaScript classes in Monaco Editor
 * @param {Monaco} monaco - The Monaco instance
 * @returns {Object} The completion provider
 */
/**
 * Creates a completion provider for custom JavaScript classes in Monaco Editor
 * @param {Monaco} monaco - The Monaco instance
 * @returns {Object} The completion provider
 */
export function createCompletionProvider(monaco) {
  // Register our custom classes and enums
  const customClasses = [
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
        { name: 'getPayload', parameters: [], returnType: 'Object', documentation: 'Gets the JSON payload.' }
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
            matchedClass.values.forEach((value, index) => {
              suggestions.push({
                label: value,
                kind: monaco.languages.CompletionItemKind.EnumMember,
                documentation: {
                  value: `${matchedClass.name}.${value}`
                },
                insertText: value,
                range: range,
                sortText: `00-${index.toString().padStart(2, '0')}` // High priority sorting
              });
            });
          } else {
            // Class property and method completion
            if (matchedClass.properties) {
              matchedClass.properties.forEach((prop, index) => {
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
            }

            if (matchedClass.methods) {
              matchedClass.methods.forEach((method, index) => {
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
          }
          return {
            suggestions,
            incomplete: false // Mark as complete to avoid other providers
          };
        }

        // For enum value access (e.g. RepairStrategy.DEFAULT)
        const enumClass = customClasses.find(cls => cls.isEnum && cls.name === objectName);
        if (enumClass) {
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
            const constructorParams = cls.properties
              ? cls.properties.map(p => p.name).join(', ')
              : '';

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
                cls.properties && cls.properties.length > 0
                  ? `(${cls.properties.map((_, i) => `\${${i + 1}}`).join(', ')})`
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

