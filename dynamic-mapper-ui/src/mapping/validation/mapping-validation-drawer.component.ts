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
import { Component, inject, Input, OnInit, ViewEncapsulation } from '@angular/core';
import { BottomDrawerRef, CoreModule } from '@c8y/ngx-components';
import { ValidationIssue } from '../../shared/mapping/mapping-validation-error';
import { translateValidationErrorCode } from '../../shared/mapping/util';

/** What the drawer resolves with: either take the user to a problem, or just close. */
export type ValidationDrawerResult =
  | { action: 'goto'; index: number }
  | { action: 'close' };

interface DisplayedIssue {
  /** Human-readable rule text, from the shared ValidationFormlyError catalogue. */
  message: string;
  field?: string;
  value?: string;
  reason?: string;
  /** Present only when the backend could identify a specific substitution. */
  index?: number;
  heading?: string;
}

/**
 * Shown when saving a mapping is rejected by backend validation (HTTP 422).
 *
 * Replaces a single joined toast that gave the user no way to act: each failure is listed with
 * whatever detail the rule supplied, and failures tied to a specific substitution offer a jump
 * straight to it. The editor stays open behind the drawer, so the correction can be made and the
 * save retried without losing any work.
 */
@Component({
  selector: 'd11r-mapping-validation-drawer',
  templateUrl: 'mapping-validation-drawer.component.html',
  encapsulation: ViewEncapsulation.None,
  standalone: true,
  imports: [CoreModule],
  host: { class: 'd-contents' }
})
export class MappingValidationDrawerComponent implements OnInit {
  private readonly bottomDrawerRef = inject(BottomDrawerRef);

  /** Detailed failures from the 422 body's `details[]`. */
  @Input() issues: ValidationIssue[] = [];
  /** Bare codes from `errors[]`, used when the backend sent no details at all. */
  @Input() errors: string[] = [];
  @Input() mappingName?: string;

  displayedIssues: DisplayedIssue[] = [];

  private resolve!: (value: ValidationDrawerResult) => void;

  result: Promise<ValidationDrawerResult> = new Promise(resolve => {
    this.resolve = resolve;
  });

  ngOnInit(): void {
    this.displayedIssues = this.issues.length
      ? this.issues.map(issue => this.toDisplayed(issue))
      // An older backend, or a failure raised before the detailed form existed, sends codes only.
      : this.errors.map(code => ({ message: translateValidationErrorCode(code) }));
  }

  private toDisplayed(issue: ValidationIssue): DisplayedIssue {
    const hasIndex = issue.index !== undefined && issue.index !== null;
    return {
      message: translateValidationErrorCode(issue.code),
      field: issue.field,
      value: issue.value,
      reason: issue.reason,
      index: hasIndex ? issue.index : undefined,
      // Substitutions are numbered from 1 in the editor's grid, but indexed from 0 on the wire.
      heading: hasIndex ? `Substitution ${issue.index + 1}` : undefined
    };
  }

  goTo(index: number): void {
    this.resolve({ action: 'goto', index });
    this.bottomDrawerRef.close();
  }

  close(): void {
    this.resolve({ action: 'close' });
    this.bottomDrawerRef.close();
  }
}
