/*
 * Copyright (c) 2022 Software AG, Darmstadt, Germany and/or Software AG USA Inc., Reston, VA, USA,
 * and/or its subsidiaries and/or its affiliates and/or their licensors.
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
import * as _ from 'lodash';
import { Mapping, API } from '../../../shared';

export const IDENTITY = '_IDENTITY_';
export const TOKEN_TOPIC_LEVEL = '_TOPIC_LEVEL_';
export const TOKEN_CONTEXT_DATA = '_CONTEXT_DATA_';
export const CONTEXT_DATA_KEY_NAME = 'key';
export const TIME = 'time';

export const TOPIC_WILDCARD_MULTI = '#';
export const TOPIC_WILDCARD_SINGLE = '+';

export function randomString(){
  return Math.floor(100000 + Math.random() * 900000).toString()
}

export function patchC8YTemplateForTesting(template: object, mapping: Mapping) {
  const identifier = randomString();
  _.set(template, API[mapping.targetAPI].identifier, identifier);
  _.set(template, `${IDENTITY}.c8ySourceId`, identifier);
}