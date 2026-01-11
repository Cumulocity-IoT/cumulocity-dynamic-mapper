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
import { CommonModule } from '@angular/common';
import { HttpStatusCode } from '@angular/common/http';
import { Component, OnInit, ViewChild, ViewEncapsulation } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { AlertService, CoreModule } from '@c8y/ngx-components';
import { EditorComponent } from '@c8y/ngx-components/editor';
import { gettext } from '@c8y/ngx-components/gettext';
import { BsModalService } from 'ngx-bootstrap/modal';
import { PopoverModule } from 'ngx-bootstrap/popover';
import { BehaviorSubject } from 'rxjs';
import { base64ToString, stringToBase64 } from '../../mapping/shared/util';
import { Direction, Feature, ManageTemplateComponent, Operation, createCustomUuid } from '../../shared';
import { SharedService } from '../../shared/service/shared.service';
import { CodeTemplate, CodeTemplateMap, TemplateType } from '../shared/configuration.model';
import { createCompletionProviderFlowFunction, createCompletionProviderSubstitutionAsCode } from '../../mapping/shared/stepper.model';

@Component({
  selector: 'd11r-shared-code',
  templateUrl: 'code-template.component.html',
  styleUrls: ['./code-template.component.css'],
  encapsulation: ViewEncapsulation.None,
  standalone: true,
  imports: [CoreModule, CommonModule, PopoverModule, EditorComponent, FormsModule]
})
export class CodeComponent implements OnInit {
  @ViewChild(EditorComponent, { static: false }) codeEditor: EditorComponent;

  codeTemplateDecoded: CodeTemplate;
  codeTemplatesDecoded: Map<string, CodeTemplate> = new Map<string, CodeTemplate>();
  codeTemplates: CodeTemplateMap;
  template: string;
  defaultTemplate: string;
  templateType: TemplateType;
  direction: Direction;
  TemplateType = TemplateType;
  codeTemplateEntries: CodeTemplate[] = [];
  codeTemplateEntries$: BehaviorSubject<CodeTemplate[]> = new BehaviorSubject<CodeTemplate[]>([]);

  editorOptions: EditorComponent['editorOptions'] = {
    minimap: { enabled: true },
    //  renderValidationDecorations: "on",
    language: 'javascript',
  };
  feature: Feature;
  private completionProviderDisposable: any;


  codeEditorHelp = `Shared code is evaluated across all mappings that utilize  <b>Smart Function JavaScript</b> or <b>Substitutions as JavaScript</b> for creating substitutions. The templates <b>Inbound</b> and <b>Outbound</b> are available in the code editor and can be customized according to your requirements per mapping.`;

  constructor(
    private bsModalService: BsModalService,
    private sharedService: SharedService,
    private alertService: AlertService,
    private router: Router,
    private route: ActivatedRoute

  ) { }

  async ngOnInit(): Promise<void> {
    this.feature = await this.route.snapshot.data['feature'];
    this.determineTemplateTypeFromUrl();
    this.template = this.defaultTemplate;

    await this.updateCodeTemplateEntries();
    this.codeTemplateDecoded = this.codeTemplatesDecoded.get(this.template);
    this.onSelectCodeTemplate();
  }

