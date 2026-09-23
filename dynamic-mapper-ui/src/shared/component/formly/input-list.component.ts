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
  @Input()
  set data(list: Record<string, string> | Array<{ key: string; value: string | undefined }> | null | undefined) {
    const incoming = InputListComponent.toEntries(list);

    // The parent (InputListFormlyComponent) binds [data]="getCurrentData()" — a method call, so
    // Angular re-invokes this setter on every change-detection cycle, not just when the form
    // control's value genuinely changes. Clicking "+" (add()) appends a blank {key:'',value:''}
    // row locally and emits it upward; the formly wrapper filters blank-key rows out before
    // writing back to the form control (a header needs a key to mean anything), so the
    // persisted value is unchanged — but the very next CD cycle used to still land here and
    // rebuild dataInternal from that unchanged persisted value, discarding the just-added blank
    // row before the user could type a key into it. From the outside this looked like "+" simply
    // did nothing. Skip the rebuild whenever the incoming data's non-blank-key entries already
    // match what dataInternal currently holds — a genuine external change (initial load, the
    // drawer being reused for a different connector, an explicit form reset) still always has a
    // different meaningful entry set and is picked up as before.
    if (
      this.dataInternal.length > 0 &&
      InputListComponent.sameEntries(
        incoming.filter((e) => e.key?.trim()),
        this.dataInternal.filter((e) => e.key?.trim())
      )
    ) {
      return;
    }

    this.dataInternal = incoming.length > 0 ? incoming : [{ key: '', value: '' }];
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
    // Emit all data for UI updates
    this.dataChange.emit([...this.dataInternal]);
  }

}