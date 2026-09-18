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
 * Version/Status cell of the mapping grid.
 *
 * The cell always renders in the same order, so the column reads as stable slots down the grid:
 * the **version**, then **draft**, then **debug**. Version and draft both describe the version
 * (which one is active, and whether unpublished changes exist), so they sit together; debug is
 * operational status and comes last.
 *
 * Only the version is interactive. When the column supplies a `callback` (see
 * MappingComponent.getColumnsMappings) it becomes a link that opens the version drawer for that
 * row; the draft and debug labels stay plain text, since they open nothing.
 *
 * The affordance has to be visible at rest: c8y's `.interact` class only sets `cursor: pointer`,
 * and an <a> without an href gets no default link styling, so the version would otherwise look
 * like plain text until hovered. Hence the always-visible history icon plus the underlined,
 * brand-coloured version.
 *
 * Without a callback the version renders as a plain badge, so the renderer stays usable in any
 * grid that has no drawer to open. Mirrors ConnectorDetailCellRendererComponent.
 */
@Component({
  selector: 'd11r-mapping-renderer-status',
  template: `
      <div class="d-flex a-i-center dm-status-cell">

        <!-- Slot 1 — version (the only interactive part of the cell) -->
        @if (context?.property['callback']) {
          <a class="interact d-flex a-i-center dm-version-link"
            [attr.data-cy]="'dm-mapping-status-link-' + context.item.id"
            title="Show version history of this mapping"
            (click)="context.property['callback'](context.item)">
            <i c8yIcon="history" class="dm-version-icon"></i>
            @if (context.value.version) {
              <span class="text-12 dm-version-text" [attr.data-cy]="'dm-mapping-status-version-' + context.item.id"
                >v{{ context.value.version }}</span>
            } @else {
              <!-- No version yet: still render link text so the cell keeps a visible click target. -->
              <span class="text-12 dm-version-text">&mdash;</span>
            }
          </a>
        } @else if (context.value.version) {
          <span class="text-12 label label-default" [attr.data-cy]="'dm-mapping-status-version-' + context.item.id"
            title="Active version">v{{ context.value.version }}</span>
        }

        <!-- Slot 2 — 'draft' belongs with the version: it describes the version state
             (unpublished changes), not how the mapping is running. -->
        @if (context.value.draftDirty) {
          <span class="text-12 label label-info" [attr.data-cy]="'dm-mapping-status-draft-' + context.item.id"
            title="This mapping has unpublished draft changes">draft</span>
        }

        <!-- Slot 3 — operational status, never a link -->
        @if (context.value.debug) {
          <span class="text-12 label label-success" [attr.data-cy]="'dm-mapping-status-debug-' + context.item.id">debug</span>
        }
      </div>
    `,
  // Default (emulated) encapsulation: these rules apply only to this renderer's own template.
  styles: [`
    .dm-status-cell,
    .dm-version-link {
      gap: 4px;
      white-space: nowrap;
    }

    .dm-version-icon,
    .dm-version-text {
      color: var(--c8y-brand-primary, #1776bf);
    }

    /* Underline at rest, not only on hover — the version must read as actionable without the
       pointer being over it. */
    .dm-version-text {
      text-decoration: underline;
      text-decoration-color: rgba(23, 118, 191, 0.4);
      text-underline-offset: 2px;
      font-weight: 600;
    }

    .dm-version-link:hover .dm-version-text {
      text-decoration-color: currentColor;
    }
  `],
  standalone: true,
  imports: [CoreModule]
})
export class StatusRendererComponent {
  constructor(public readonly context: CellRendererContext) { }
}
