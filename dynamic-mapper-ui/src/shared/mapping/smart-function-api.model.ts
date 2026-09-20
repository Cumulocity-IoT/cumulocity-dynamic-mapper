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

/**
 * Shape of the Smart Function API table that the mapping editor's completion and hover providers
 * consume (see stepper.model.ts).
 *
 * It lives in its own file so that the generated table can be typed against it without a circular
 * import: the generator emits `ClassOrEnum[]` referencing this module, which makes TypeScript
 * verify that generated data actually satisfies what the providers expect.
 */

export interface BaseClass {
  name: string;
  documentation: string;
  deprecated?: boolean;
}

export interface ClassDefinition extends BaseClass {
  isEnum: false;
  properties: Array<{ name: string; type: string; documentation: string }>;
  methods: Array<{ name: string; parameters: string[]; returnType: string; documentation: string }>;
}

export interface EnumDefinition extends BaseClass {
  isEnum: true;
  values: string[];
}

export type ClassOrEnum = ClassDefinition | EnumDefinition;
