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

import { Pipe, PipeTransform } from "@angular/core";

// filter-json.pipe.ts
@Pipe({
    name: 'filterJson'
  })
  export class FilterJsonPipe implements PipeTransform {
    transform(value: any, excludedProperties: string[] = [], exclude: boolean = false): any {
      if (!value) return value;
  
      if (!excludedProperties.length) {
        return value;
      }
  
      const filteredObject: any = {};
      
      Object.keys(value).forEach(key => {
        if (exclude) {
          // Exclude mode: only add if key is not in excludedProperties
          if (!excludedProperties.includes(key)) {
            filteredObject[key] = value[key];
          }
        } else {
          // Include mode: only add if key is in excludedProperties
          if (excludedProperties.includes(key)) {
            filteredObject[key] = value[key];
          }
        }
      });
  
      return filteredObject;
    }
  }