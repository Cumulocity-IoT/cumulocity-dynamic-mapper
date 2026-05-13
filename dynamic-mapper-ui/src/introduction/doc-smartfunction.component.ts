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

import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { CoreModule } from '@c8y/ngx-components';
import { NODE3 } from '../shared';

@Component({
  selector: 'd11r-doc-smartfunction',
  templateUrl: './doc-smartfunction.component.html',
  styleUrls: ['./doc-shared.css'],
  standalone: true,
  imports: [CoreModule, RouterLink]
})
export class DocSmartFunctionComponent {
  ROUTE_SERVICE_CONFIGURATION: string = `/c8y-pkg-dynamic-mapper/${NODE3}/serviceConfiguration/general`;
  ROUTE_CODE_TEMPLATES_INBOUND_SMART_FUNCTION: string = `/c8y-pkg-dynamic-mapper/${NODE3}/codeTemplate/INBOUND_SMART_FUNCTION`;

  scrollToElement(elementId: string): void {
    const element = document.getElementById(elementId);
    if (element) {
      window.scrollTo({ top: element.offsetTop - 80, behavior: 'smooth' });
    }
  }
}
