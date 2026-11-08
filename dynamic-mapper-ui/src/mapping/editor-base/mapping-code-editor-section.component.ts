/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
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
import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CoreModule } from '@c8y/ngx-components';
import { EditorComponent } from '@c8y/ngx-components/editor';
import { PopoverModule } from 'ngx-bootstrap/popover';
import { TemplateType } from '../../shared/configuration/configuration.model';

/**
 * The code-editor block of the transformation step: label and help, "Generate with AI", the
 * code template select and the Monaco editor. Projected into the transformation step by both the
 * stepper and the unified editor; it holds no state, the host owns code and template selection.
 *
 * `dataCyPrefix` keeps the data-cy attributes distinct per host (e2e selectors).
 */
@Component({
  selector: 'd11r-mapping-code-editor-section',
  templateUrl: 'mapping-code-editor-section.component.html',
  standalone: true,
  imports: [CoreModule, CommonModule, EditorComponent, PopoverModule]
})
export class MappingCodeEditorSectionComponent {
  @Input() label = '';
  @Input() help = '';
  @Input() code?: string;
  @Input() editorOptions?: EditorComponent['editorOptions'];
  @Input() templateId?: TemplateType;
  @Input() codeTemplateItems: Array<{ label: string; value: string }> = [];
  @Input() aiButtonLabel = 'Generate with AI';
  @Input() aiGenerationDisabled = false;
  @Input() aiAgentDeployed = false;
  @Input() aiCapabilitiesDisabled = false;
  /** False while the host's templates are still loading, so the editor is not created too early. */
  @Input() showEditor = true;
  @Input() showInternalNote = false;
  @Input() dataCyPrefix = 'dm-mapping-stepper';

  @Output() codeChange = new EventEmitter<string>();
  @Output() templateSelected = new EventEmitter<TemplateType>();
  @Output() createTemplate = new EventEmitter<void>();
  @Output() generate = new EventEmitter<void>();
  @Output() editorInit = new EventEmitter<void>();
}
