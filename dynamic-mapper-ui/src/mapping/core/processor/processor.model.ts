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
import { AlertService } from '@c8y/ngx-components';
import * as _ from 'lodash';
import { getTypeOf, randomIdAsString } from '../../../mapping/shared/util';
import { API, getPathTargetForDeviceIdentifiers, Mapping, Substitution, MappingType, RepairStrategy } from '../../../shared';
import { SubstitutionContext } from './processor-js.model';

export interface DynamicMapperRequest {
  predecessor?: number;
  method?: string;
  api?: string;
  sourceId?: any;
  externalId?: string;
  externalIdType?: string;
  request?: any;
  response?: any;
  error?: string;
  hidden?: boolean;
}

export interface ProcessingContext {
  mapping: Mapping;
  topic: string;
  resolvedPublishTopic?: string;
  payload?: JSON;
  requests?: DynamicMapperRequest[];
  errors?: string[];
  warnings?: string[];
  processingType?: ProcessingType;
  mappingType: MappingType;
  processingCache: Map<string, SubstituteValue[]>;
  sendPayload?: boolean;
  sourceId?: string;
  logs?: any[];
  deviceName?: string;
  deviceType?: string;
}

export interface TestContext {
  mapping: Mapping;
  payload: string;
  send?: boolean;
}

export interface TestResult {
  success: boolean;
  requests: DynamicMapperRequest[];
  errors: string[];
  warnings?: string[];
  logs?: string[];
}

export enum ProcessingType {
  UNDEFINED,
  ONE_DEVICE_MULTIPLE_VALUE,
  MULTIPLE_DEVICE_ONE_VALUE,
  MULTIPLE_DEVICE_MULTIPLE_VALUE
}

export enum SubstituteValueType {
  NUMBER,
  TEXTUAL,
  BOOLEAN,
  OBJECT,
  IGNORE,
  ARRAY
}

export interface SubstituteValue {
  value: any;
  type?: SubstituteValueType;
  repairStrategy: RepairStrategy;
}export function getTypedValue(subValue: SubstituteValue): any {
  if (subValue.type == SubstituteValueType.NUMBER) {
    return Number(subValue.value);
  } else if (subValue.type == SubstituteValueType.TEXTUAL) {
    return String(subValue.value);
  } else {
    return subValue.value;
  }
}

export const isNumeric = (num: any) => (typeof num === 'number' || (typeof num === 'string' && num.trim() !== '')) &&
  !isNaN(num as number);


export function processSubstitute(processingCacheEntry: SubstituteValue[], extractedSourceContent: any, substitution: Substitution) {
  if (getTypeOf(extractedSourceContent) == 'null') {
    processingCacheEntry.push({
      value: extractedSourceContent,
      type: SubstituteValueType.IGNORE,
      repairStrategy: substitution.repairStrategy
    });
    console.error(
      'No substitution for: ',
      substitution.pathSource
    );
  } else if (getTypeOf(extractedSourceContent) == 'String') {
    processingCacheEntry.push({
      value: extractedSourceContent,
      type: SubstituteValueType.TEXTUAL,
      repairStrategy: substitution.repairStrategy
    });
  } else if (getTypeOf(extractedSourceContent) == 'Number') {
    processingCacheEntry.push({
      value: extractedSourceContent,
      type: SubstituteValueType.NUMBER,
      repairStrategy: substitution.repairStrategy
    });
  } else if (getTypeOf(extractedSourceContent) == 'Array') {
    processingCacheEntry.push({
      value: extractedSourceContent,
      type: SubstituteValueType.ARRAY,
      repairStrategy: substitution.repairStrategy
    });
  } else if (getTypeOf(extractedSourceContent) == 'Object') {
    processingCacheEntry.push({
      value: extractedSourceContent,
      type: SubstituteValueType.OBJECT,
      repairStrategy: substitution.repairStrategy
    });
  } else if (getTypeOf(extractedSourceContent) == 'Boolean') {
    processingCacheEntry.push({
      value: extractedSourceContent,
      type: SubstituteValueType.BOOLEAN,
      repairStrategy: substitution.repairStrategy
    });
  } else {
    console.warn(
      `Since result is not (number, array, textual, object), it is ignored: ${extractedSourceContent}`
    );
  }
}

