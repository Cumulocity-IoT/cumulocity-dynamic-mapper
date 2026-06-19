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
import { FormsModule } from '@angular/forms';
import { CellRendererContext, CoreModule } from '@c8y/ngx-components';

/**
 * Inline-editable note cell using the Cumulocity "edit on focus" pattern
 * (.input-group-editable). Shows the note as plain text at rest; reveals a
 * bordered input on hover/focus with a pencil affordance.
 *
 * Communication back to the parent is done via {@code context.item.onNoteChange},
 * a callback the parent injects per-row. When the callback is absent the input
 * is read-only (canManage=false).
 */
@Component({
  selector: 'd11r-note-edit-cell',
  template: `
    <div class="input-group input-group-editable" style="width: 100%">
      <input
        type="text"
        class="form-control"
        [(ngModel)]="editValue"
        [placeholder]="'Add a note…' | translate"
        [disabled]="!canEdit"
        (blur)="onBlur()"
        (keydown.enter)="onEnter($event)"
        data-cy="dm-version-note-cell"
      />
      <span></span>
    </div>
  `,
  standalone: true,
  imports: [CoreModule, FormsModule]
})
export class NoteEditCellRendererComponent implements OnInit {
  editValue = '';
  private originalValue = '';

  constructor(public readonly context: CellRendererContext) {}

  ngOnInit(): void {
    const v = this.context.value as string;
    this.editValue = v === '—' ? '' : (v ?? '');
    this.originalValue = this.editValue;
  }

  get canEdit(): boolean {
    return typeof (this.context.item as any).onNoteChange === 'function';
  }

  onBlur(): void {
    if (this.editValue === this.originalValue) {
      return;
    }
    const row = this.context.item as any;
    if (typeof row.onNoteChange === 'function') {
      row.onNoteChange(this.editValue);
      this.originalValue = this.editValue;
    }
  }

  onEnter(event: KeyboardEvent): void {
    (event.target as HTMLInputElement).blur();
  }
}
