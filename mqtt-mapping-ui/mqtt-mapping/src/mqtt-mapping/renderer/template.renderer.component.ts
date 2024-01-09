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
import { Component } from "@angular/core";
import { CellRendererContext } from "@c8y/ngx-components";

@Component({
  template: `
    <textarea
      class="text-monospace font-smaller"
      style="border:none; background-color: transparent;"
      rows="3"
      cols="40"
      title="{{ json }}"
      readonly
      >{{ json }}</textarea
    >
  `,
})
export class TemplateRendererComponent {
  public json: string;
  constructor(public context: CellRendererContext) {
    this.json = JSON.stringify(JSON.parse(context.value), null, 4);
  }
}