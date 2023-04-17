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

/**
 * The example component for custom header renderer.
 * The header text is taken from `this.context.property` which contains current column object.
 * Additionally the header has custom icon element and styled span element.
 */
@Component({
  template: `
    <span
      style="text-transform: uppercase; font-variant: small-caps; text-decoration: underline;"
    >
      {{ context.property.header }}
    </span>
  `,
})
export class TypeHeaderCellRendererComponent {
  constructor(public context: CellRendererContext) {}
}
