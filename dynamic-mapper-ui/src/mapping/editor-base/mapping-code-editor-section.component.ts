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
import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output, ViewChild } from '@angular/core';
import { CoreModule } from '@c8y/ngx-components';
import { EditorComponent } from '@c8y/ngx-components/editor';
import { PopoverModule } from 'ngx-bootstrap/popover';
import { TemplateType } from '../../shared/configuration/configuration.model';

/**
 * The code-editor block of the transformation step: label and help, "Generate with AI", the
 * code template select and the Monaco editor. Projected into the transformation step by both the
 * stepper and the unified editor; it holds no state, the host owns code and template selection.
 * `OnPush`: it re-renders when an input binding changes, which is all it needs.
 *
 * `dataCyPrefix` keeps the data-cy attributes distinct per host (e2e selectors).
 */
@Component({
  selector: 'd11r-mapping-code-editor-section',
  templateUrl: 'mapping-code-editor-section.component.html',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
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
  /** Selecting a template only selects it; this asks the host to put the template's code into the editor. */
  @Output() replaceCode = new EventEmitter<void>();
  @Output() createTemplate = new EventEmitter<void>();
  @Output() generate = new EventEmitter<void>();
  @Output() editorInit = new EventEmitter<void>();

  /**
   * `c8y-select` reports the selected item (`{ label, value }`), not its value. The host looks the
   * template up by key, so pass on only the key. Passing the item on made every lookup miss.
   */
  onTemplateSelected(selected: { value: string; label?: string } | string | null | undefined): void {
    const key = typeof selected === 'string' ? selected : selected?.value;
    if (key) {
      this.templateSelected.emit(key as TemplateType);
    }
  }

  /** The code editor, once `showEditor` has created it. Hosts write into it through {@link writeCode}. */
  @ViewChild('codeEditor') codeEditorRef?: EditorComponent;

  /**
   * Puts `code` into the editor now, without waiting for the `code` binding to reach it. Used when the
   * host replaces the code (template, AI result). Safe before the editor exists: it then starts with
   * the value of the `code` binding.
   */
  writeCode(code: string): void {
    this.codeEditorRef?.writeValue(code);
  }
}
