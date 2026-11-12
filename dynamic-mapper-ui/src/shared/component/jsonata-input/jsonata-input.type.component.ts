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
 */

import { ChangeDetectionStrategy, Component, NgZone, OnDestroy, OnInit, ViewEncapsulation, inject, signal } from '@angular/core';
import { FormsModule, ReactiveFormsModule } from '@angular/forms';
import { EditorComponent, loadMonacoEditor } from '@c8y/ngx-components/editor';
import { FieldType } from '@ngx-formly/core';
import { TranslateService } from '@ngx-translate/core';
import type * as Monaco from 'monaco-editor';
import {
  JSONATA_LANGUAGE_ID,
  JSONATA_MARKER_OWNER,
  findParseError,
  registerJsonataLanguage,
  registerPathCompletion,
  toMarker
} from './jsonata-language';

const MIN_HEIGHT = 34;
const MAX_HEIGHT = 150;

/**
 * Formly field type `d11r-jsonata-input`: a compact Monaco editor for a JSONata expression.
 *
 * Adds syntax highlighting, function completion with signatures, hover help, completion of the
 * property names of a template, and syntax errors marked at their position while typing.
 * The value is a plain string, so the field replaces `d11r-input` without changes to the model.
 *
 * Optional field props:
 * - `readonly: true`: shows the expression without letting the user change it.
 * - `readonlyMessage`: shown instead of Monaco's own message when the user tries to edit a read-only field.
 * - `templateProvider: () => unknown`: returns the JSON template whose keys are offered as path completion.
 */
@Component({
  selector: 'd11r-formly-field-d11r-jsonata-input',
  template: `
    <div class="form-control d11r-jsonata-input" [attr.data-cy]="props.attributes?.['data-cy']" [class.is-invalid]="showError" [class.is-disabled]="formControl.disabled">
      @if (isEmpty() && props.placeholder) {
        <span class="d11r-jsonata-placeholder" [textContent]="props.placeholder"></span>
      }
      <c8y-editor
        [attr.data-cy]="'dm-jsonata-editor-' + key"
        [editorOptions]="editorOptions"
        [style.height.px]="height()"
        [formControl]="formControl"
        (editorInit)="onEditorInit($event)"
      ></c8y-editor>
    </div>
  `,
  styles: [
    `
      .d11r-jsonata-input {
        position: relative;
        padding: 0;
        height: auto;
        overflow: hidden;
        /* Explicit, so the field reads as an input even where the theme styles .form-control without a frame. */
        border: 1px solid var(--c8y-form-control-border-color, #8a94a3);
        border-radius: 2px;
        background: var(--c8y-form-control-background, #fff);
      }
      .d11r-jsonata-input:hover {
        border-color: var(--c8y-form-control-border-color-hover, #5b6573);
      }
      .d11r-jsonata-input:focus-within {
        border-color: var(--c8y-brand-primary, #1776bf);
        box-shadow: 0 0 0 1px var(--c8y-brand-primary, #1776bf);
      }
      .d11r-jsonata-input.is-invalid {
        border-color: var(--c8y-state-danger, #d5167f);
      }
      .d11r-jsonata-input.is-disabled {
        opacity: 0.7;
      }
      .d11r-jsonata-input c8y-editor {
        display: block;
        width: 100%;
      }
      .d11r-jsonata-placeholder {
        position: absolute;
        top: 8px;
        left: 8px;
        right: 6px;
        z-index: 1;
        pointer-events: none;
        overflow: hidden;
        white-space: nowrap;
        text-overflow: ellipsis;
        font-size: 12px;
        opacity: 0.6;
      }
    `
  ],
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [FormsModule, ReactiveFormsModule, EditorComponent]
})
export class JsonataInputTypeComponent extends FieldType implements OnInit, OnDestroy {
  private readonly zone = inject(NgZone);
  private readonly translateService = inject(TranslateService);
  private readonly JSONATA = require('jsonata');
  private readonly disposables: Monaco.IDisposable[] = [];

  readonly height = signal(MIN_HEIGHT);
  readonly isEmpty = signal(true);

  editorOptions: Monaco.editor.IStandaloneEditorConstructionOptions = {
    language: JSONATA_LANGUAGE_ID,
    minimap: { enabled: false },
    lineNumbers: 'off',
    glyphMargin: false,
    folding: false,
    lineDecorationsWidth: 6,
    lineNumbersMinChars: 0,
    renderLineHighlight: 'none',
    overviewRulerLanes: 0,
    hideCursorInOverviewRuler: true,
    overviewRulerBorder: false,
    scrollBeyondLastLine: false,
    scrollbar: { vertical: 'auto', horizontal: 'hidden', alwaysConsumeMouseWheel: false },
    wordWrap: 'on',
    fontSize: 12,
    automaticLayout: true,
    // Suggestion and hover widgets are drawn outside the one-line host instead of being clipped by it.
    fixedOverflowWidgets: true,
    padding: { top: 8, bottom: 8 },
    contextmenu: false,
    quickSuggestions: { other: true, comments: false, strings: false },
    suggestOnTriggerCharacters: true
  };

  ngOnInit(): void {
    // `props.readonly` shows the expression, with its highlighting, without letting it be edited.
    // Set once, before the editor is created: a new options object on every check would rebuild it.
    if (this.props['readonly']) {
      const hint = this.props['readonlyMessage'] as string | undefined;
      this.editorOptions = {
        ...this.editorOptions,
        readOnly: true,
        domReadOnly: true,
        // Replaces Monaco's "Cannot edit in read-only editor" with where the expression can be edited.
        readOnlyMessage: { value: hint ? this.translateService.instant(hint) : '' },
        cursorWidth: 0
      };
    }
  }

  async onEditorInit(editor: Monaco.editor.IStandaloneCodeEditor): Promise<void> {
    const model = editor.getModel();
    // The same Monaco instance the c8y editor created the model with; another copy has its own language registry.
    const monaco = await loadMonacoEditor();
    await registerJsonataLanguage(monaco);
    if (!model || model.isDisposed()) {
      return;
    }
    monaco.editor.setModelLanguage(model, JSONATA_LANGUAGE_ID);

    const validate = () => {
      const value = model.getValue();
      this.isEmpty.set(value.length === 0);
      const error = findParseError((expression) => this.JSONATA(expression), value);
      monaco.editor.setModelMarkers(
        model,
        JSONATA_MARKER_OWNER,
        error ? [toMarker(error, value, monaco.MarkerSeverity.Error)] : []
      );
    };
    validate();

    const templateProvider = this.props['templateProvider'] as (() => unknown) | undefined;
    this.disposables.push(
      model.onDidChangeContent(() => this.zone.run(validate)),
      editor.onDidContentSizeChange((e) =>
        this.zone.run(() => this.height.set(Math.min(Math.max(e.contentHeight, MIN_HEIGHT), MAX_HEIGHT)))
      ),
      // The value is also set from outside (selecting a substitution): the content event covers that too.
      { dispose: () => monaco.editor.setModelMarkers(model, JSONATA_MARKER_OWNER, []) }
    );
    if (templateProvider) {
      this.disposables.push(
        registerPathCompletion(monaco, model, () => {
          try {
            return templateProvider();
          } catch {
            // Template in text mode and not valid JSON right now: no suggestions.
            return undefined;
          }
        })
      );
    }
  }

  ngOnDestroy(): void {
    this.disposables.splice(0).forEach((d) => d.dispose());
  }
}