export function getDeviceEntries(context: ProcessingContext): SubstituteValue[] {
  const { processingCache, mapping } = context;
  const pathsTargetForDeviceIdentifiers: string[] = getPathTargetForDeviceIdentifiers(context);
  const firstPathTargetForDeviceIdentifiers = pathsTargetForDeviceIdentifiers.length > 0
    ? pathsTargetForDeviceIdentifiers[0]
    : null;
  const deviceEntries: SubstituteValue[] = processingCache.get(
    firstPathTargetForDeviceIdentifiers
  );
  return deviceEntries;
}

export function prepareAndSubstituteInPayload(
  context: ProcessingContext,
  substitute: SubstituteValue,
  payloadTarget: JSON,
  pathTarget: string,
  alert: AlertService
) {
  if (TOKEN_CONTEXT_DATA + ".deviceName" == pathTarget) {
    context.deviceName = substitute.value;
  } else if (TOKEN_CONTEXT_DATA + ".deviceType" == pathTarget) {
    context.deviceType = substitute.value;
  } else {
    substituteValueInPayload(substitute, payloadTarget, pathTarget);
  }
}

export const TOKEN_IDENTITY = '_IDENTITY_';
export const TOKEN_TOPIC_LEVEL = '_TOPIC_LEVEL_';
export const TOKEN_CONTEXT_DATA = '_CONTEXT_DATA_';
export const CONTEXT_DATA_KEY_NAME = 'key';
export const KEY_TIME = 'time';

export const TOPIC_WILDCARD_MULTI = '#';
export const TOPIC_WILDCARD_SINGLE = '+';


export function substituteValueInPayload(substitute: SubstituteValue, payloadTarget: JSON, pathTarget: string) {
  const subValueMissingOrNull: boolean = !substitute || substitute.value == null;

  if (pathTarget == '$') {
    Object.keys(getTypedValue(substitute)).forEach((key) => {
      payloadTarget[key] = getTypedValue(substitute)[key as keyof unknown];
    });
  } else {
    if ((substitute.repairStrategy == RepairStrategy.REMOVE_IF_MISSING_OR_NULL &&
      subValueMissingOrNull)) {
      _.unset(payloadTarget, pathTarget);
    } else if (substitute.repairStrategy == RepairStrategy.CREATE_IF_MISSING) {
      // const pathIsNested: boolean = keys.includes('.') || keys.includes('[');
      // if (pathIsNested) {
      //   throw new Error('Can only create new nodes on the root level!');
      // }
      // jsonObject.put("$", keys, sub.typedValue());
      _.set(payloadTarget, pathTarget, getTypedValue(substitute));
    } else {
      if (_.has(payloadTarget, pathTarget)) {
        _.set(payloadTarget, pathTarget, getTypedValue(substitute));
      } else {
        // alert.warning(`Message could NOT be parsed, ignoring this message: Path: ${keys} not found!`);
        throw new Error(
          `Message could NOT be parsed, ignoring this message: Path: ${pathTarget} not found!`
        );
      }
    }
  }
}

export function sortProcessingCache(context: ProcessingContext): void {
  // Convert Map to array of entries, sort, then create new Map
  const sortedEntries = Array.from(context.processingCache.entries())
    .sort(([keyA], [keyB]) => keyA.localeCompare(keyB));

  // Clear the original map and repopulate with sorted entries
  context.processingCache.clear();
  sortedEntries.forEach(([key, value]) => {
    context.processingCache.set(key, value);
  });
}

export function patchC8YTemplateForTesting(template: object, mapping: Mapping) {
  const identifier = randomIdAsString();
  _.set(template, API[mapping.targetAPI].identifier, identifier);
  _.set(template, `${TOKEN_IDENTITY}.c8ySourceId`, identifier);
}


export interface EvaluationError {
  message: string;
  stack: string | null;
  location: any | null;
}

export interface EvaluationResult {
  success: boolean;
  result?: any;
  error?: EvaluationError;
  logs: string[];
}

/**
 * Evaluates JavaScript code with arguments and a timeout
 * @param codeString The JavaScript code to evaluate
 * @param args Arguments to pass to the code
 * @returns Promise resolving to evaluation result
 */

