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
  ElementRef,
  Input,
  OnInit,
  OnDestroy,
  ViewChild,
  Output,
  EventEmitter,
  ChangeDetectionStrategy,
  ViewEncapsulation,
  AfterViewInit,
  ChangeDetectorRef,
  SimpleChanges
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
  JSONContent,
  isMultiSelection,
  createMultiSelection,
  TextContent,
  isValueSelection,
  ContextMenuItem,
  OnChangeStatus // Add this import
} from 'vanilla-jsoneditor';

import type { JSONPath } from 'immutable-json-patch'
import { parseJSONPathCustom, stringifyJSONPathCustom } from './utils';

export interface ContentChanges {
  previousContent: Content,
  updatedContent: Content,
}

@Component({
  selector: 'd11r-mapping-json-editor',
  template: '<div [class]="class" [id]="id" #jsonEditorContainer></div>',
  preserveWhitespaces: false,
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrls: ['./jsoneditor.style.css'],
  encapsulation: ViewEncapsulation.None,
  standalone: true
})
export class JsonEditorComponent implements OnInit, OnDestroy, AfterViewInit {
  @Input() options;
  @Input()
  set data(value: unknown) {
    if (value && Object.keys(value).length != 0 && this.initRan) {
      this.content = { json: value }
      this.editor?.update(this.content);
      this.cdr.detectChanges();
    }
  }
  @Input()
  updateEditor: EventEmitter<any>;
  @Input()
  class: string;

  @Output()
  contentChanged: EventEmitter<ContentChanges> = new EventEmitter<ContentChanges>();
  @Output()
  pathChanged: EventEmitter<string> = new EventEmitter<string>();
  @Output()
  initialized: EventEmitter<string> = new EventEmitter<string>();

  @ViewChild('jsonEditorContainer', { static: true })
  jsonEditorContainer: ElementRef;

  initRan: boolean = true;
  identifier: string;
  private isReverting: boolean = false; // Flag to prevent emission during revert

  constructor(
    private cdr: ChangeDetectorRef
  ) {

  }

  private editor: JsonEditor;
  id = `angjsoneditor${Math.floor(Math.random() * 1000000)}`;
  content: Content = {
    text: undefined,
    json: {}
  };

  ngOnInit() {
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes) {
      if (JSON.stringify(changes['options']?.currentValue) !== JSON.stringify(changes['options']?.previousValue)) {
        this.updateProps();
      }
    }
  }

  private updateProps() {
    this.editor?.updateProps(this.options);
    this.cdr.detectChanges();
  }

  ngAfterViewInit() {
    this.initializeEditor();
    this.initRan = true;
  }

  initializeEditor() {
    if (!this.jsonEditorContainer.nativeElement) {
      console.error("Can't find the ElementRef reference for jsoneditor)");
    }
    delete (this.content as any).text;
    this.editor = createJSONEditor({
      target: this.jsonEditorContainer.nativeElement,
      props: {
        ...this.options,
        content: this.content,
        onChange: (
          updatedContent,
          previousContent,
          { contentErrors, patchResult }
        ) => {
          // Skip emission if we're reverting
          if (this.isReverting) {
            this.isReverting = false;
            return;
          }

          this.content = updatedContent;
          this.contentChanged.emit({ previousContent, updatedContent });
        },
        onSelect: this.onSelect.bind(this),
        onRenderMenu: this.onRenderMenu.bind(this),
        onRenderContextMenu: this.onRenderContextMenu.bind(this),
        onClassName: this.onClassName.bind(this)
      }
    });

    this.class = `jsoneditor2 ${this.class}`;
    this.updateEditor?.subscribe((update) => {
      this.setSchema(update.schema);
      this.identifier = update.identifier;
    });
    this.initialized.emit('Ready');
  }

  ngOnDestroy() {
    this.editor?.destroy();
  }

  onClassName(path: JSONPath, value: any): string | undefined {
    let result = undefined;
    return result;
  }

  onRenderMenu(items: MenuItem[]): MenuItem[] | undefined {
    items.splice(
      items.findIndex((i) => i['className'] === 'jse-transform'),
      1
    );
    this.options.removeModes?.forEach(mode => items.splice(
      items.findIndex((i) => i['text'] === mode),
      1
    ))
    return items;
  }

  removeItem(items: ContextMenuItem[], textToRemove: string) {
    for (let i = items.length - 1; i >= 0; i--) {
      const item = items[i];
      if (item.type === 'button') {
        if (item.text === textToRemove) {
          items.splice(i, 1);
        }
      } else if (item.type === 'row' || item.type === ('column' as any)) {
        this.removeItem(item['items'] as any, textToRemove);
        if (item['items'].length === 0) {
          items.splice(i, 1);
        }
      } else if (item.type === 'dropdown-button') {
        this.removeItem(item['items'] as any, textToRemove);
        this.removeItem([item['main']] as any, textToRemove);
        if (item.items.length === 0) {
          items.splice(i, 1);
        }
      }
    }
  }

  private onRenderContextMenu(
    items: ContextMenuItem[],
    context: { mode: 'tree' | 'text' | 'table'; modal: boolean }
  ): ContextMenuItem[] | undefined {
    this.removeItem(items, 'Transform');
    return items;
  }

  private onSelect(selection: JSONEditorSelection | undefined) {
    const c: any = selection;
    if (!c?.triggeredSelection) {
      if (isKeySelection(selection) || isValueSelection(selection)) {
        const st = stringifyJSONPathCustom((selection as any).path);
        this.pathChanged.emit(st);
      } else if (isMultiSelection(selection)) {
        const st = stringifyJSONPathCustom((selection as any).anchorPath);
        this.pathChanged.emit(st);
      }
    }
  }

  setSchema(schema: any) {
    const validator = createAjvValidator({ schema });
    this.editor?.updateProps({ validator: validator });
  }

  async setSelectionToPath(pathString: string) {
    const containsSpecialChars = (str: string): boolean => {
      const regex = /[\$\(\)&\s\+\-\/\*\=]/;
      return regex.test(str);
    }
    if (pathString && !containsSpecialChars(pathString)) {
      const path = parseJSONPathCustom(pathString);
      const selection: any = createMultiSelection(path, path);
      selection.triggeredSelection = false;

      try {
        await this.editor.select(selection);
      } catch (error) {
        console.warn('Set selection to path not possible:', pathString, error);
      }
      this.pathChanged.emit(pathString);
    }
  }

  get(): JSON {
    const content: Content = this.editor.get();
    if (isJSONContent(content)) {
      const j: any = (this.editor.get() as JSONContent).json;
      return j;
    } else {
      const t: any = (this.editor.get() as TextContent).text;
      const j: JSON = JSON.parse(t);
      return j;
    }
  }

  set(json: any) {
    const value: JSONContent = {
      json: json
    };
    this.editor.set(value);
  }

  /**
   * Revert content without triggering onChange event and preserving expansion state
   */
  revertContent(json: any) {
    this.isReverting = true;
    const value: JSONContent = {
      json: json
    };
    // Use patch instead of set to maintain the expansion state
    try {
      this.editor.update(value);
    } catch (error) {
      // If update fails, fall back to set
      console.warn('Update failed, falling back to set:', error);
      this.editor.set(value);
    }
    this.content = value;
  }

  updateOptions(opts) {
    this.editor?.updateProps(opts)
  }
}