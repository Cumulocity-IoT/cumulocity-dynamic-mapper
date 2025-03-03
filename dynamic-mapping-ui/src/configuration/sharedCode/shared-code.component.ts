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

let initializedMonaco = false;

@Component({
  selector: 'd11r-shared-code',
  templateUrl: 'shared-code.component.html',
  encapsulation: ViewEncapsulation.None
})
export class SharedCodeComponent implements OnInit, OnDestroy {
  code: string;

  editorOptions: EditorComponent['editorOptions'] = {
    minimap: { enabled: true },
    //  renderValidationDecorations: "on",
    language: 'javascript',
  };

  codeEditorHelp = 'javascript function for creating substitutions. Please do not change the methods signature <code>function extractFromSource(ctx)</code>. <br> Define substitutions: <code>Substitution(String key, Object value, String type, String repairStrategy)</code> <br> with <code>type</code>: <code>"ARRAY"</code>, <code>"IGNORE"</code>, <code>"NUMBER"</code>, <code>"OBJECT"</code>, <code>"TEXTUAL"</code> <br>and <code>repairStrategy</code>: <br><code>"DEFAULT"</code>, <code>"USE_FIRST_VALUE_OF_ARRAY"</code>, <code>"USE_LAST_VALUE_OF_ARRAY"</code>, <code>"IGNORE"</code>, <code>"REMOVE_IF_MISSING_OR_NULL"</code>,<code>"CREATE_IF_MISSING"</code>';

  constructor(
    public bsModalService: BsModalService,
    private sharedService: SharedService,
    private alertService: AlertService,
  ) { }

  ngOnInit() {
    const code = this.sharedService.getSharedCode();
    this.code = base64ToString(code);
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


  async onCommitButton() {
    if (this.code) {
      const encodeCode = stringToBase64(this.code);
      this.sharedService.getSharedCode();
    }
  }


  onValueCodeChange(value) {
    // console.log("code changed", value);
    this.code = value;
  }

}
