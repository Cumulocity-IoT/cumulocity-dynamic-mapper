/*
 * Copyright (c) 2022 Software AG, Darmstadt, Germany and/or Software AG USA Inc., Reston, VA, USA,
 * and/or its subsidiaries and/or its affiliates and/or their licensors.
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
  ViewEncapsulation
} from '@angular/core';
import {
  JSONEditor,
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

@Component({
  selector: 'd11r-mapping-json-editor2',
  template: '<div [class]="class" [id]="id" #jsonEditorContainer></div>',
  preserveWhitespaces: false,
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrls: ['./jsoneditor2.style.css'],
  encapsulation: ViewEncapsulation.None
})
export class JsonEditor2Component implements OnInit, OnDestroy {
  @Input() options;
  @Input()
  set data(value: unknown) {
    if (value) {
      this.content['json'] = value;
    }

    if (this.editor) {
      this.editor.destroy();
      this.ngOnInit();
    }
  }
  @Input()
  schemaUpdate: EventEmitter<string>;
  @Input()
  class: string;

  @Output()
  changeContent: EventEmitter<any> = new EventEmitter<any>();
  @Output()
  pathChanged: EventEmitter<string> = new EventEmitter<string>();
  @Output()
  initialized: EventEmitter<string> = new EventEmitter<string>();

  @ViewChild('jsonEditorContainer', { static: true })
  jsonEditorContainer: ElementRef;

  constructor(private elementRef: ElementRef) {}

  private editor: JSONEditor;
  id = `angjsoneditor${Math.floor(Math.random() * 1000000)}`;
  content: Content = {
    text: undefined,
    json: {
      content: 'no content'
    }
  };
  ngOnInit() {
    if (!this.jsonEditorContainer.nativeElement) {
      console.error("Can't find the ElementRef reference for jsoneditor)");
    }
    delete (this.content as any).text;
    this.editor = new JSONEditor({
      target: this.jsonEditorContainer.nativeElement,
      props: {
        ...this.options,
        content: this.content,
        onChange: (
          updatedContent,
          previousContent,
          { contentErrors, patchResult }
        ) => {
          // content is an object { json: JSONData } | { text: string }
          console.log('onChange', {
            updatedContent,
            previousContent,
            contentErrors,
            patchResult
          });
          this.content = updatedContent;
          this.changeContent.emit(updatedContent);
        },
        onSelect: this.onSelect.bind(this),
        onRenderMenu(items: MenuItem[]): MenuItem[] | undefined {
          // console.log("MenuItems:", items);
          // remove buttons for table-mode, transform, sort
          items.splice(
            items.findIndex((i) => i['text'] === 'table'),
            1
          );
          items.splice(
            items.findIndex((i) => i['className'] === 'jse-transform'),
            1
          );
          return items;
        },
        onRenderContextMenu: this.onRenderContextMenu.bind(this)
      }
    });

    this.class = `jsoneditor2 ${this.class}`;
    this.schemaUpdate?.subscribe((schema) => {
      this.setSchema(schema);
    });
    this.initialized.emit('Ready');
  }

  ngOnDestroy() {
    this.editor?.destroy();
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
    console.log('ContextMenü', items, context);
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
        console.log('Selected path:', st);
      } else if (isMultiSelection(selection)) {
        const st = stringifyJSONPath((selection as any).anchorPath);
        this.pathChanged.emit(st);
        console.log('Selected anchorPath:', st);
      }
    } else {
      console.log('Ignoring selection as is was triggered:', selection);
    }
  }

  setSchema(schema: any) {
    const validator = createAjvValidator({ schema });
    this.editor?.updateProps({ validator: validator });
  }

  setSelectionToPath(pathString: string) {
    const path = parseJSONPath(pathString);
    console.log('Set selection to path:', pathString, path);
    const selection: any = createMultiSelection(path, path);
    // marker to ignore emitting change events when the path was set programmatically
    selection.triggeredSelection = true;

    try {
      this.editor.select(selection);
    } catch (error) {
      console.warn('Set selection to path not possible:', pathString, error);
    }
    this.pathChanged.emit(pathString);
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
}
