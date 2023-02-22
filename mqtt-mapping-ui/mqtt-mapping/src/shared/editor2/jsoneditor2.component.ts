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
  Component, ElementRef, Input, OnInit, OnDestroy, ViewChild,
  Output, EventEmitter, ChangeDetectionStrategy, ViewEncapsulation
} from '@angular/core';
import { JSONEditor, JSONSelection, renderValue, Content, JSONPath, MenuItem, SelectionType,JSONPatchDocument, DocumentState, createKeySelection } from 'vanilla-jsoneditor'

import { isEqual } from 'lodash-es'
import { COLOR_HIGHLIGHTED } from '../util';

@Component({
  selector: 'mapping-json-editor2‚',
  template: `<div class="jsoneditor2" [id]="id" #jsonEditorContainer></div>`,
  preserveWhitespaces: false,
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrls: ['./jsoneditor2.style.css'],
  encapsulation: ViewEncapsulation.None,
})

export class JsonEditor2Component implements OnInit, OnDestroy {
  private editor: JSONEditor;
  public id = 'angjsoneditor' + Math.floor(Math.random() * 1000000);
  debug: boolean = true;
  public optionsChanged = false;

  @ViewChild('jsonEditorContainer', { static: true }) jsonEditorContainer: ElementRef;

  content: Content = {
    text: undefined
  };

  @Input() props;

  @Input('data')
  set data(value: Object) {
    this.content['json'] = value;

    if (this.editor) {
      this.editor.destroy();
      this.ngOnInit();
    }
  }

  @Output()
  change: EventEmitter<any> = new EventEmitter<any>();
  @Output()
  onPathChanged: EventEmitter<string> = new EventEmitter<string>();

  constructor(private elementRef: ElementRef) { }

  ngOnInit() {

    if (!this.jsonEditorContainer.nativeElement) {
      console.error(`Can't find the ElementRef reference for jsoneditor)`);
    }

    this.editor = new JSONEditor(
      {
        target: this.jsonEditorContainer.nativeElement,
        props: {
          content: this.content,
          onChange: (updatedContent, previousContent, { contentErrors, patchResult }) => {
            // content is an object { json: JSONData } | { text: string }
            console.log('onChange', { updatedContent, previousContent, contentErrors, patchResult })
            this.content = updatedContent
            this.change.emit(updatedContent);
          },
          onRenderValue(props) {
            // use the enum renderer, and fallback on the default renderer
            console.log("Props before:", props);
            props = {
              ...props,
              onSelect: (selection: JSONSelection) => {
                console.log("Was selected:", selection)
              }
            }
            const path: JSONPath = ['menu','popup' ]; 
            const selection : JSONSelection = 
            {
              type: SelectionType.key,
              anchorPath: path,
              focusPath: path,
              pointersMap: { 'menu/popup': true},
              edit: false
          };
            props['selection'] = selection;
            props['path'] = path;

            console.log("Props after:", props);

            return renderValue(props)
          },
          onRenderMenu(items: MenuItem[], context: { mode: 'tree' | 'text' | 'table', modal: boolean }) : MenuItem[] | undefined {
            console.log("MenuItems:", items);
            items.splice(items.findIndex(i => i['text'] === "table"),1);
            return items;
          }
        }
      });


  }

  ngOnDestroy() {
    this.destroy();
  }

  public scrollTo(path:JSONPath) {
    const selection = createKeySelection(path, true);
    this.editor.scrollTo(path);
  }

  public getValidateSchema(): any {
    return this.editor.validateSchema;
  }

  public setSchema(schema: any, schemaRefs: any) {
    this.editor.setSchema(schema, schemaRefs);
  }

  public destroy() {
    this.editor?.destroy();
  }

  public getEditor() {
    return this.editor;
  }

  public onSelect(selection: Selection) {
    console.log("Was selected:", selection);
  }

  public setSelectionToPath(path: string) {
    console.log("Set selection to path:", path);
    let levels = this.jsonPath2EditorPath(path);
    const selection = { path: levels };
    try {
      this.editor.setSelection(selection, selection)
    } catch (error) {
      console.warn("Set selection to path not possible:", levels, error);
    }
    this.onPathChanged.emit(path);
  }

  private editorPath2jsonPath(levels: string[]): string {
    let path = "";
    levels.forEach(n => {
      if (typeof n === 'number') {
        path = path.substring(0, path.length - 1);
        path += '[' + n + ']';
      } else {
        path += n;
      }
      path += ".";
    });
    path = path.replace(/\.$/g, '')
    if (path.startsWith("[")) {
      path = "$" + path;
    }

    return path;
  }

  private jsonPath2EditorPath(path: string): string[] {
    const ns = path.split(".");
    // if payload is an json array then we have to transform the path
    if (ns[0].startsWith("$")) {
      const patternIndex = /\[(-?\d*)\]/;
      let result = ns[0].match(patternIndex);
      if (result && result.length >= 2) {
        ns[0] = result[1];
      }
      console.log("Changed level 0:", ns[0]);
    }
    let levels = [];
    //const patternArray = /.*(?=\[*)/
    const patternArray = /^[^\[]+/;
    const patternIndex = /(?<=\[)(-?\d*)(?=\])/;
    ns.forEach(l => {
      let ar = l.match(patternArray);
      if (ar?.length > 0) {
        levels.push(ar[0]);
      }
      let ind = l.match(patternIndex);
      if (ind?.length > 0) {
        levels.push(ind[0]);
      }
    });
    return levels;
  }

}
