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
// camel-case.pipe.ts
import { Pipe, PipeTransform } from '@angular/core';

@Pipe({
  name: 'base64decode',
  standalone: false
})
export class Base64DecodePipe implements PipeTransform {
  transform(value: string): string {
    try {
      return atob(value);
    } catch (e) {
      return value;
    }
  }
}
