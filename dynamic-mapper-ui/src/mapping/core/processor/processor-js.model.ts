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
 * @deprecated This file is deprecated and will be removed in a future version.
 * Please import from './java-simulation/java-types' instead.
 *
 * This file now re-exports from the consolidated java-simulation module to maintain
 * backward compatibility during the transition period.
 *
 * Migration guide:
 * - Change: import { SubstitutionContext } from './processor-js.model'
 * - To:     import { SubstitutionContext } from './java-simulation/java-types'
 *
 * @since 6.1.6 - Deprecated in favor of java-simulation module
 * @willBeRemovedIn 7.0.0
 */

// Re-export all types from the new consolidated module
export {
  Java,
  RepairStrategy,
  TYPE,
  SubstituteValue,
  ArrayList,
  HashMap,
  HashSet,
  SubstitutionResult,
  JsonObject,
  SubstitutionContext
} from './java-simulation/java-types';
