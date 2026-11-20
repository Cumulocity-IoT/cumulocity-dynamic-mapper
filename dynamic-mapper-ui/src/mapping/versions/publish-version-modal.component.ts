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
import { Component, Input, OnInit, ChangeDetectionStrategy } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { CoreModule, ModalLabels } from '@c8y/ngx-components';
import { Subject } from 'rxjs';

const SEMVER_RE = /^\d+\.\d+\.\d+$/;

/**
 * Modal for choosing a semver label when publishing a mapping draft.
 *
 * Shows the current active version, three bump-buttons (patch / minor / major)
 * that pre-fill the editable version field, and a note textarea. Resolves with
 * `{ version, note, activate }` on confirm or `null` on cancel.
 */
@Component({
  selector: 'd11r-publish-version-modal',
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [CoreModule, FormsModule],
  template: `
    <c8y-modal
      [title]="'Publish draft' | translate"
      [labels]="labels"
      [disabled]="!isValid()"
      (onClose)="confirm()"
      (onDismiss)="cancel()"
      [headerClasses]="'modal-header dialog-header'"
    >
      <ng-container c8y-modal-title>
        <span [c8yIcon]="'upload'"></span>
      </ng-container>
      <div class="animated fadeIn">
        <div class="dm-publish-grid" style="display: grid; grid-template-columns: 11rem 1fr; gap: 12px 16px; align-items: baseline">
          <span class="text-muted">{{ 'Mapping' | translate }}</span>
          <span class="text-medium">{{ mappingName }}</span>

          <span class="text-muted">{{ 'Current active version' | translate }}</span>
          <span class="text-medium">{{ currentVersion ?? '—' }}</span>

          <label class="text-muted m-b-0" for="dm-publish-version">{{ 'New version' | translate }}</label>
          <c8y-form-group class="m-b-0" [hasError]="!!version && !isValid()">
            <input
              id="dm-publish-version"
              class="form-control"
              type="text"
              [(ngModel)]="version"
              [placeholder]="'e.g. 1.2.3' | translate"
              required
              data-cy="dm-publish-version"
            />
            <div class="d-flex flex-wrap a-i-center gap-8 m-t-8">
              @for (s of bumps; track s.key) {
                <button
                  type="button"
                  class="btn btn-xs"
                  [ngClass]="version === suggestions[s.key] ? 'btn-primary' : 'btn-default'"
                  (click)="pick(suggestions[s.key])"
                >
                  {{ s.label | translate }}
                  <span class="m-l-4" style="font-family: monospace">{{ suggestions[s.key] }}</span>
                </button>
              }
            </div>
            @if (version && !isValid()) {
              <span class="help-block">{{ 'Must be MAJOR.MINOR.PATCH (e.g. 1.2.3)' | translate }}</span>
            }
          </c8y-form-group>

          <label class="text-muted m-b-0" for="dm-publish-note">{{ 'Change note' | translate }}</label>
          <textarea
            id="dm-publish-note"
            class="form-control"
            rows="3"
            [(ngModel)]="note"
            [placeholder]="'Describe what changed in this version' | translate"
            data-cy="dm-publish-note"
          ></textarea>
        </div>

        <hr class="m-t-24 m-b-16" />

        <c8y-form-group class="m-b-0">
          <label class="c8y-switch">
            <input type="checkbox" [ngModel]="activate" (ngModelChange)="setActivate($event)" data-cy="dm-publish-activate" />
            <span></span>
            <span>{{ activateLabel | translate }}</span>
          </label>
          @if (activateHint) {
            <span class="help-block text-muted">{{ activateHint | translate: { version: currentVersion } }}</span>
          }
        </c8y-form-group>
      </div>
    </c8y-modal>
  `
})
export class PublishVersionModalComponent implements OnInit {
  @Input() mappingName = '';
  @Input() currentVersion: string | null = null;
  @Input() suggestions: { patch: string; minor: string; major: string } = { patch: '1.0.0', minor: '1.0.0', major: '1.0.0' };

  /** Whether the mapping is currently active: decides the wording of the activation option. */
  @Input() mappingActive = false;
  /** Preset for the activation option. Save & Activate passes true; it is always the user's call. */
  @Input() activate = true;

  readonly bumps: { key: 'patch' | 'minor' | 'major'; label: string }[] = [
    { key: 'patch', label: 'Patch' },
    { key: 'minor', label: 'Minor' },
    { key: 'major', label: 'Major' }
  ];

  version = '';
  note = '';

  labels: ModalLabels = { ok: 'Publish & activate', cancel: 'Cancel' };

  setActivate(value: boolean): void {
    this.activate = value;
    this.labels = { ok: value ? 'Publish & activate' : 'Publish', cancel: 'Cancel' };
  }

  /** Explains what the chosen activation option does to the running mapping. */
  get activateHint(): string {
    if (this.activate) {
      return this.mappingActive ? 'The mapping keeps running {{version}} until you confirm.' : '';
    }
    return this.mappingActive
      ? 'The mapping keeps running {{version}} until you activate the new version.'
      : 'The mapping stays inactive and processes no messages until you activate it.';
  }

  get activateLabel(): string {
    return this.mappingActive ? 'Switch the running mapping to this version' : 'Activate the mapping with this version';
  }

  /** Emits the chosen version, note and activation choice on confirm, or null on cancel. */
  closeSubject = new Subject<{ version: string; note: string; activate: boolean } | null>();

  ngOnInit(): void {
    this.version = this.suggestions.patch;
    this.setActivate(this.activate);
  }

  pick(v: string): void {
    this.version = v;
  }

  isValid(): boolean {
    return SEMVER_RE.test(this.version.trim());
  }

  confirm(): void {
    if (!this.isValid()) return;
    this.closeSubject.next({ version: this.version.trim(), note: this.note.trim(), activate: this.activate });
    this.closeSubject.complete();
  }

  cancel(): void {
    this.closeSubject.next(null);
    this.closeSubject.complete();
  }
}
