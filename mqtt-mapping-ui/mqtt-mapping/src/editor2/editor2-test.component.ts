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
  AfterViewInit,
  Component,
  OnInit,
  ViewChild,
  ViewEncapsulation,
} from "@angular/core";
import { API, Direction } from "../shared/mapping.model";
import { getSchema } from "../shared/util";
import { JsonEditor2Component } from "../shared/editor2/jsoneditor2.component";

@Component({
  selector: "json-editor2",
  templateUrl: "editor2-test.component.html",
  styleUrls: ["./editor2-test.style.css"],

  encapsulation: ViewEncapsulation.None,
})
export class Editor2TestComponent implements OnInit, AfterViewInit {
  constructor() {}
  @ViewChild("editor2Test", { static: false }) editor2: JsonEditor2Component;
  templateSource: any;
  options: any;
  public path: string;

  ngOnInit(): void {
    this.templateSource = {
      source: {id: "1234"},
      type: "c8y_Type",
      time: "2023-07-13T10:10:10",
      menu: {
        id: "file",
        value: "File",
        popup: {
          menuitem: [
            { value: "New", onclick: "CreateNewDoc()" },
            { value: "Open", onclick: "OpenDoc()" },
            { value: "Close", onclick: "CloseDoc()" },
          ],
        },
      },
    };
    this.options = { mainMenuBar: true, navigationBar: false, statusBar: false };
  }

  ngAfterViewInit(): void {
    this.editor2.setSchema(
      getSchema(API.MEASUREMENT.name, Direction.INBOUND, true)
    );
  }

  onSelectedSourcePathChanged(event) {
    console.log("Something happend in the editor: ", event);
  }

  onScrollToPathChanged(event) {
    const pathOrg = event.target.value;
    console.log("Editor2 set:", event.target.value, this.editor2);
    this.editor2.setSelectionToPath(pathOrg);
  }
}
