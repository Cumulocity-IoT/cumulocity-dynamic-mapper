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
import { CodeTemplates } from '../shared/configuration.model';

let initializedMonaco = false;

@Component({
  selector: 'd11r-shared-code',
  templateUrl: 'code-template.component.html',
  styleUrls: ['./code-template.component.css'],
  encapsulation: ViewEncapsulation.None
})
export class SharedCodeComponent implements OnInit, OnDestroy {
  codeTemplate: string;
  templateId: CodeTemplates;

  editorOptions: EditorComponent['editorOptions'] = {
    minimap: { enabled: true },
    //  renderValidationDecorations: "on",
    language: 'javascript',
  };

  codeEditorHelp = `Shared javascript code for creating substitutions. These functions can be referenced by all mappings that use code based substitutions. The minimal code snippet is <br><code>const SubstitutionResult = Java.type('dynamic.mapping.processor.model.SubstitutionResult');</code><br><code>const Substitution = Java.type('dynamic.mapping.processor.model.Substitution');</code>`;

  constructor(
    public bsModalService: BsModalService,
    private sharedService: SharedService,
    private alertService: AlertService,
  ) { }

  async ngOnInit() {
    const codeTemplate = await this.sharedService.getCodeTemplate(this.templateId);
    this.codeTemplate = base64ToString(codeTemplate);
  }

  async ngAfterViewInit(): Promise<void> {
    if (!initializedMonaco) {
      const monaco = await loadMonacoEditor();
      if (monaco) {
        initializedMonaco = true;
      }
    }
  }

  ngOnDestroy() {
  }

  async clickedSaveSharedCode() {
    if (this.codeTemplate) {
      const encodeCode = stringToBase64(this.codeTemplate);
      this.sharedService.updateSharedCode(this.templateId, encodeCode);
    }
  }

  onValueCodeChange(value) {
    // console.log("code changed", value);
    this.codeTemplate = value;
  }

}