export function evaluateWithArgsWebWorker(codeString: string, ctx: SubstitutionContext): Promise<EvaluationResult> {
  // Serialize the SubstitutionContext object
  const serializableCtx = {
    identifier: ctx.getGenericDeviceIdentifier ? ctx.getGenericDeviceIdentifier() : ctx['deviceIdentifier'],
    payload: ctx.getPayload ? ctx.getPayload() : ctx['payload'],
    topic: ctx.getTopic ? ctx.getTopic() : ctx['topic'],
  };

  // Define the timeout duration
  const timeoutMs = 250;

  // Create a promise for the evaluation
  return Promise.race([
    // Timeout promise
    new Promise<EvaluationResult>(resolve => setTimeout(() => {
      resolve({
        success: false,
        error: {
          message: `Execution timed out after ${timeoutMs}ms`,
          stack: null,
          location: null
        },
        logs: ["Execution timed out"]
      });
    }, timeoutMs)),

    // Worker promise
    new Promise<EvaluationResult>((resolve) => {
      const logs: string[] = [];
      let worker: Worker | null = null;
      let workerUrl: string | null = null;
      let timeoutId: number | null = null;

      try {
        // Create worker script with improved log handling
        const workerScript = `
          self.onmessage = function(event) {
            // Set up console capture
            const logs = [];
            const consoleLog = function(...args) {
              const logString = args.map(arg => {
                if (arg === undefined) return 'undefined';
                if (arg === null) return 'null';
                if (typeof arg === 'object') {
                  try {
                    return JSON.stringify(arg);
                  } catch (e) {
                    return String(arg);
                  }
                }
                return String(arg);
              }).join(' ');
              
              logs.push(logString);
              
              // Real-time log streaming to main thread
              self.postMessage({ 
                type: 'log', 
                data: logString 
              });
            };
            
            // Create console object with all common methods
            const console = {
              log: consoleLog,
              info: consoleLog,
              warn: consoleLog,
              error: consoleLog,
              debug: consoleLog
            };
            
            /**
             * JavaScript equivalent of the Java SubstitutionContext class
             */
            class SubstitutionContext {
              IDENTITY = "_IDENTITY_";
              #payload;  // Using private class field (equivalent to private final in Java)
              #genericDeviceIdentifier;
              #topic;

              // Constants
              IDENTITY_EXTERNAL = this.IDENTITY + ".externalId";
              IDENTITY_C8Y = this.IDENTITY + ".c8ySourceId";

              /**
               * Constructor for the SubstitutionContext class
               * @param {string} genericDeviceIdentifier - The generic device identifier
               * @param {string} payload - The JSON object representing the data
               * @param {string} topic - The publish/ subscribe topic
               */
              constructor(genericDeviceIdentifier, payload, topic) {
                this.#payload = (payload || {});
                this.#genericDeviceIdentifier = genericDeviceIdentifier;
                this.#topic = topic;
              }

              /**
               * Gets the generic device identifier
               * @returns {string} The generic device identifier
               */
              getGenericDeviceIdentifier() {
                return this.#genericDeviceIdentifier;
              }

              /**
               * Gets the topic
               * @returns {string} The topic
               */
              getTopic() {
                return this.#topic;
              }

              /**
               * Gets the external identifier from the JSON object
               * @returns {string|null} The external identifier or null if not found
               */
              getExternalIdentifier() {
                try {
                  const parsedPayload = JSON.parse(this.#payload);
                  return parsedPayload[this.IDENTITY]?.["externalId"] || null;
                } catch (e) {
                  console.debug("Error retrieving external identifier", e);
                  return null;
                }
              }
              /**
               * Gets the C8Y identifier from the JSON object
               * @returns {string|null} The C8Y identifier or null if not found
               */
              getC8YIdentifier() {
                try {
                  const parsedPayload = JSON.parse(this.#payload);
                  // Optional chaining will return undefined if any part of the chain is null/undefined
                  return parsedPayload[this.IDENTITY]?.["c8ySourceId"] || null;
                } catch (e) {
                  console.debug("Error retrieving c8y identifier", e);
                  return null;
                }
              }
              /**
               * Gets the JSON object
               * @returns {Object} The JSON object
               */
              getPayload() {
                return this.#payload;
              }
            }

            const { code, ctx } = event.data;
            
            try {
              // Debug marker to verify code execution context
              // Create context object
              const arg0 = new SubstitutionContext(ctx.identifier, ctx.payload, ctx.topic);
              
              // Use Function constructor with console explicitly passed
              const fn = new Function('arg0', 'console', code);
              const result = fn(arg0, console);
              
              // Send back successful result with logs
              self.postMessage({ 
                type: 'result',
                success: true, 
                result,
                logs
              });
            } catch (error) {
              console.error("Error in worker:", error.message);
              
            // Extract and fix line numbers in a more robust way
              let errorStack = error.stack || "";
              let errorMessage = error.message || "Unknown error";
              let lineInfo = null;
              
              // Log the original error stack for debugging
              // console.error("Original error stack:", errorStack);
              
              // Parse line numbers more robustly - handle different browser formats
              const lineMatches = errorStack.match(/<anonymous>:(\\d+):(\\d+)/);
              const evalMatches = errorStack.match(/eval.*<anonymous>:(\\d+):(\\d+)/);
              const functionMatches = errorStack.match(/Function:(\\d+):(\\d+)/);
              
              let adjustedLine = null;
              let column = null;
              
              if (lineMatches) {
                adjustedLine = Math.max(1, parseInt(lineMatches[1]) - 2);
                column = parseInt(lineMatches[2]);
                // console.error("Found line from <anonymous>:", adjustedLine);
              } else if (evalMatches) {
                adjustedLine = Math.max(1, parseInt(evalMatches[1]) - 2);
                column = parseInt(evalMatches[2]);
                // console.error("Found line from eval:", adjustedLine);
              } else if (functionMatches) {
                adjustedLine = Math.max(1, parseInt(functionMatches[1]) - 2);
                column = parseInt(functionMatches[2]);
                // console.error("Found line from Function:", adjustedLine);
              } else {
                // console.error("No line number found in error stack.");
              }
              
              // Add line info to the error message if found
              if (adjustedLine !== null) {
                errorMessage = errorMessage + " (at line " + adjustedLine + 
                              (column ? ", column " + column : "") + ")";
                
                lineInfo = { line: adjustedLine, column: column };
              }
              
              // Send back error with adjusted information
              self.postMessage({ 
                type: 'result', 
                success: false, 
                error: {
                  message: errorMessage,
                  stack: errorStack,
                  location: lineInfo
                },
                logs
              });
            }
          };
        `;

        // Rest of your code remains the same
        const blob = new Blob([workerScript], { type: 'application/javascript' });
        workerUrl = URL.createObjectURL(blob);

        // Create and start the worker
        worker = new Worker(workerUrl);

        // Set up timeout to terminate worker
        timeoutId = window.setTimeout(() => {
          if (worker) {
            worker.terminate();
          }
          if (workerUrl) {
            URL.revokeObjectURL(workerUrl);
          }

          resolve({
            success: false,
            error: {
              message: `Execution timed out after ${timeoutMs}ms`,
              stack: null,
              location: null
            },
            logs
          });
        }, timeoutMs);

        // Handle messages from the worker - IMPROVED LOG HANDLING
        worker.onmessage = (e) => {
          const { type, data } = e.data;

          if (type === 'log') {
            // Add log to our collection
            logs.push(data);
            // You can also log to the main thread console for debugging
            console.log(`[Worker Log] ${data}`);
          } else if (type === 'result') {
            // Clear timeout
            if (timeoutId !== null) {
              clearTimeout(timeoutId);
              timeoutId = null;
            }

            // Clean up
            if (worker) {
              worker.terminate();
              worker = null;
            }
            if (workerUrl) {
              URL.revokeObjectURL(workerUrl);
              workerUrl = null;
            }

            // IMPORTANT: Use the logs from the worker result
            // along with any logs we collected from streaming
            const workerLogs = e.data.logs || [];
            const allLogs = [...logs];

            // Avoid duplicate logs (might happen with the streaming approach)
            for (const wLog of workerLogs) {
              if (!logs.includes(wLog)) {
                allLogs.push(wLog);
              }
            }

            resolve({
              success: e.data.success,
              result: e.data.result,
              error: e.data.error,
              logs: allLogs
            } as EvaluationResult);
          }
        };

        // Handle worker errors
        worker.onerror = (error) => {
          if (timeoutId !== null) {
            clearTimeout(timeoutId);
            timeoutId = null;
          }

          if (worker) {
            worker.terminate();
            worker = null;
          }
          if (workerUrl) {
            URL.revokeObjectURL(workerUrl);
            workerUrl = null;
          }

          resolve({
            success: false,
            error: {
              message: `Worker error: ${error.message}`,
              stack: null,
              location: null
            },
            logs
          });
        };

        // Start the worker with code and context
        worker.postMessage({
          code: codeString,
          ctx: serializableCtx
        });
      } catch (error: any) {
        // Clean up if creation fails
        if (timeoutId !== null) {
          clearTimeout(timeoutId);
        }
        if (worker) {
          worker.terminate();
        }
        if (workerUrl) {
          URL.revokeObjectURL(workerUrl);
        }

        resolve({
          success: false,
          error: {
            message: `Failed to create or run worker: ${error.message}`,
            stack: error.stack || null,
            location: null
          },
          logs
        });
      }
    })
  ]);
}