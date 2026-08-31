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
  Output,
  ViewEncapsulation
} from '@angular/core';
import { CoreModule } from '@c8y/ngx-components';

@Component({
  selector: 'd11r-type-selector',
  host: { class: 'flex-grow d-col fit-h' },
  templateUrl: 'type-selector.component.html',
  styleUrls: ['../../shared/mapping.style.css'],
  encapsulation: ViewEncapsulation.None,
  standalone: true,
  imports:[CoreModule]
})
export class TypeSelectorComponent {
  @Input() set typeList(list: string[]) {
    this.typeListInternal = [...list];
  }
  get typeList(): string[] {
    return this.typeListInternal;
  }

  @Output() cancel = new EventEmitter<any>();
  @Output() commit = new EventEmitter<string[]>();

  typeListInternal: string[] = [];

  trackByFn(index: any, _item: any) {
    return index;
  }

  add() {
    this.typeListInternal.push("");
  }

  remove(index: number) {
    this.typeListInternal = this.typeListInternal.filter((_, i) => i !== index);
  }

  /**
   * True when either no type filter is defined (subscribe to all types, a valid
   * backend state) or at least one non-blank type has been entered. Gates Save.
   */
  get hasValidType(): boolean {
    return this.typeListInternal.length === 0 || this.typeListInternal.some(t => !!t?.trim());
  }

  clickedUpdateSubscription() {
    const cleaned = this.typeListInternal.map(t => t?.trim()).filter(t => !!t);
    this.commit.emit(cleaned);
  }

  clickedCancel() {
    this.cancel.emit();
  }
}
