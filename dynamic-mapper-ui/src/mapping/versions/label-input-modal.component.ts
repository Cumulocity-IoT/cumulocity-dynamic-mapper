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
import { Component, Input } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { CoreModule, ModalLabels } from '@c8y/ngx-components';
import { Subject } from 'rxjs';

/**
 * Small modal that prompts for a single free-text label. Emits the entered value
 * on save (may be empty string) or {@code undefined} on cancel via closeSubject.
 */
@Component({
  selector: 'd11r-label-input-modal',
  template: `
    <c8y-modal [title]="title" (onClose)="onSave()" (onDismiss)="onDismiss()" [labels]="labels"
      [headerClasses]="'modal-header dialog-header'">
      <ng-container c8y-modal-title>
        <span [c8yIcon]="'pencil'"></span>
      </ng-container>
      <div class="p-24">
        <c8y-form-group>
          <label>{{ 'Label' | translate }}</label>
          <input type="text" class="form-control" [(ngModel)]="value" data-cy="dm-version-label-input"
            placeholder="{{ 'Optional change note (e.g. what changed and why)' | translate }}" />
        </c8y-form-group>
      </div>
    </c8y-modal>
  `,
  standalone: true,
  imports: [CoreModule, FormsModule]
})
export class LabelInputModalComponent {
  @Input() title = 'Label';
  @Input() value = '';

  labels: ModalLabels = { ok: 'Save', cancel: 'Cancel' };
  closeSubject = new Subject<string | undefined>();

  onSave(): void {
    this.closeSubject.next(this.value ?? '');
    this.closeSubject.complete();
  }

  onDismiss(): void {
    this.closeSubject.next(undefined);
    this.closeSubject.complete();
  }
}
