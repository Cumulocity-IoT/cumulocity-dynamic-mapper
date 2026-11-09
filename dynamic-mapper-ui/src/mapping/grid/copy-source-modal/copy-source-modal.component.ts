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

import { Component, Input, OnDestroy, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { CoreModule } from '@c8y/ngx-components';
import { BsModalRef } from 'ngx-bootstrap/modal';
import { Subject } from 'rxjs';

/** Which version of a mapping line a duplicate is created from. */
export type CopySource = 'active' | 'draft';

/**
 * Modal asking which version of a mapping with unpublished draft changes to duplicate.
 * Same structure as the publish-draft modal. Resolves with the chosen source on confirm,
 * or `null` on cancel.
 */
@Component({
  selector: 'd11r-copy-source-modal',
  standalone: true,
  imports: [CoreModule, FormsModule],
  template: `
    <div class="modal-header">
      <h4 class="modal-title">Duplicate mapping</h4>
    </div>
    <div class="modal-body">
      <p class="text-muted" style="margin-bottom:12px">
        Mapping: <strong>{{ mappingName }}</strong><br>
        This mapping has unpublished draft changes. Which version do you want to copy?
      </p>
      <div class="form-group">
        <label class="c8y-radio">
          <input type="radio" name="copySource" value="draft" [(ngModel)]="source" data-cy="dm-copy-source-draft" />
          <span></span>
          <span>Draft version <span class="text-muted">(your latest unpublished changes)</span></span>
        </label>
        <label class="c8y-radio">
          <input type="radio" name="copySource" value="active" [(ngModel)]="source" data-cy="dm-copy-source-active" />
          <span></span>
          <span>
            Active version @if (activeVersion) { <strong>{{ activeVersion }}</strong> }
            <span class="text-muted">(the configuration that is currently running)</span>
          </span>
        </label>
      </div>
    </div>
    <div class="modal-footer">
      <button type="button" class="btn btn-default" (click)="cancel()" data-cy="dm-copy-source-cancel">Cancel</button>
      <button type="button" class="btn btn-primary" (click)="confirm()" data-cy="dm-copy-source-ok">Duplicate</button>
    </div>
  `
})
export class CopySourceModalComponent implements OnDestroy {
  @Input() mappingName = '';
  /** Version of the active configuration, if one was published. */
  @Input() activeVersion?: string;

  source: CopySource = 'draft';

  /** Emits the chosen source on confirm, or null when cancelled or dismissed. */
  readonly closeSubject = new Subject<CopySource | null>();

  private readonly modalRef = inject(BsModalRef);
  private closed = false;

  confirm(): void {
    this.close(this.source);
  }

  cancel(): void {
    this.close(null);
  }

  /** Also runs when the modal is dismissed with Escape or a backdrop click. */
  ngOnDestroy(): void {
    this.emit(null);
  }

  private close(result: CopySource | null): void {
    this.emit(result);
    this.modalRef.hide();
  }

  private emit(result: CopySource | null): void {
    if (this.closed) return;
    this.closed = true;
    this.closeSubject.next(result);
    this.closeSubject.complete();
  }
}
