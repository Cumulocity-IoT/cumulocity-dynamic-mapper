// input-list.component.ts

import { Component, EventEmitter, Input, OnDestroy, OnInit, Output } from '@angular/core';
import { CoreModule } from '@c8y/ngx-components';
import { debounceTime, Subject } from 'rxjs';

@Component({
  selector: 'd11r-input-list',
  templateUrl: './input-list.component.html',
  standalone: true,
  imports: [CoreModule]
})
export class InputListComponent implements OnInit, OnDestroy {
  // The meaningful (non-blank-key) entries as of our own last emitChange() call — i.e. what we
  // most recently told the parent our state was. `undefined` until the first local edit.
  //
  // The parent (InputListFormlyComponent) binds [data]="getCurrentData()" — a method call, so
  // Angular re-invokes the setter below on every change-detection cycle, not just when the form
  // control's value genuinely changes from an outside cause. A naive fix that compares the
  // incoming value against the *current* dataInternal (tried first, reverted) has a race: typing
  // is debounced 150ms before it's emitted upward, so by the time a given emission's round trip
  // back through the form control reaches this setter, the user may already have typed further —
  // dataInternal has moved on, the comparison no longer matches, and the incoming (now-stale)
  // snapshot overwrites dataInternal, silently dropping the newest keystrokes (looked like a
  // just-added second row "vanishing" while typing into it).
  //
  // Comparing against lastEmittedMeaningful instead fixes this: incoming data that matches what
  // *we* last sent is always safe to ignore, no matter how stale it is relative to dataInternal's
  // current state — it can only be an echo of our own past emission, never new information, so
  // there's nothing in it dataInternal doesn't already (more currently) reflect.
  private lastEmittedMeaningful?: Array<{ key: string; value: string | undefined }>;

  @Input()
  set data(list: Record<string, string> | Array<{ key: string; value: string | undefined }> | null | undefined) {
    const incoming = InputListComponent.toEntries(list);
    const incomingMeaningful = incoming.filter((e) => e.key?.trim());

    if (this.lastEmittedMeaningful && InputListComponent.sameEntries(incomingMeaningful, this.lastEmittedMeaningful)) {
      return;
    }

    this.dataInternal = incoming.length > 0 ? incoming : [{ key: '', value: '' }];
    this.lastEmittedMeaningful = incomingMeaningful;
  }

  private static toEntries(
    list: Record<string, string> | Array<{ key: string; value: string | undefined }> | null | undefined
  ): Array<{ key: string; value: string | undefined }> {
    if (!list) {
      return [];
    }
    if (Array.isArray(list)) {
      return [...list];
    }
    if (typeof list === 'object') {
      return Object.entries(list).map(([key, value]) => ({ key, value: value as string | undefined }));
    }
    return [];
  }

  private static sameEntries(
    a: Array<{ key: string; value: string | undefined }>,
    b: Array<{ key: string; value: string | undefined }>
  ): boolean {
    if (a.length !== b.length) {
      return false;
    }
    return a.every((entry, i) => entry.key === b[i].key && entry.value === b[i].value);
  }

  private changeSubject = new Subject<void>();

  ngOnInit() {
    // Debounce the changes to prevent too frequent updates
    this.changeSubject.pipe(
      debounceTime(150) // Wait 150ms after the last change
    ).subscribe(() => {
      this.emitChange();
    });
  }

  ngOnDestroy() {
    this.changeSubject.complete();
  }

  get data(): Array<{ key: string; value: string | undefined }> {
    return this.dataInternal;
  }

  trackByIndex(index: number, item: any): number {
    return index;
  }

  @Input() disabled = false;
  @Output() dataChange = new EventEmitter<Array<{ key: string; value: string | undefined }>>();

  dataInternal: Array<{ key: string; value: string | undefined }> = [];

  add() {
    this.dataInternal.push({ key: '', value: '' });
    this.emitChange();
  }

  remove(index: number) {
    this.dataInternal.splice(index, 1);
    // If all items are removed, add an empty one but don't emit yet
    if (this.dataInternal.length === 0) {
      this.dataInternal.push({ key: '', value: '' });
    }
    this.emitChange();
  }

  onInputChange() {
    // Instead of calling emitChange directly, trigger the debounced subject
    this.changeSubject.next();
  }

  private emitChange() {
    // Record what we're telling the parent, so a later echo of exactly this (however stale by
    // the time it round-trips back through the `data` setter above) is recognized as our own and
    // ignored rather than clobbering whatever the user has typed since.
    this.lastEmittedMeaningful = this.dataInternal.filter((e) => e.key?.trim());
    // Emit all data for UI updates
    this.dataChange.emit([...this.dataInternal]);
  }

}