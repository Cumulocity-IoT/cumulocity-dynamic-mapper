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
 */

import {
  SmartFunctionInV2,
  CumulocityObject,
} from '../types';

/**
 * @name V2 Inbound Smart Function — typed config, state, and tuple return
 * @description Demonstrates SmartFunctionInV2 with a typed object generic.
 *   - config keys are typed — getConfig().mappingName is a string, not any
 *   - state keys are typed — getState('messageCount', 0) returns number
 *   - return type is a tuple — TypeScript enforces [measurement, managedObject] order
 * @templateType INBOUND_SMART_FUNCTION
 * @direction INBOUND
 *
 * Compiled JavaScript is identical to a plain (msg, context) => [...] arrow function.
 * The object generic only exists at type-checking time — no runtime overhead.
 */

/**
 * Inbound function that:
 * 1. Reads typed config (mappingName, tenant)
 * 2. Tracks message count in typed state
 * 3. Returns exactly [measurement, managedObject] — enforced by TypeScript
 *
 * Compare with V1 SmartFunctionIn where getConfig() / getState() return `any`.
 */
export const onMessage: SmartFunctionInV2<{
  // Tuple: enforces exactly one measurement followed by one managedObject.
  // Returning only a measurement, or returning them in the wrong order, is a
  // compile-time error — not a silent runtime bug.
  returns: [CumulocityObject<'measurement'>, CumulocityObject<'managedObject'>];
  config: {
    mappingName: string;
    tenant: string;
  };
  state: {
    messageCount: number;
    lastTemperature: number;
  };
}> = (msg, context) => {
  // getConfig() is typed — no 'as' cast needed
  const { mappingName } = context.getConfig();

  // getState() is typed — returns number, not any
  const count = context.getState('messageCount', 0) + 1;
  const temp = (msg.payload['temperature'] as number) ?? 0;

  // setState() enforces the TState shape — setState('messageCount', 'oops') is an error
  context.setState('messageCount', count);
  context.setState('lastTemperature', temp);

  console.log(`[${mappingName}] message #${count}, temp=${temp}`);

  const clientId = context.getClientId() ?? msg.payload['clientId'] as string;

  // Return tuple — TypeScript verifies order and count at compile time
  return [
    {
      cumulocityType: 'measurement',
      action: 'create',
      payload: {
        type: 'c8y_TemperatureMeasurement',
        time: new Date().toISOString(),
        c8y_Temperature: { T: { value: temp, unit: 'C' } },
        c8y_Statistics: { messageCount: count },
      },
      externalSource: [{ type: 'c8y_Serial', externalId: clientId }],
    },
    {
      cumulocityType: 'managedObject',
      action: 'update',
      payload: {
        c8y_LastTemperature: { value: temp, unit: 'C', time: new Date().toISOString() },
      },
      externalSource: [{ type: 'c8y_Serial', externalId: clientId }],
    },
  ];
};

export default onMessage;
