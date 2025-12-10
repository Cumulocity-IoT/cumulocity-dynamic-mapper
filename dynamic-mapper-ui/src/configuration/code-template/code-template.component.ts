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
import { AlertService, CoreModule } from '@c8y/ngx-components';
import { BsModalService } from 'ngx-bootstrap/modal';
import { SharedService } from '../../shared/service/shared.service';
import { base64ToString, stringToBase64 } from '../../mapping/shared/util';
import { CodeTemplate, CodeTemplateMap, TemplateType } from '../shared/configuration.model';
import { FormGroup } from '@angular/forms';
import { Direction, Feature, ManageTemplateComponent, Operation, createCustomUuid } from '../../shared';
import { BehaviorSubject } from 'rxjs';
import { ActivatedRoute, Router } from '@angular/router';
import { HttpStatusCode } from '@angular/common/http';
import { createCompletionProviderSubstitutionAsCode } from '../../mapping/shared/stepper.model';
import { gettext } from '@c8y/ngx-components/gettext';
import { CommonModule } from '@angular/common';

let initializedMonaco = false;

@Component({
  selector: 'd11r-shared-code',
  templateUrl: 'code-template.component.html',
  styleUrls: ['./code-template.component.css'],
  encapsulation: ViewEncapsulation.None,
  standalone: true,
  imports: [CoreModule, CommonModule, EditorComponent]
})
export class CodeComponent implements OnInit {
  codeTemplateDecoded: CodeTemplate;
  codeTemplatesDecoded: Map<string, CodeTemplate> = new Map<string, CodeTemplate>();
  codeTemplates: CodeTemplateMap;
  template: string;
  defaultTemplate: string;
  templateType: TemplateType;
  direction: Direction;
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
  feature: Feature;


  codeEditorHelp = `Shared code is evaluated across all mappings that utilize <b>Define substitutions as JavaScript</b> for creating substitutions. The templates <b>Inbound</b> and <b>Outbound</b> are available in the code editor and can be customized according to your requirements per mapping.`;

  constructor(
    private bsModalService: BsModalService,
    private sharedService: SharedService,
    private alertService: AlertService,
    private router: Router,
    private route: ActivatedRoute

  ) { }

  async ngOnInit(): Promise<void> {

    this.feature = await this.route.snapshot.data['feature'];
    const href = this.router.url;
    // First determine the template type based on URL
    if (href.match(/c8y-pkg-dynamic-mapper\/node3\/codeTemplate\/INBOUND_SUBSTITUTION_AS_CODE/g)) {
      // MIGRATION
      // this.templateType = TemplateType.INBOUND;
      this.templateType = TemplateType.INBOUND_SUBSTITUTION_AS_CODE;
      this.direction = Direction.INBOUND;
      this.defaultTemplate = TemplateType.INBOUND_SUBSTITUTION_AS_CODE.toString();
      this.codeEditorHelp = `The templates <b>Inbound</b> are available in the code editor and can be customized according to your requirements per mapping. They serve as sample to building substitutions in JavaScript. The function <code>function extractFromSource(ctx) {} </code> is called during the evaluation at runtime to define substitutions.`;
    } else if (href.match(/c8y-pkg-dynamic-mapper\/node3\/codeTemplate\/OUTBOUND_SUBSTITUTION_AS_CODE/g)) {
      // MIGRATION
      // this.templateType = TemplateType.OUTBOUND;
      this.templateType = TemplateType.OUTBOUND_SUBSTITUTION_AS_CODE;
      this.direction = Direction.OUTBOUND;
      this.defaultTemplate = TemplateType.OUTBOUND_SUBSTITUTION_AS_CODE.toString();
      this.codeEditorHelp = `The templates <b>Outbound</b> are available in the code editor and can be customized according to your requirements per mapping. They serve as sample to building substitutions in JavaScript. The function <code>function extractFromSource(ctx) {} </code> is called during the evaluation at runtime to define substitutions.`;
    } else if (href.match(/c8y-pkg-dynamic-mapper\/node3\/codeTemplate\/INBOUND_SMART_FUNCTION/g)) {
      // MIGRATION
      // this.templateType = TemplateType.INBOUND;
      this.templateType = TemplateType.INBOUND_SMART_FUNCTION;
      this.direction = Direction.INBOUND;
      this.defaultTemplate = TemplateType.INBOUND_SMART_FUNCTION.toString();
      this.codeEditorHelp = `The templates <b>Inbound for Smart Function</b> are available in the code editor and can be customized according to your requirements per mapping. They serve as sample for a predefined Smart Function for data transformation and create payload for Cumulocity API calls. The function <code>function onMessage(msg, context) </code> is called during evaluation at runtime to define the payload.`;
    } else if (href.match(/c8y-pkg-dynamic-mapper\/node3\/codeTemplate\/OUTBOUND_SMART_FUNCTION/g)) {
      // MIGRATION
      // this.templateType = TemplateType.OUTBOUND;
      this.templateType = TemplateType.OUTBOUND_SMART_FUNCTION;
      this.direction = Direction.OUTBOUND;
      this.defaultTemplate = TemplateType.OUTBOUND_SMART_FUNCTION.toString();
      this.codeEditorHelp = `The templates <b>Outbound for Smart Function</b> are available in the code editor and can be customized according to your requirements per mapping. They serve as sample for a Smart Function (JavaScript) to create Broker Payload. The function <code>function extractFromSource(ctx) {} </code> is called during evaluation at runtime to define the payload.`;
    } else {
      this.templateType = TemplateType.SHARED;
      this.defaultTemplate = TemplateType.SHARED.toString();
      this.codeEditorHelp = `Shared code is evaluated across all mappings that utilize <b>Define substitutions as JavaScript</b> for creating substitutions. The system code shows the code with definitions of used wrapper classes.`;
    }
    this.template = this.defaultTemplate;

    await this.updateCodeTemplateEntries(); // Call this after setting codeTemplates
    this.codeTemplateDecoded = this.codeTemplatesDecoded.get(this.template);
    // console.log("CodeTemplateEntries after init:", this.codeTemplateEntries);

    this.onSelectCodeTemplate();

  }

