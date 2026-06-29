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
import { Component, Input, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { CoreModule } from '@c8y/ngx-components';
import { Subject } from 'rxjs';

const SEMVER_RE = /^\d+\.\d+\.\d+$/;

/**
 * Modal for choosing a semver label when publishing a mapping draft.
 *
 * Shows the current active version, three bump-buttons (patch / minor / major)
 * that pre-fill the editable version field, and a note textarea. Resolves with
 * `{ version, note }` on confirm or `null` on cancel.
 */
@Component({
  selector: 'd11r-publish-version-modal',
  standalone: true,
  imports: [CoreModule, FormsModule],
  template: `
    <div class="modal-header">
      <h4 class="modal-title">Publish draft</h4>
    </div>
    <div class="modal-body">
      <p class="text-muted" style="margin-bottom:12px">
        Mapping: <strong>{{ mappingName }}</strong><br>
        Current active version: <strong>{{ currentVersion ?? '—' }}</strong>
      </p>

      <div class="form-group">
        <label>New version <span class="text-danger">*</span></label>
        <div class="d-flex" style="gap:8px; margin-bottom:8px">
          <button type="button" class="btn btn-default btn-sm" (click)="pick(suggestions.patch)">
            Patch &nbsp;<code>{{ suggestions.patch }}</code>
          </button>
          <button type="button" class="btn btn-default btn-sm" (click)="pick(suggestions.minor)">
            Minor &nbsp;<code>{{ suggestions.minor }}</code>
          </button>
          <button type="button" class="btn btn-default btn-sm" (click)="pick(suggestions.major)">
            Major &nbsp;<code>{{ suggestions.major }}</code>
          </button>
        </div>
        <input
          class="form-control"
          type="text"
          [(ngModel)]="version"
          placeholder="e.g. 1.2.3"
          [class.has-error]="version && !isValid()"
        />
        @if (version && !isValid()) {
          <span class="help-block text-danger">Must be MAJOR.MINOR.PATCH (e.g. 1.2.3)</span>
        }
      </div>

      <div class="form-group">
        <label>Change note <span class="text-muted">(optional)</span></label>
        <textarea class="form-control" rows="3" [(ngModel)]="note" placeholder="Describe what changed in this version"></textarea>
      </div>
    </div>
    <div class="modal-footer">
      <button type="button" class="btn btn-default" (click)="cancel()">Cancel</button>
      <button type="button" class="btn btn-primary" [disabled]="!isValid()" (click)="confirm()">
        Publish
      </button>
    </div>
  `
})
export class PublishVersionModalComponent implements OnInit {
  @Input() mappingName = '';
  @Input() currentVersion: string | null = null;
  @Input() suggestions: { patch: string; minor: string; major: string } = { patch: '1.0.0', minor: '1.0.0', major: '1.0.0' };

  version = '';
  note = '';

  /** Emits the chosen version+note on confirm, or null on cancel. */
  closeSubject = new Subject<{ version: string; note: string } | null>();

  ngOnInit(): void {
    this.version = this.suggestions.patch;
  }

  pick(v: string): void {
    this.version = v;
  }

  isValid(): boolean {
    return SEMVER_RE.test(this.version.trim());
  }

  confirm(): void {
    if (!this.isValid()) return;
    this.closeSubject.next({ version: this.version.trim(), note: this.note.trim() });
    this.closeSubject.complete();
  }

  cancel(): void {
    this.closeSubject.next(null);
    this.closeSubject.complete();
  }
}
