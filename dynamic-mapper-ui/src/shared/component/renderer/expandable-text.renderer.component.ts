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

import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  OnDestroy,
  signal,
  viewChild
} from '@angular/core';
import { CellRendererContext } from '@c8y/ngx-components';
import { TranslatePipe } from '@ngx-translate/core';

/**
 * A cell that shows long text on one line and offers a "More" button to read the rest.
 *
 * Without it a long value, such as the last error of a mapping, stretches its grid row to many lines and
 * pushes the other rows out of view. The button only appears when the text does not fit; the full text is also
 * the tooltip while collapsed. The expanded state is kept per row and column, so a realtime refresh of the
 * grid does not collapse a row the user opened.
 */
@Component({
  selector: 'd11r-renderer-expandable-text',
  template: `
    <div class="d11r-expandable-text" [class.expanded]="expanded()">
      <span #body class="d11r-expandable-text__body" [attr.title]="expanded() ? null : text">{{ text }}</span>
      @if (expanded() || overflowing()) {
        <button type="button" class="btn-link d11r-expandable-text__toggle" (click)="toggle()">
          {{ (expanded() ? 'Less' : 'More') | translate }}
        </button>
      }
    </div>
  `,
  styles: [
    `
      .d11r-expandable-text {
        display: flex;
        align-items: baseline;
        gap: 8px;
        min-width: 0;
      }
      .d11r-expandable-text__body {
        flex: 0 1 auto;
        min-width: 0;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
      .d11r-expandable-text.expanded {
        flex-direction: column;
        align-items: flex-start;
        gap: 2px;
      }
      .d11r-expandable-text.expanded .d11r-expandable-text__body {
        white-space: normal;
        overflow-wrap: anywhere;
      }
      .d11r-expandable-text__toggle {
        flex: 0 0 auto;
        padding: 0;
        border: 0;
        background: none;
        white-space: nowrap;
      }
    `
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [TranslatePipe]
})
export class ExpandableTextRendererComponent implements AfterViewInit, OnDestroy {
  /** Rows the user opened, as "row id|column". Lives longer than a cell, which the grid re-creates on refresh. */
  private static readonly openedCells = new Set<string>();

  private readonly bodyRef = viewChild.required<ElementRef<HTMLElement>>('body');
  private resizeObserver?: ResizeObserver;

  readonly expanded = signal(false);
  readonly overflowing = signal(false);

  constructor(public readonly context: CellRendererContext) {
    this.expanded.set(ExpandableTextRendererComponent.openedCells.has(this.cellKey));
  }

  get text(): string {
    const value = this.context.value;
    return value === null || value === undefined ? '' : String(value);
  }

  private get cellKey(): string {
    const item = this.context.item as { identifier?: string; id?: string } | undefined;
    return `${item?.identifier ?? item?.id ?? ''}|${this.context.property?.name ?? ''}`;
  }

  ngAfterViewInit(): void {
    const body = this.bodyRef().nativeElement;
    this.measure(body);
    // The column can be resized, which changes whether the text fits.
    if (typeof ResizeObserver !== 'undefined') {
      this.resizeObserver = new ResizeObserver(() => this.measure(body));
      this.resizeObserver.observe(body);
    }
  }

  ngOnDestroy(): void {
    this.resizeObserver?.disconnect();
  }

  toggle(): void {
    const open = !this.expanded();
    this.expanded.set(open);
    if (open) {
      ExpandableTextRendererComponent.openedCells.add(this.cellKey);
    } else {
      ExpandableTextRendererComponent.openedCells.delete(this.cellKey);
    }
  }

  /** The text overflows when it is collapsed and wider than its box. Skipped while expanded: it always fits. */
  private measure(body: HTMLElement): void {
    if (this.expanded()) {
      return;
    }
    this.overflowing.set(body.scrollWidth > body.clientWidth);
  }
}
