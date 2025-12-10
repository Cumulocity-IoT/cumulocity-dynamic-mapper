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

import { Component, Input, OnDestroy, OnInit, ViewChild, ViewEncapsulation } from '@angular/core';
import { CoreModule, ModalLabels } from '@c8y/ngx-components';
import { Subject } from 'rxjs';
import { Substitution } from '../../shared';
import { EditorComponent } from '@c8y/ngx-components/editor';

@Component({
  selector: 'd11r-code-explorer-modal',
  templateUrl: './code-explorer-modal.component.html',
  styleUrls: ['../shared/mapping.style.css'],
  encapsulation: ViewEncapsulation.None,
  standalone: true,
  imports:[CoreModule, EditorComponent]
})
export class CodeExplorerComponent implements OnInit, OnDestroy {

  @Input() templateCode: string;
  @Input() templateName: string;
  @ViewChild('modal', { static: false })
  private modal: any;

  editorOptions: EditorComponent['editorOptions'];
  closeSubject: Subject<Substitution> = new Subject();
  labels: ModalLabels;

  private readonly destroy$ = new Subject<void>();

  async ngOnInit(): Promise<void> {
    this.editorOptions = {
      minimap: { enabled: true },
      language: 'javascript',
      renderWhitespace: 'none',
      tabSize: 4,
      readOnly: true
    };
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    this.closeSubject.complete();
  }

  onCancel(): void {
    this.modal?._dismiss();
  }
}
