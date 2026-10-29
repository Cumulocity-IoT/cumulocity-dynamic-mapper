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

import { CommonModule } from '@angular/common';
import { Component, Input } from '@angular/core';
import { FormGroup } from '@angular/forms';
import { CoreModule } from '@c8y/ngx-components';
import { Observable } from 'rxjs';
import { Mapping } from '../../shared';

/**
 * Extension name / event / parameter selectors for the Select Templates step (stepper) and tab
 * (unified editor). One instance is rendered per side (source / target); the host decides via
 * {@link showSelectors} which side carries them. Also renders the PROTOBUF_INTERNAL note, which
 * replaces the template editor when the payload structure comes from the Protobuf schema.
 *
 * `dataCyPrefix` keeps the data-cy attributes distinct per host (e2e selectors).
 */
@Component({
  selector: 'd11r-mapping-extension-selector',
  templateUrl: 'mapping-extension-selector.component.html',
  standalone: true,
  imports: [CoreModule, CommonModule]
})
export class MappingExtensionSelectorComponent {
  @Input() form!: FormGroup;
  @Input() side: 'source' | 'target' = 'source';
  @Input() mapping!: Mapping;
  @Input() showSelectors = false;
  @Input() showInternalNote = false;
  @Input() isLoading = false;
  @Input() hasParameter = false;
  @Input() extensionItems: string[] = [];
  @Input() extensionEvents!: Observable<{ label: string; value: string }[]>;
  @Input() dataCyPrefix = 'dm-mapping-stepper';
}
