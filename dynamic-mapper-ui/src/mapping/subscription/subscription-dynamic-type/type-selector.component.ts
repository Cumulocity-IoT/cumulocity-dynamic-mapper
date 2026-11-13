/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
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
  ChangeDetectionStrategy,
  Component,
  effect,
  EventEmitter,
  input,
  model,
  signal,
  Output,
  untracked,
  ViewEncapsulation
} from '@angular/core';
import { CoreModule } from '@c8y/ngx-components';

@Component({
  selector: 'd11r-type-selector',
  host: { class: 'flex-grow d-col fit-h' },
  templateUrl: 'type-selector.component.html',
  styleUrls: ['../../../shared/mapping/mapping.style.css'],
  encapsulation: ViewEncapsulation.None,
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports:[CoreModule]
})
export class TypeSelectorComponent {
  readonly typeList = input<string[]>([]);

  /** Embedded in a page instead of a drawer: no header or buttons; every edit is emitted as `typesChange`. */
  readonly inline = input(false);

  /** Inline only: offer, per new type, to subscribe its already existing devices too (a second column). */
  readonly showResync = input(false);
  /** The types that are subscribed already; a type not in this list is new and can be resynced. */
  readonly knownTypes = input<string[]>([]);
  /** Two-way: ticking a type's resync box replaces it and announces `resyncTypesChange`. */
  readonly resyncTypes = model<string[]>([]);

  @Output() cancel = new EventEmitter<void>();
  @Output() commit = new EventEmitter<string[]>();
  @Output() typesChange = new EventEmitter<string[]>();

  typeListInternal: string[] = [];
  private typeListInitialized = false;

  constructor() {
    effect(() => {
      const list = this.typeList();
      untracked(() => {
        // Inline, the component owns the rows after the first value: the host echoes back only the
        // non-blank types, which would otherwise wipe a row the user has just added and not yet typed.
        if (this.inline() && this.typeListInitialized) return;
        this.typeListInitialized = true;
        this.typeListInternal = [...list];
      });
    });
  }

  trackByFn(index: number, _item: unknown) {
    return index;
  }

  /** Text for the aria-live region; screen readers announce each add and remove. */
  readonly liveMessage = signal('');

  private announce(message: string) {
    this.liveMessage.set(message);
    setTimeout(() => this.liveMessage.set(''), 1000);
  }

  add() {
    this.typeListInternal.push("");
    this.announce('Item added');
    this.emitInline();
  }

  remove(index: number) {
    this.typeListInternal = this.typeListInternal.filter((_, i) => i !== index);
    this.announce('Item removed');
    this.emitInline();
  }

  isNewType(type: string | undefined): boolean {
    const t = type?.trim();
    return !!t && !this.knownTypes().includes(t);
  }

  /** A non-blank type the server has already. A blank row is neither known nor new: its checkbox is shown disabled. */
  isKnownType(type: string | undefined): boolean {
    const t = type?.trim();
    return !!t && this.knownTypes().includes(t);
  }

  isResync(type: string | undefined): boolean {
    return this.resyncTypes().includes(type?.trim() ?? '');
  }

  setResync(type: string | undefined, checked: boolean) {
    const t = type?.trim();
    if (!t) return;
    const others = this.resyncTypes().filter(x => x !== t);
    this.resyncTypes.set(checked ? [...others, t] : others);
  }

  /** Inline mode only: blank rows stay on screen while being typed but are never emitted. */
  emitInline() {
    if (!this.inline()) return;
    this.typesChange.emit(this.typeListInternal.map(t => t?.trim()).filter(t => !!t));
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
