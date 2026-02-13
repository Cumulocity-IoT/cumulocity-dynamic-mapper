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

import { Component, inject, Input, OnDestroy, OnInit, ViewEncapsulation } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { BottomDrawerRef, CoreModule } from '@c8y/ngx-components';
import { Subject } from 'rxjs';
import { EditorComponent } from '@c8y/ngx-components/editor';
import { Mapping } from '../../../shared';
import { base64ToString, stringToBase64 } from '../../../mapping/shared/util';

@Component({
  selector: 'd11r-code-editor-drawer',
  templateUrl: './code-editor-drawer.component.html',
  encapsulation: ViewEncapsulation.None,
  standalone: true,
  imports: [CoreModule, EditorComponent, FormsModule]
})
export class CodeEditorDrawerComponent implements OnInit, OnDestroy {
  @Input() mapping: Mapping;
  @Input() action: string = "update";
  @Input() sourceSystem: string;

  private readonly bottomDrawerRef = inject(BottomDrawerRef);
  private readonly destroy$ = new Subject<void>();

  editorOptions: EditorComponent['editorOptions'];
  closeSubject: Subject<string> = new Subject();
  code: string;
  valid: boolean = true;

  async ngOnInit(): Promise<void> {
    // Decode base64 encoded code for display/editing
    this.code = this.mapping?.code ? base64ToString(this.mapping.code) : '';
    this.editorOptions = {
      minimap: { enabled: true },
      language: 'javascript',
      renderWhitespace: 'none',
      tabSize: 4,
      readOnly: this.action === 'view'
    };
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    this.closeSubject.complete();
  }

  onSave(): void {
    if (!this.valid) return;
    // Encode code to base64 before returning
    const encodedCode = stringToBase64(this.code);
    this.closeSubject.next(encodedCode);
    this.bottomDrawerRef.close();
  }

  onCancel(): void {
    this.closeSubject.next(undefined);
    this.bottomDrawerRef.close();
  }
}
