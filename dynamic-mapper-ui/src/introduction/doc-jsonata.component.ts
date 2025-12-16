/* eslint-disable @angular-eslint/no-empty-lifecycle-method */
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

import { Component, OnInit } from '@angular/core';
import { CoreModule } from '@c8y/ngx-components';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'd11r-doc-jsonata',
  templateUrl: './doc-jsonata.component.html',
  styleUrl: './doc-shared.css',
  standalone: true,
  imports: [CoreModule, CommonModule, RouterLink]
})
export class DocJSONataComponent implements OnInit {

  constructor() {}

  ngOnInit() {}

  scrollToElement(elementId: string): void {
    const element = document.getElementById(elementId);
    if (element) {
      element.scrollIntoView({
        behavior: 'smooth',
        block: 'start'
      });
    }
  }
}
