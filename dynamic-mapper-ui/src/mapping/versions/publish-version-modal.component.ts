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
import { CoreModule, InputGroupEditableComponent, ModalLabels } from '@c8y/ngx-components';
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
  imports: [CoreModule, FormsModule, InputGroupEditableComponent],
  template: `
    <c8y-modal
      [labels]="labels"
      [disabled]="!isValid()"
      (onClose)="confirm()"
      (onDismiss)="cancel()"
      [headerClasses]="'modal-header dialog-header'"
    >
      <ng-container c8y-modal-title>
        <span [c8yIcon]="'upload'"></span>
      </ng-container>
      <div class="p-24 modal-inner-scroll animated fadeIn">
        <p class="text-center text-medium text-16 sticky-top bg-white">{{ 'Publish draft' | translate }}</p>
        <div class="p-24">
          <p class="text-muted">
            {{ 'Mapping' | translate }}: <strong>{{ mappingName }}</strong><br />
            {{ 'Current active version' | translate }}: <strong>{{ currentVersion ?? '—' }}</strong>
          </p>

          <c8y-form-group>
            <label>{{ 'New version' | translate }} <span class="text-danger">*</span></label>
            <div class="d-flex gap-8 m-b-8">
              <button type="button" class="btn btn-default btn-sm" (click)="pick(suggestions.patch)">
                {{ 'Patch' | translate }} &nbsp;<code>{{ suggestions.patch }}</code>
              </button>
              <button type="button" class="btn btn-default btn-sm" (click)="pick(suggestions.minor)">
                {{ 'Minor' | translate }} &nbsp;<code>{{ suggestions.minor }}</code>
              </button>
              <button type="button" class="btn btn-default btn-sm" (click)="pick(suggestions.major)">
                {{ 'Major' | translate }} &nbsp;<code>{{ suggestions.major }}</code>
              </button>
            </div>
            <input
              class="form-control"
              type="text"
              [(ngModel)]="version"
              [placeholder]="'e.g. 1.2.3' | translate"
              [class.has-error]="version && !isValid()"
            />
            @if (version && !isValid()) {
              <span class="help-block text-danger">{{ 'Must be MAJOR.MINOR.PATCH (e.g. 1.2.3)' | translate }}</span>
            }
          </c8y-form-group>

          <c8y-form-group>
            <label class="c8y-switch">
              <input type="checkbox" [ngModel]="activate" (ngModelChange)="setActivate($event)" data-cy="dm-publish-activate" />
              <span class="m-r-8"></span>
              <span>{{ activateLabel | translate }}</span>
            </label>
            <span class="help-block text-muted">{{ activateHint | translate: { version: currentVersion } }}</span>
          </c8y-form-group>

          <c8y-form-group>
            <label>{{ 'Change note' | translate }} <span class="text-muted">({{ 'optional' | translate }})</span></label>
            <c8y-input-group-editable
              [multiline]="true"
              [noSaveButton]="true"
              maxHeight="160px"
              formGroupClass="m-b-0"
              ariaLabel="Change note"
              placeholder="Describe what changed in this version"
              [(ngModel)]="note"
            ></c8y-input-group-editable>
          </c8y-form-group>
        </div>
      </div>
    </c8y-modal>
  `
})export class PublishVersionModalComponent implements OnInit {
  @Input() mappingName = '';
  @Input() currentVersion: string | null = null;
  @Input() suggestions: { patch: string; minor: string; major: string } = { patch: '1.0.0', minor: '1.0.0', major: '1.0.0' };

  /** Whether the mapping is currently active: decides the wording of the activation option. */
  @Input() mappingActive = false;
  /** Preset for the activation option. Save & Activate passes true; it is always the user's call. */
  @Input() activate = true;

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
