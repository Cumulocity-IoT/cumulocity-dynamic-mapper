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
  ViewEncapsulation,
} from "@angular/core";
import {
  JSONEditor,
  renderValue,
  Content,
  JSONPath,
  MenuItem,
  createKeySelection,
  createAjvValidator,
  parseJSONPath,
  createInsideSelection,
} from "vanilla-jsoneditor";

@Component({
  selector: "mapping-json-editor2‚",
  template: `<div class="jsoneditor2" [id]="id" #jsonEditorContainer></div>`,
  preserveWhitespaces: false,
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrls: ["./jsoneditor2.style.css"],
  encapsulation: ViewEncapsulation.None,
})
export class JsonEditor2Component implements OnInit, OnDestroy {
  @ViewChild("jsonEditorContainer", { static: true })
  jsonEditorContainer: ElementRef;

  @Input() options
  // @Input("editorProps")
  // set editorProps(value: Object) {
  //   this.editorProps = value
  // }

  @Input("data")
  set data(value: Object) {
    this.content["json"] = value;

    if (this.editor) {
      this.editor.destroy();
      this.ngOnInit();
    }
  }

  @Output()
  change: EventEmitter<any> = new EventEmitter<any>();
  @Output()
  onPathChanged: EventEmitter<string> = new EventEmitter<string>();

  constructor(private elementRef: ElementRef) {}

  private editor: JSONEditor;
  public id = "angjsoneditor" + Math.floor(Math.random() * 1000000);
  content: Content = {
    text: undefined,
  };
  ngOnInit() {
    if (!this.jsonEditorContainer.nativeElement) {
      console.error(`Can't find the ElementRef reference for jsoneditor)`);
    }
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
          console.log("onChange", {
            updatedContent,
            previousContent,
            contentErrors,
            patchResult,
          });
          this.content = updatedContent;
          this.change.emit(updatedContent);
        },
        onRenderValue: this.onRenderValue.bind(this),
        // onSelect(selection: JSONEditorSelection | undefined){
        // TODO extract path from selection
        //   this.onPathChanged.emit(selection);
        // },
        onRenderMenu(
          items: MenuItem[],
          context: { mode: "tree" | "text" | "table"; modal: boolean }
        ): MenuItem[] | undefined {
          console.log("MenuItems:", items);
          // remove buttons for table-mode, transform, sort
          items.splice(
            items.findIndex((i) => i["text"] === "table"),
            1
          );
          items.splice(
            items.findIndex((i) => i["className"] === "jse-sort"),
            1
          );
          items.splice(
            items.findIndex((i) => i["className"] === "jse-transform"),
            1
          );
          return items;
        },
      },
    });
  }

  ngOnDestroy() {
    this.editor?.destroy();
  }

  private onRenderValue(props) {
    // if (props.selection) {
    //   const pathString = stringifyJSONPath(props.selection.anchorPath);
    //   console.log("Selected node:", props.selection, pathString);
    //   this.onPathChanged.emit(pathString);
    // }
    // props = {
    //   ...props,
    //   onSelect: (selection: JSONSelection) => {
    //     console.log("Was selected:", selection)
    //   }
    // }
    return renderValue(props);
  }

  public updateSelection(path: JSONPath, orgPath: string) {
    const selection = createKeySelection(path, false);
    this.editor.updateSelection(selection);
  }

  public setSchema(schema: any) {
    const validator = createAjvValidator({ schema });
    this.editor.updateProps({ validator: validator });
  }

  // public onSelect(selection: Selection) {
  //   console.log("Was selected:", selection);
  // }

  public setSelectionToPath(pathString: string) {
    const path = parseJSONPath(pathString);
    console.log("Set selection to path:", pathString, path);
    //const selection = createKeySelection(path, false);
    const selection = createInsideSelection(path);
    try {
      this.editor.updateSelection(selection);
    } catch (error) {
      console.warn("Set selection to path not possible:", pathString, error);
    }
    this.onPathChanged.emit(pathString);
  }
}
