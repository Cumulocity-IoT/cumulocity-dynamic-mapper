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

import { AfterViewInit, ChangeDetectionStrategy, Component, ElementRef, ViewChild } from '@angular/core';
import { FormsModule, ReactiveFormsModule } from '@angular/forms';
import { FieldType, FormlyModule } from '@ngx-formly/core';

@Component({
  selector: 'd11r-formly-field-textarea',
  template: `
    @if (props['sensitive']) {
      <div style="position: relative;">
        <textarea
          c8y-textarea-autoresize
          [class]="class"
          [readonly]="props.readonly"
          [required]="props.required"
          [formControl]="formControl"
          [cols]="props.cols"
          [rows]="props.rows"
          [formlyAttributes]="field"
          [placeholder]="props.placeholder"
          [style.-webkit-text-security]="(!isServerMasked && !showSensitive) ? 'disc' : 'none'"
          [style.color]="(!isServerMasked && !showSensitive) ? 'transparent' : null"
          [style.text-shadow]="(!isServerMasked && !showSensitive) ? '0 0 8px rgba(0,0,0,0.5)' : null"
          #textareaRef
        >
 {{ formControl.value }}
</textarea>
        @if (!isServerMasked) {
          <button type="button" class="btn btn-clean" style="position: absolute; right: 8px; top: 6px;"
            (click)="showSensitive = !showSensitive"
            [title]="showSensitive ? 'Hide' : 'Show'"
            [attr.aria-label]="showSensitive ? 'Hide sensitive value' : 'Show sensitive value'"
            [attr.aria-pressed]="showSensitive">
            <i [class]="'dlt-c8y-icon-' + (showSensitive ? 'eye-slash' : 'eye')"></i>
          </button>
        }
      </div>
    } @else {
      <textarea
        c8y-textarea-autoresize
        [class]="class"
        [readonly]="props.readonly"
        [required]="props.required"
        [formControl]="formControl"
        [cols]="props.cols"
        [rows]="props.rows"
        [formlyAttributes]="field"
        [placeholder]="props.placeholder"
        #textareaRef
      >
 {{ formControl.value }}
</textarea>
    }
  `,
  styleUrls: ['./textarea.type.component.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [FormsModule, ReactiveFormsModule, FormlyModule]
})
export class CustomFieldTextarea extends FieldType implements AfterViewInit {
  @ViewChild('textareaRef') textareaRef: ElementRef<HTMLTextAreaElement>;
  showSensitive = false;

  get isServerMasked(): boolean {
    return this.formControl.value === '****';
  }

  get class() {
    return `form-control ${this.props['class']}`;
  }
  get readonly() {
    return this.props.readonly ? this.props.readonly : true;
  }
  get cols() {
    return this.props.cols ? 80 : this.props.cols;
  }
  get rows() {
    return this.props['rows'] ? this.props['rows'] : 2;
  }

  ngAfterViewInit(): void {
    this.formControl.valueChanges.subscribe(() => {
      setTimeout(() => this.resize(), 0);
    });
  }

  private resize(): void {
    const el = this.textareaRef?.nativeElement;
    if (el) {
      el.style.height = 'auto';
      el.style.height = `${el.scrollHeight}px`;
    }
  }
}
