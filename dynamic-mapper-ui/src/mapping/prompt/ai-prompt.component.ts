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
import {
  Component,
  EventEmitter,
  Input,
  OnInit,
  Output,
  ViewEncapsulation
} from '@angular/core';
import { DeviceGridService, } from '@c8y/ngx-components/device-grid';
import { Mapping, MappingSubstitution } from '../../shared';

@Component({
  selector: 'd11r-mapping-ai-prompt',
  templateUrl: 'ai-prompt.component.html',
  styleUrls: ['../shared/mapping.style.css'],
  encapsulation: ViewEncapsulation.None,
  standalone: false
})
export class AIPromptComponent implements OnInit {
  @Input() mapping: Mapping;

  @Output() cancel = new EventEmitter<any>();
  @Output() commit = new EventEmitter<MappingSubstitution[]>();
  substitutions: MappingSubstitution[] = [];

  constructor(protected deviceGridService: DeviceGridService) {
  }
  ngOnInit(): void {
    console.log(this.mapping);
  }

  clickedUpdateSubscription() {
 
    this.commit.emit(this.substitutions);
  }

  clickedCancel() {
    this.cancel.emit();
  }

}
