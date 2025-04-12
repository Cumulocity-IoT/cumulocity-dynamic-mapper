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
  Component,
  OnInit, ViewEncapsulation
} from '@angular/core';
import { EditorComponent, loadMonacoEditor } from '@c8y/ngx-components/editor';
import { AlertService } from '@c8y/ngx-components';
import { BsModalService } from 'ngx-bootstrap/modal';
import { SharedService } from '../../shared/service/shared.service';
import { base64ToString, stringToBase64 } from '../../mapping/shared/util';
import { CodeTemplate, CodeTemplateMap, TemplateType } from '../shared/configuration.model';
import { FormGroup } from '@angular/forms';
import { ManageTemplateComponent, createCustomUuid } from '../../shared';
import { BehaviorSubject } from 'rxjs';

let initializedMonaco = false;

@Component({
  selector: 'd11r-shared-code',
  templateUrl: 'code-template.component.html',
  styleUrls: ['./code-template.component.css'],
  encapsulation: ViewEncapsulation.None
})
export class SharedCodeComponent implements OnInit {
  codeTemplateDecoded: CodeTemplate;
  codeTemplatesDecoded: Map<string, CodeTemplate> = new Map<string, CodeTemplate>();
  codeTemplates: CodeTemplateMap;
  templateId: string = TemplateType.SHARED;
  formGroup: FormGroup;
  isLoading = true;
  errorMessage = '';
  TemplateType = TemplateType;
  codeTemplateEntries: CodeTemplate[] = [];
  codeTemplateEntries$: BehaviorSubject<CodeTemplate[]> = new BehaviorSubject<CodeTemplate[]>([]);

  editorOptions: EditorComponent['editorOptions'] = {
    minimap: { enabled: true },
    //  renderValidationDecorations: "on",
    language: 'javascript',
  };

  codeEditorHelp = `Shared code is evaluated across all mappings that utilize <b>Define substitutions as JavaScript</b> for creating substitutions. The templates <b>Inbound</b> and <b>Outbound</b> are available in the code editor and can be customized according to your requirements per mapping.`;

  constructor(
    public bsModalService: BsModalService,
    private sharedService: SharedService,
    private alertService: AlertService,
  ) { }

  async ngOnInit(): Promise<void> {
    await this.updateCodeTemplateEntries(); // Call this after setting codeTemplates
    this.codeTemplateDecoded = this.codeTemplatesDecoded.get(this.templateId);
    console.log("CodeTemplateEntries after init:", this.codeTemplateEntries);
  }

  refresh() {
    this.updateCodeTemplateEntries(); // Call this after setting codeTemplates

  }

  async ngAfterViewInit(): Promise<void> {
    if (!initializedMonaco) {
      const monaco = await loadMonacoEditor();
      if (monaco) {
        initializedMonaco = true;
      }
    }
  }

  async updateCodeTemplateEntries(): Promise<void> {
    this.codeTemplates = await this.sharedService.getCodeTemplates();
    this.codeTemplateEntries = Object.entries(this.codeTemplates).map(([key, template]) => ({
      key,
      id: undefined,
      code: undefined,
      name: template.name,
      type: template.type,
      internal: template.internal,
      readonly: template.readonly,
      defaultTemplate: template.defaultTemplate
    }));
    this.codeTemplateEntries$.next(this.codeTemplateEntries);
    // Iterate and decode
    Object.entries(this.codeTemplates).forEach(([key, template]) => {
      try {
        const decodedCode = base64ToString(template.code);
        this.codeTemplatesDecoded.set(key, {
          id: key, name: template.name,
          type: template.type, code: decodedCode, internal: template.internal, readonly: template.readonly, defaultTemplate: false,
        });
      } catch (error) {
        this.codeTemplatesDecoded.set(key, {
          id: key, name: template.name,
          type: template.type, code: "// Code Template not valid!", internal: template.internal, readonly: template.readonly, defaultTemplate: false,
        });
      }
    });
  }

  async onSaveCodeTemplate() {
    if (this.codeTemplateDecoded) {
      const encodeCode = stringToBase64(this.codeTemplateDecoded.code);
      const templateToUpdate = this.codeTemplateDecoded;
      await this.sharedService.updateCodeTemplate(this.templateId, {
        ...templateToUpdate, code: encodeCode
      });
      this.alertService.success("Saved code template");
      this.updateCodeTemplateEntries();
    }
  }

  async onDeleteCodeTemplate() {
    if (this.codeTemplateDecoded) {
      this.sharedService.deleteCodeTemplate(this.templateId);
      this.alertService.success("Deleted code template");
      this.updateCodeTemplateEntries();
      this.templateId = TemplateType.SHARED;
    }
  }

  async onRenameCodeTemplate() {
    if (this.codeTemplateDecoded) {
      const initialState = {
        action: 'RENAME',
        name: this.codeTemplateDecoded.name
      };
      const modalRef = this.bsModalService.show(ManageTemplateComponent, { initialState });

      modalRef.content.closeSubject.subscribe(async (name) => {
        // console.log('Configuration after edit:', editedConfiguration);
        if (name) {
          this.codeTemplateDecoded.name = name;
          const encodeCode = stringToBase64(this.codeTemplateDecoded.code);
          const templateToUpdate = this.codeTemplateDecoded;
          await this.sharedService.updateCodeTemplate(this.templateId, {
            ...templateToUpdate, code: encodeCode
          });
          this.alertService.success("Renamed code template");
        }
        this.updateCodeTemplateEntries();
        this.templateId = TemplateType.SHARED;
      });

    }
  }

  async onAddCodeTemplate() {
    if (this.codeTemplateDecoded) {
      const initialState = {
        action: 'COPY',
        name: this.codeTemplateDecoded.name
      };
      const modalRef = this.bsModalService.show(ManageTemplateComponent, { initialState });

      modalRef.content.closeSubject.subscribe(async (name) => {
        // console.log('Configuration after edit:', editedConfiguration);
        if (name) {
          this.codeTemplateDecoded.name = name;
          const encodeCode = stringToBase64(this.codeTemplateDecoded.code);
          const templateToUpdate = this.codeTemplateDecoded;
          await this.sharedService.createCodeTemplate({
            ...templateToUpdate, code: encodeCode, id: createCustomUuid(), internal: false
          });
          this.alertService.success("Copied code template");
        }
        this.updateCodeTemplateEntries();
        this.templateId = TemplateType.SHARED;
      });

    }
  }

  onValueCodeChange(value) {
    // console.log("code changed", value);
    this.codeTemplateDecoded.code = value;
  }

  onSelectCodeTemplate(): void {
    this.codeTemplateDecoded = this.codeTemplatesDecoded.get(this.templateId);
  }

}