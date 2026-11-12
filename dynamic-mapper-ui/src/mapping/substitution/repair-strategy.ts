/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
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

import { gettext } from '@c8y/ngx-components/gettext';
import { RepairStrategy } from '../../shared';

/** Repair strategies that collapse an extracted array to one element. Meaningless together with "Expand as array". */
export const ARRAY_ONLY_STRATEGIES: string[] = [
  RepairStrategy.USE_FIRST_VALUE_OF_ARRAY,
  RepairStrategy.USE_LAST_VALUE_OF_ARRAY
];

/** Kept in sync with the backend enum `dynamic.mapper.processor.model.RepairStrategy`. */
export const REPAIR_STRATEGY_DESCRIPTIONS: Record<string, string> = {
  [RepairStrategy.DEFAULT]:
    gettext('Write the extracted value to the target path as-is. The path must already exist in the target template.'),
  [RepairStrategy.USE_FIRST_VALUE_OF_ARRAY]:
    gettext('If the extracted value is an array, use only its first element. Not applicable when "Expand as array" is set.'),
  [RepairStrategy.USE_LAST_VALUE_OF_ARRAY]:
    gettext('If the extracted value is an array, use only its last element. Not applicable when "Expand as array" is set.'),
  [RepairStrategy.IGNORE]:
    gettext('If the extracted value is missing or null, skip the substitution and keep whatever the target template defines.'),
  [RepairStrategy.REMOVE_IF_MISSING_OR_NULL]:
    gettext('If the extracted value is missing or null, delete the target node from the target template.'),
  [RepairStrategy.CREATE_IF_MISSING]:
    gettext('Create the target node, including missing parent nodes, if it does not exist in the target template.')
};
