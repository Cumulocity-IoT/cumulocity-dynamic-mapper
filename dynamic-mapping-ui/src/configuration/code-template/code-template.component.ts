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
import {
  Component, OnDestroy,
  OnInit, ViewEncapsulation
} from '@angular/core';
import { EditorComponent, loadMonacoEditor } from '@c8y/ngx-components/editor';
import { AlertService } from '@c8y/ngx-components';
import { BsModalService } from 'ngx-bootstrap/modal';
import { SharedService } from '../../shared/service/shared.service';
import { base64ToString, stringToBase64 } from '../../mapping/shared/util';
import { CODE_TEMPLATES } from '../shared/configuration.model';
import { FormGroup } from '@angular/forms';
import { CodeTemplates } from '../../shared';

let initializedMonaco = false;

@Component({
  selector: 'd11r-shared-code',
  templateUrl: 'code-template.component.html',
  styleUrls: ['./code-template.component.css'],
  encapsulation: ViewEncapsulation.None
})
export class SharedCodeComponent implements OnInit {
  codeTemplate: string;
  templateId: CODE_TEMPLATES = CODE_TEMPLATES.SHARED_CODE_TEMPLATE;
  formGroup: FormGroup;
  CodeTemplates = CODE_TEMPLATES;
  codeTemplates: CodeTemplates = {};
  isLoading = true;
  errorMessage = '';

  editorOptions: EditorComponent['editorOptions'] = {
    minimap: { enabled: true },
    //  renderValidationDecorations: "on",
    language: 'javascript',
  };

  codeEditorHelp = `Shared javascript code for creating substitutions. These functions can be referenced by all mappings that use code based substitutions.`;

  constructor(
    public bsModalService: BsModalService,
    private sharedService: SharedService,
    private alertService: AlertService,
  ) { }

  async ngOnInit(): Promise<void> {
    this.codeTemplates = await this.sharedService.getCodeTemplates();

    // Set default if available
    const keys = Object.keys(this.codeTemplates);
    this.templateId = keys[0] as CODE_TEMPLATES;
    this.codeTemplate = base64ToString(this.codeTemplates[this.templateId]);
  }

  isValidTemplateKey(key: string): boolean {
    return Object.values(CODE_TEMPLATES).includes(key as any);
  }

  async ngAfterViewInit(): Promise<void> {
    if (!initializedMonaco) {
      const monaco = await loadMonacoEditor();
      if (monaco) {
        initializedMonaco = true;
      }
    }
  }

  async clickedSaveSharedCode() {
    if (this.codeTemplate) {
      const encodeCode = stringToBase64(this.codeTemplate);
      this.sharedService.updateSharedCode(this.templateId, encodeCode);
      this.alertService.success("Saved code template");
      this.codeTemplates = await this.sharedService.getCodeTemplates();
    }
  }

  onValueCodeChange(value) {
    // console.log("code changed", value);
    this.codeTemplate = value;
  }

  onSelectTemplate(): void {
    this.codeTemplate = base64ToString(this.codeTemplates[this.templateId]);
  }

  getTemplateKeys(): string[] {
    return Object.keys(this.codeTemplates);
  }
}