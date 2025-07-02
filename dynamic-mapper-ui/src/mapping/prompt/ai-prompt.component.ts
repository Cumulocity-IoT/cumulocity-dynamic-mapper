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
  inject,
  Input,
  OnInit,
  ViewEncapsulation
} from '@angular/core';
import { DeviceGridService, } from '@c8y/ngx-components/device-grid';
import { Mapping, MappingSubstitution } from '../../shared';
import { BottomDrawerRef } from '@c8y/ngx-components';

@Component({
  selector: 'd11r-mapping-ai-prompt',
  templateUrl: 'ai-prompt.component.html',
  styleUrls: ['../shared/mapping.style.css'],
  encapsulation: ViewEncapsulation.None,
  standalone: false
})
export class AIPromptComponent implements OnInit {

  bottomDrawerRef = inject(BottomDrawerRef);
  deviceGridService = inject(DeviceGridService);

  @Input() mapping: Mapping;

  private _save: (value: MappingSubstitution[]) => void;
  private _cancel: (reason?: any) => void;
  
  result: Promise<MappingSubstitution[]> = new Promise((resolve, reject) => {
    this._save = resolve;
    this._cancel = reject;
  });

  substitutions: MappingSubstitution[] = [];


  ngOnInit(): void {
    console.log(this.mapping);
  }

  save() {
    this._save(this.substitutions);
    this.bottomDrawerRef.close();
  }

  cancel() {
    this._cancel("User canceled");
    this.bottomDrawerRef.close();
  }

}