  refresh() {
    this.updateCodeTemplateEntries(); // Call this after setting codeTemplates
  }

  async ngAfterViewInit(): Promise<void> {
    if (!initializedMonaco) {
      const monaco = await loadMonacoEditor();
      monaco.languages.registerCompletionItemProvider('javascript', createCompletionProviderSubstitutionAsCode(monaco));
      if (monaco) {
        initializedMonaco = true;
      }
    }
  }

  async updateCodeTemplateEntries(): Promise<void> {
    let defaultSet = [this.defaultTemplate];
    if (this.defaultTemplate == TemplateType.SHARED.toString()) {
      defaultSet.push(TemplateType.SYSTEM.toString());
    }
    this.codeTemplates = await this.sharedService.getCodeTemplates();
    this.codeTemplateEntries = Object.entries(this.codeTemplates).map(([key, template]) => ({
      key,
      id: undefined,
      code: undefined,
      name: template.name,
      description: template.description,
      templateType: template.templateType,
      internal: template.internal,
      readonly: template.readonly,
      defaultTemplate: template.defaultTemplate
    })).filter(temp => defaultSet.includes(temp.templateType));
    this.codeTemplateEntries$.next(this.codeTemplateEntries);
    // Iterate and decode
    Object.entries(this.codeTemplates).forEach(([key, template]) => {
      try {
        const decodedCode = base64ToString(template.code);
        this.codeTemplatesDecoded.set(key, {
          id: key, name: template.name, description: template.description,
          templateType: template.templateType, code: decodedCode, internal: template.internal, readonly: template.readonly, defaultTemplate: false,
        });
      } catch (error) {
        this.codeTemplatesDecoded.set(key, {
          id: key, name: template.name,
          templateType: template.templateType, code: "// Code Template not valid!", internal: template.internal, readonly: template.readonly, defaultTemplate: false,
        });
      }
    });
  }

  async onInitSystemCodeTemplate() {
    const response1 = await this.sharedService.runOperation(
      { operation: Operation.INIT_CODE_TEMPLATES }
    );
    // console.log('Details reconnect2NotificationEndpoint', response1);
    if (response1.status === HttpStatusCode.Created) {
      this.alertService.success(gettext('Reset system code template.'));
    } else {
      this.alertService.danger(gettext('Failed to reset system code template!'));
    }
  }

  async onSaveCodeTemplate() {
    if (this.codeTemplateDecoded) {
      const encodeCode = stringToBase64(this.codeTemplateDecoded.code);
      const templateToUpdate = this.codeTemplateDecoded;
      await this.sharedService.updateCodeTemplate(this.template, {
        ...templateToUpdate, code: encodeCode
      });
      this.alertService.success("Saved code template");
      this.updateCodeTemplateEntries();
    }
  }

  async onDeleteCodeTemplate() {
    if (this.codeTemplateDecoded) {
      this.sharedService.deleteCodeTemplate(this.template);
      this.alertService.success("Deleted code template");
      this.template = this.defaultTemplate;
      this.updateCodeTemplateEntries();
    }
  }

  async onRenameCodeTemplate() {
    if (this.codeTemplateDecoded) {
      const initialState = {
        action: 'RENAME',
        codeTemplate: { name: this.codeTemplateDecoded.name }
      };
      const modalRef = this.bsModalService.show(ManageTemplateComponent, { initialState });

      modalRef.content.closeSubject.subscribe(async (codeTemplate: Partial<CodeTemplate>) => {
        // console.log('Configuration after edit:', editedConfiguration);
        if (codeTemplate) {
          this.codeTemplateDecoded.name = codeTemplate.name;
          const encodeCode = stringToBase64(this.codeTemplateDecoded.code);
          const templateToUpdate = this.codeTemplateDecoded;
          await this.sharedService.updateCodeTemplate(this.template, {
            ...templateToUpdate, code: encodeCode
          });
          this.alertService.success("Renamed code template");
        }
        this.template = this.defaultTemplate;
        this.updateCodeTemplateEntries();
      });

    }
  }

  async onDuplicateCodeTemplate() {
    if (this.codeTemplateDecoded) {
      const initialState = {
        action: 'COPY',
        codeTemplate: { name: this.codeTemplateDecoded.name + ' - Copy' }
      };
      const modalRef = this.bsModalService.show(ManageTemplateComponent, { initialState });

      modalRef.content.closeSubject.subscribe(async (codeTemplate: Partial<CodeTemplate>) => {
        // console.log('Configuration after edit:', editedConfiguration);
        if (codeTemplate) {
          this.codeTemplateDecoded.name = codeTemplate.name;
          const encodeCode = stringToBase64(this.codeTemplateDecoded.code);
          const templateToUpdate = this.codeTemplateDecoded;
          await this.sharedService.createCodeTemplate({
            ...templateToUpdate, code: encodeCode, id: createCustomUuid(), internal: false, readonly: false
          });
          this.alertService.success("Copied code template");
        }
        this.updateCodeTemplateEntries();
        this.template = this.defaultTemplate;
      });

    }
  }

  onValueCodeChange(value: string) {
    // console.log("code changed", value);
    this.codeTemplateDecoded.code = value;
  }

  onSelectCodeTemplate(): void {
    this.codeTemplateDecoded = this.codeTemplatesDecoded.get(this.template);
  }

}