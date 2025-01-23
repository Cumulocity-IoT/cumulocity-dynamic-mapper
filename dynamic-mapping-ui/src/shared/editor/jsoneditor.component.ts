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
  stringifyJSONPath,
  Content,
  MenuItem,
  createAjvValidator,
  parseJSONPath,
  JSONEditorSelection,
  isKeySelection,
  isJSONContent,
  JSONContent,
  isMultiSelection,
  createMultiSelection,
  TextContent,
  isValueSelection,
  ContextMenuItem
} from 'vanilla-jsoneditor';

import type { JSONPath } from 'immutable-json-patch'
import { IDENTITY } from '../mapping/mapping.model';

@Component({
  selector: 'd11r-mapping-json-editor2',
  template: '<div [class]="class" [id]="id" #jsonEditorContainer></div>',
  preserveWhitespaces: false,
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrls: ['./jsoneditor.style.css'],
  encapsulation: ViewEncapsulation.None
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
  contentChanged: EventEmitter<Content> = new EventEmitter<Content>();
  @Output()
  pathChanged: EventEmitter<string> = new EventEmitter<string>();
  @Output()
  initialized: EventEmitter<string> = new EventEmitter<string>();

  @ViewChild('jsonEditorContainer', { static: true })
  jsonEditorContainer: ElementRef;

  initRan: boolean = true;
  identifier: string;

  constructor(
    private cdr: ChangeDetectorRef
  ) {

  }

  private editor: JsonEditor;
  id = `angjsoneditor${Math.floor(Math.random() * 1000000)}`;
  content: Content = {
    text: undefined,
    json: {
      content: 'no content'
    }
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
          this.content = updatedContent;
          this.contentChanged.emit(updatedContent);
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
    // if (path.join('.') == this.identifier || path.join('.') == `${IDENTITY}.externalId` || path.join('.') == '_IDENTITY_.c8ySourceId') result = 'id-node';
    if (path.join('.') == `${IDENTITY}.externalId` || path.join('.') == '_IDENTITY_.c8ySourceId') result = 'id-node';
    return result;
  }

  onRenderMenu(items: MenuItem[]): MenuItem[] | undefined {
    // console.log("MenuItems:", items);
    // remove buttons for table-mode, transform, sort
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
          // Remove the item with name 'NotUsed'
          items.splice(i, 1);
        }
      } else if (item.type === 'row' || item.type === ('column' as any)) {
        // Recursively remove items with name 'NotUsed' from the nested array
        this.removeItem(item['items'] as any, textToRemove);
        // Remove the entire parent item if it becomes empty after removal
        if (item['items'].length === 0) {
          items.splice(i, 1);
        }
      } else if (item.type === 'dropdown-button') {
        // Recursively remove items with name 'NotUsed' from the nested array
        this.removeItem(item['items'] as any, textToRemove);
        this.removeItem([item['main']] as any, textToRemove);
        // Remove the entire parent item if it becomes empty after removal
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
    // ('ContextMenü', items, context);
    this.removeItem(items, 'Transform');
    return items;
  }

  private onSelect(selection: JSONEditorSelection | undefined) {
    const c: any = selection;
    // ignore emitting change events when the path was set programmatically to avoid circles
    if (!c?.triggeredSelection) {
      if (isKeySelection(selection) || isValueSelection(selection)) {
        const st = stringifyJSONPath((selection as any).path);
        this.pathChanged.emit(st);
        // console.log('Selected path:', st);
      } else if (isMultiSelection(selection)) {
        const st = stringifyJSONPath((selection as any).anchorPath);
        this.pathChanged.emit(st);
        // console.log('Selected anchorPath:', st);
      }
    } else {
      // console.log('Ignoring selection as is was triggered:', selection);
    }
  }

  setSchema(schema: any) {
    const validator = createAjvValidator({ schema });
    this.editor?.updateProps({ validator: validator });
  }

  async setSelectionToPath(pathString: string) {
    const containsSpecialChars = (str: string): boolean => {
      const regex = /[\$\(\)&]/;
      return regex.test(str);
    }
    if (pathString && !containsSpecialChars(pathString)) {
      const path = parseJSONPath(pathString);
      // console.log('Set selection to path:', pathString, path);
      const selection: any = createMultiSelection(path, path);
      // const selection: any = createKeySelection(path, false);
      // marker to ignore emitting change events when the path was set programmatically
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

  updateOptions(opts) {
    this.editor?.updateProps(opts)
  }
}
