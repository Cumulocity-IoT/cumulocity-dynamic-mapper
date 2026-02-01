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
    // Handle both object and array input
    if (!list) {
      this.dataInternal = [];
    } else if (Array.isArray(list)) {
      // If it's already an array, use it
      this.dataInternal = [...list];
    } else if (typeof list === 'object') {
      // If it's an object, convert to array
      this.dataInternal = Object.entries(list).map(([key, value]) => ({
        key,
        value: value as string | undefined
      }));
    } else {
      this.dataInternal = [];
    }

    // Add an empty entry if the array is empty - BUT DON'T EMIT CHANGES
    if (this.dataInternal.length === 0) {
      this.dataInternal.push({ key: '', value: '' });
      // Don't call emitChange() here - this would cause the loop!
    }
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