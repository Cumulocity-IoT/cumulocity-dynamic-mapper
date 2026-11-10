/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *  @authors Christof Strack, Stefan Witschel
 *
 */

import { Component, Input, ChangeDetectionStrategy } from '@angular/core';
import { C8yTranslateModule, IconDirective } from '@c8y/ngx-components';
import { CommonModule } from '@angular/common';
import { KpiDetails } from '../../shared/monitoring.service';

@Component({
  selector: 'd11r-kpi-item-card',
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [IconDirective, C8yTranslateModule, CommonModule,],
  templateUrl: './kpi-item-card.component.html',
  host: { class: 'card m-b-0 fit-w fit-h' }
})
export class KpiItemCardComponent {
  /**
   * The label of the service (already translated).
   */
  @Input() kpi: KpiDetails;
}