  private determineTemplateTypeFromUrl(): void {
    const url = this.router.url;
    const templateConfigs = [
      {
        pattern: /INBOUND_SUBSTITUTION_AS_CODE/,
        type: TemplateType.INBOUND_SUBSTITUTION_AS_CODE,
        direction: Direction.INBOUND,
        help: `The templates <b>Inbound</b> are available in the code editor and can be customized according to your requirements per mapping. They serve as sample to building substitutions in JavaScript. The function <code>function extractFromSource(ctx)</code> is called during the evaluation at runtime to define substitutions.`
      },
      {
        pattern: /OUTBOUND_SUBSTITUTION_AS_CODE/,
        type: TemplateType.OUTBOUND_SUBSTITUTION_AS_CODE,
        direction: Direction.OUTBOUND,
        help: `The templates <b>Outbound</b> are available in the code editor and can be customized according to your requirements per mapping. They serve as sample to building substitutions in JavaScript. The function <code>function extractFromSource(ctx)</code> is called during the evaluation at runtime to define substitutions.`
      },
      {
        pattern: /INBOUND_SMART_FUNCTION/,
        type: TemplateType.INBOUND_SMART_FUNCTION,
        direction: Direction.INBOUND,
        help: `The templates <b>Inbound for Smart Function</b> are available in the code editor and can be customized according to your requirements per mapping. They serve as sample for a predefined Smart Function for data transformation and create payload for Cumulocity API calls. The function <code>function onMessage(msg, context)</code> is called during evaluation at runtime to define the payload.`
      },
      {
        pattern: /OUTBOUND_SMART_FUNCTION/,
        type: TemplateType.OUTBOUND_SMART_FUNCTION,
        direction: Direction.OUTBOUND,
        help: `The templates <b>Outbound for Smart Function</b> are available in the code editor and can be customized according to your requirements per mapping. They serve as sample for a Smart Function (JavaScript) to create Broker Payload. The function <code>function onMessage(msg, context)</code> is called during evaluation at runtime to define the payload.`
      }
    ];

    const matchedConfig = templateConfigs.find(config => url.match(config.pattern));

    if (matchedConfig) {
      this.templateType = matchedConfig.type;
      this.direction = matchedConfig.direction;
      this.defaultTemplate = matchedConfig.type.toString();
      this.codeEditorHelp = matchedConfig.help;
    } else {
      this.templateType = TemplateType.SHARED;
      this.defaultTemplate = TemplateType.SHARED.toString();
      this.codeEditorHelp = `Shared code is evaluated across all mappings that utilize <b>Smart Function JavaScript</b> or <b>Substitutions as JavaScript</b> for creating substitutions. The system code shows the code with definitions of used wrapper classes used only for <b>Substitutions as JavaScript</b>.`;
    }
  }

  refresh() {
    this.updateCodeTemplateEntries();
  }

  ngAfterViewInit(): void {
    this.registerCompletionProvider();
  }

  private registerCompletionProvider(): void {
    if (!this.codeEditor || !this.codeEditor.editor) {
      return;
    }

    const monaco = (window as any).monaco;
    if (!monaco) {
      console.warn('Monaco editor not available');
      return;
    }

    // Dispose previous provider if exists
    if (this.completionProviderDisposable) {
      this.completionProviderDisposable.dispose();
    }

    // Determine which completion provider to register based on template type
    let completionProvider: any;

    if (this.templateType === TemplateType.INBOUND_SMART_FUNCTION ||
        this.templateType === TemplateType.OUTBOUND_SMART_FUNCTION) {
      // Register Flow Function completion provider (SMART_FUNCTION)
      completionProvider = createCompletionProviderFlowFunction(monaco);
    } else if (this.templateType === TemplateType.INBOUND_SUBSTITUTION_AS_CODE ||
               this.templateType === TemplateType.OUTBOUND_SUBSTITUTION_AS_CODE) {
      // Register Substitution as Code completion provider
      completionProvider = createCompletionProviderSubstitutionAsCode(monaco);
    } else {
      // For SHARED and SYSTEM templates, register both providers
      const smartFunctionProvider = createCompletionProviderFlowFunction(monaco);
      const substitutionProvider = createCompletionProviderSubstitutionAsCode(monaco);

      // Register both providers
      const disposable1 = monaco.languages.registerCompletionItemProvider('javascript', smartFunctionProvider);
      const disposable2 = monaco.languages.registerCompletionItemProvider('javascript', substitutionProvider);

      // Store combined disposable
      this.completionProviderDisposable = {
        dispose: () => {
          disposable1.dispose();
          disposable2.dispose();
        }
      };
      return;
    }

    // Register single provider
    if (completionProvider) {
      this.completionProviderDisposable = monaco.languages.registerCompletionItemProvider(
        'javascript',
        completionProvider
      );
    }
  }

  async updateCodeTemplateEntries(): Promise<void> {
    const defaultSet = this.getDefaultTemplateSet();
    this.codeTemplates = await this.sharedService.getCodeTemplates();

    this.codeTemplateEntries = Object.entries(this.codeTemplates)
      .map(([key, template]) => ({
        key,
        id: undefined,
        code: undefined,
        name: template.name,
        description: template.description,
        templateType: template.templateType,
        internal: template.internal,
        readonly: template.readonly,
        defaultTemplate: template.defaultTemplate
      }))
      .filter(temp => defaultSet.includes(temp.templateType));

    this.codeTemplateEntries$.next(this.codeTemplateEntries);
    this.decodeCodeTemplates();
  }

