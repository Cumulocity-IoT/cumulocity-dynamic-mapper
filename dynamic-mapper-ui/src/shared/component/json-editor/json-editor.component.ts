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


import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  EventEmitter,
  Input,
  OnDestroy,
  Output,
  ViewChild,
  ViewEncapsulation,
  AfterViewInit
} from '@angular/core';
import {
  JsonEditor,
  createJSONEditor,
  Content,
  MenuItem,
  createAjvValidator,
  JSONEditorSelection,
  isKeySelection,
  isJSONContent,
  isMultiSelection,
  createMultiSelection,
  isValueSelection,
  ContextMenuItem,
  JSONSchema
} from 'vanilla-jsoneditor';

/** Editor props, plus `removeModes`: mode buttons to hide from the menu. */
export type JsonEditorInputOptions = Record<string, unknown> & { removeModes?: readonly string[] };

import type { JSONPath } from 'immutable-json-patch';
import { parseJSONPathCustom, stringifyJSONPathCustom } from './util';

export interface ContentChanges {
  previousContent: Content,
  updatedContent: Content,
}

/** Characters that mark a string as an expression (JSONata etc.) rather than a plain JSON path. */
const EXPRESSION_CHARS = /[$()&\s+\-/*=]/;

/** A key no JSON document contains; selecting it removes the highlight. */
const NO_SELECTION_KEY = '\u0000no-selection';

/** Returns `items` without the buttons labelled `text`, dropping groups that become empty. */
export function withoutButton(items: ContextMenuItem[], text: string): ContextMenuItem[] {
  return items.flatMap((item) => {
    if (item.type === 'button') {
      return item.text === text ? [] : [item];
    }
    const group = item as ContextMenuItem & { items?: ContextMenuItem[] };
    if (!Array.isArray(group.items)) {
      return [item];
    }
    const children = withoutButton(group.items, text);
    return children.length > 0 ? [{ ...group, items: children } as ContextMenuItem] : [];
  });
}

/**
 * Wrapper around vanilla-jsoneditor.
 *
 * Inputs: `data` (replaces the content; empty values are ignored), `options` (editor props plus
 * `removeModes`), `schema` (JSON schema for validation), `class`.
 * Outputs: `contentChanged` (user edits only; programmatic `data`/`set()` never emit, because the
 * library does not call `onChange` for them) and `pathChanged`.
 */
@Component({
  selector: 'd11r-mapping-json-editor',
  template: '<div [class]="class" #jsonEditorContainer></div>',
  preserveWhitespaces: false,
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrls: ['./json-editor.style.css'],
  encapsulation: ViewEncapsulation.None,
  standalone: true
})
export class JsonEditorComponent implements OnDestroy, AfterViewInit {
  /** Editor props, plus `removeModes`: mode buttons (e.g. 'text', 'table') to hide from the menu. */
  @Input()
  set options(value: JsonEditorInputOptions) {
    const changed = JSON.stringify(value) !== JSON.stringify(this._options);
    this._options = value;
    if (changed) {
      this.editor?.updateProps(this.libraryProps());
    }
  }
  get options(): JsonEditorInputOptions {
    return this._options;
  }

  @Input()
  set data(value: unknown) {
    if (value && Object.keys(value).length !== 0) {
      this.set(value);
    }
  }

  @Input()
  set schema(value: JSONSchema | null | undefined) {
    if (value) {
      this.setSchema(value);
    }
  }

  @Input()
  class: string;

  @Output()
  contentChanged: EventEmitter<ContentChanges> = new EventEmitter<ContentChanges>();
  @Output()
  pathChanged: EventEmitter<string> = new EventEmitter<string>();

  @ViewChild('jsonEditorContainer', { static: true })
  jsonEditorContainer: ElementRef<HTMLElement>;

  private _options: JsonEditorInputOptions;
  private editor: JsonEditor | undefined;
  /** Content/schema received before the editor exists; applied on creation. */
  private content: Content = { json: {} };
  private pendingSchema: JSONSchema | undefined;

  ngAfterViewInit() {
    this.editor = createJSONEditor({
      target: this.jsonEditorContainer.nativeElement,
      props: {
        ...this.libraryProps(),
        content: this.content,
        onChange: (updatedContent, previousContent) => {
          this.content = updatedContent;
          this.contentChanged.emit({ previousContent, updatedContent });
        },
        onSelect: (selection) => this.onSelect(selection),
        onRenderMenu: (items) => this.onRenderMenu(items),
        onRenderContextMenu: (items) => withoutButton(items, 'Transform'),
        onClassName: (path) => this.onClassName(path)
      }
    });

    if (this.pendingSchema) {
      this.setSchema(this.pendingSchema);
      this.pendingSchema = null;
    }
  }

  ngOnDestroy() {
    this.editor?.destroy();
  }

  /** Editor props without the wrapper-only `removeModes`. */
  private libraryProps() {
    const { removeModes, ...props } = this._options ?? {};
    return props;
  }

  private onClassName(path: JSONPath): string | undefined {
    if (path.length === 0 || typeof path[0] !== 'string' || !path[0].startsWith('_')) {
      return undefined;
    }
    // Color only the root _IDENTITY_/_TOPIC_LEVEL_/_CONTEXT_DATA_ node itself, not its nested
    // content. The nested-content class below exists purely to reset the CSS coloring that the
    // root's descendant selector would otherwise also apply to it (the JSON tree editor nests
    // every node's DOM inside its parent's, so a plain descendant selector can't tell the root
    // node's own key/value apart from its children's).
    return path.length === 1 ? 'jse-metadata-node' : 'jse-metadata-node-content';
  }

  private onRenderMenu(items: MenuItem[]): MenuItem[] {
    const removeModes: readonly string[] = this._options?.removeModes ?? [];
    return items.filter(
      (i) => i['className'] !== 'jse-transform' && !removeModes.includes(i['text'])
    );
  }

  private onSelect(selection: JSONEditorSelection | undefined) {
    const path = isKeySelection(selection) || isValueSelection(selection)
      ? selection.path
      : isMultiSelection(selection) ? selection.anchorPath : undefined;
    if (path && path[0] !== NO_SELECTION_KEY) {
      this.pathChanged.emit(stringifyJSONPathCustom(path));
    }
  }

  setSchema(schema: JSONSchema) {
    if (this.editor) {
      this.editor.updateProps({ validator: createAjvValidator({ schema }) });
    } else {
      this.pendingSchema = schema;
    }
  }

  /**
   * Selects the node at `pathString`. A path that is empty or a JSONata expression has no node,
   * so the previous selection is cleared instead of being left highlighted as if it still applied.
   */
  async setSelectionToPath(pathString: string) {
    if (!this.editor) return;
    if (!pathString || EXPRESSION_CHARS.test(pathString)) {
      this.clearSelection();
      return;
    }
    const path = parseJSONPathCustom(pathString);
    try {
      await this.editor.select(createMultiSelection(path, path));
    } catch (error) {
      console.warn('Set selection to path not possible:', pathString, error);
      this.clearSelection();
    }
    this.pathChanged.emit(pathString);
  }

  /**
   * Removes the node highlight. The library's `select(undefined)` and `select(null)` leave the
   * old highlight in place, so this selects a path that does not exist, which highlights nothing.
   * That select fires `onSelect`, which ignores the sentinel key.
   */
  clearSelection(): void {
    try {
      const path = [NO_SELECTION_KEY];
      this.editor?.select(createMultiSelection(path, path));
    } catch (error) {
      console.warn('Clear selection not possible:', error);
    }
  }

  /** Current content as JSON, parsing the text if the editor is in text mode. */
  get<T = unknown>(): T {
    const content = this.editor.get();
    return (isJSONContent(content) ? content.json : JSON.parse(content.text)) as T;
  }

  /** Replaces the content. Does not emit `contentChanged`. */
  set(json: unknown) {
    this.content = { json };
    this.editor?.set(this.content);
  }
}
