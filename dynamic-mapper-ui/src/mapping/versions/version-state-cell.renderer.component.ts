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
import { CellRendererContext, CoreModule } from '@c8y/ngx-components';

/**
 * Renders the state of a version row — 'active' | 'published' | 'draft' in
 * {@code context.value} — and, for active/published rows, doubles as the activation control:
 * a real toggle switch (same `c8y-switch` pattern as MappingStatusActivationRendererComponent
 * on the main mapping grid) rather than a separate row-action icon.
 *
 * Fixed 2026-09-23: previously the active row showed only a static "active" badge and the
 * published row(s) showed only a separate "Activate" row-action icon (`toggle-on`) that looked
 * like a live, already-on switch regardless of which row was actually active — two indicators
 * in different columns that could visually disagree. Now there is exactly one indicator per
 * row: on+disabled for the active version, off+clickable-to-activate for every published
 * version. Draft keeps its plain badge — a draft isn't directly activatable, it has to be
 * published first — so no toggle is offered for it.
 *
 * The click handler calls {@code context.item.onActivate}, a callback the drawer injects
 * per-row (same pattern as NoteEditCellRendererComponent's `onNoteChange`); its absence
 * disables the toggle (canManage=false, or this is already the active row).
 */
@Component({
  selector: 'd11r-version-state-cell',
  template: `
    @switch (context.value) {
      @case ('draft') {
        <!-- label-warning, matching the draft badge in the mapping grid (StatusRendererComponent):
             a draft means "unpublished, not in effect yet", so it reads as action-required in both
             places rather than as neutral information. -->
        <span class="label label-warning" [attr.data-cy]="'dm-version-state-draft'">{{ 'draft' | translate }}</span>
      }
      @default {
        <!-- No visible text label: the column is too narrow for "Active"/"Published" without
             truncating (found live — showed as clipped "Pu"/"Ac"), and the switch's on/off
             position plus color already carry the same information. Kept for screen readers
             (sr-only) and as a hover tooltip via [title], both already in place. -->
        <label
          class="c8y-switch"
          [title]="(context.value === 'active' ? 'This is the active version' : 'Activate this version') | translate"
        >
          <input
            type="checkbox"
            [attr.data-cy]="'dm-version-state-toggle-' + context.item.id"
            [checked]="context.value === 'active'"
            [disabled]="context.value === 'active' || !canActivate"
            (click)="onToggleClick($event)"
          />
          <span></span>
          <span class="sr-only">
            {{ (context.value === 'active' ? 'active' : 'published') | translate }}
          </span>
        </label>
      }
    }
  `,
  standalone: true,
  imports: [CoreModule]
})
export class VersionStateCellRendererComponent {
  constructor(public readonly context: CellRendererContext) { }

  get canActivate(): boolean {
    return typeof (this.context.item as any)?.onActivate === 'function';
  }

  onToggleClick(event: Event): void {
    event.preventDefault();
    if (this.context.value === 'active' || !this.canActivate) {
      return;
    }
    (this.context.item as any).onActivate();
  }
}