  private getDefaultTemplateSet(): string[] {
    const defaultSet = [this.defaultTemplate];
    if (this.defaultTemplate === TemplateType.SHARED.toString()) {
      defaultSet.push(TemplateType.SYSTEM.toString());
    }
    return defaultSet;
  }

  private decodeCodeTemplates(): void {
    Object.entries(this.codeTemplates).forEach(([key, template]) => {
      try {
        const decodedCode = base64ToString(template.code);
        this.codeTemplatesDecoded.set(key, {
          id: key,
          name: template.name,
          description: template.description,
          templateType: template.templateType,
          code: decodedCode,
          internal: template.internal,
          readonly: template.readonly,
          defaultTemplate: false
        });
      } catch (error) {
        this.codeTemplatesDecoded.set(key, {
          id: key,
          name: template.name,
          templateType: template.templateType,
          code: '// Code Template not valid!',
          internal: template.internal,
          readonly: template.readonly,
          defaultTemplate: false
        });
      }
    });
  }

  async onInitSystemCodeTemplate() {
    const response = await this.sharedService.runOperation({ operation: Operation.INIT_CODE_TEMPLATES });

    if (response.status === HttpStatusCode.Created) {
      this.alertService.success(gettext('Reset system code template.'));
    } else {
      this.alertService.danger(gettext('Failed to reset system code template!'));
    }
  }

  async onSaveCodeTemplate() {
    if (!this.codeTemplateDecoded) return;

    const encodedCode = stringToBase64(this.codeTemplateDecoded.code);
    await this.sharedService.updateCodeTemplate(this.template, {
      ...this.codeTemplateDecoded,
      code: encodedCode
    });

    this.alertService.success(gettext('Saved code template'));
    await this.updateCodeTemplateEntries();
  }

  async onDeleteCodeTemplate() {
    if (!this.codeTemplateDecoded) return;

    await this.sharedService.deleteCodeTemplate(this.template);
    this.alertService.success(gettext('Deleted code template'));
    this.template = this.defaultTemplate;
    await this.updateCodeTemplateEntries();
  }

  async onRenameCodeTemplate() {
    if (!this.codeTemplateDecoded) return;

    await this.openTemplateModal('RENAME', this.codeTemplateDecoded.name, async (updatedTemplate) => {
      this.codeTemplateDecoded.name = updatedTemplate.name;
      const encodedCode = stringToBase64(this.codeTemplateDecoded.code);
      await this.sharedService.updateCodeTemplate(this.template, {
        ...this.codeTemplateDecoded,
        code: encodedCode
      });
      this.alertService.success(gettext('Renamed code template'));
    });
  }

  async onDuplicateCodeTemplate() {
    if (!this.codeTemplateDecoded) return;

    await this.openTemplateModal('COPY', `${this.codeTemplateDecoded.name} - Copy`, async (updatedTemplate) => {
      const encodedCode = stringToBase64(this.codeTemplateDecoded.code);
      await this.sharedService.createCodeTemplate({
        ...this.codeTemplateDecoded,
        name: updatedTemplate.name,
        code: encodedCode,
        id: createCustomUuid(),
        internal: false,
        readonly: false
      });
      this.alertService.success(gettext('Copied code template'));
    });
  }

  private async openTemplateModal(
    action: string,
    defaultName: string,
    onSuccess: (template: Partial<CodeTemplate>) => Promise<void>
  ): Promise<void> {
    const initialState = {
      action,
      codeTemplate: { name: defaultName }
    };

    const modalRef = this.bsModalService.show(ManageTemplateComponent, { initialState });

    modalRef.content.closeSubject.subscribe(async (codeTemplate: Partial<CodeTemplate>) => {
      if (codeTemplate) {
        await onSuccess(codeTemplate);
      }
      this.template = this.defaultTemplate;
      await this.updateCodeTemplateEntries();
    });
  }

  onValueCodeChange(value: string) {
    // console.log("code changed", value);
    this.codeTemplateDecoded.code = value;
  }

  onSelectCodeTemplate(): void {
    this.codeTemplateDecoded = this.codeTemplatesDecoded.get(this.template);
  }

}