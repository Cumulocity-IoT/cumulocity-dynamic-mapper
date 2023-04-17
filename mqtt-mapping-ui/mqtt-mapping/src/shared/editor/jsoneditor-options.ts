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

export type JsonEditorMode = "tree" | "view" | "form" | "code" | "text";

export interface JsonEditorTreeNode {
  field: String;
  value: String;
  path: String[];
}

export interface IError {
  path: (string | number)[];
  message: string;
}

export class JsonEditorOptions {
  public ace: any;
  public ajv: Object;

  /**
   *   {function} onChange  Callback method, triggered
  on change of contents.
  Does not pass the contents itself.
  See also `onChangeJSON` and
  `onChangeText`.
   */
  public onChange: () => void;

  /**
*   // {function} onChangeJSON  Callback method, triggered
//     in modes on change of contents,
//     passing the changed contents
//     as JSON.
//     Only applicable for modes
//     'tree', 'view', and 'form'.
*/
  public onChangeJSON: () => void;

  public onNodeName: () => void;
  public onCreateMenu: () => void;
  public onColorPicker: () => void;

  /**
*   // {function} onChangeText  Callback method, triggered
//     in modes on change of contents,
//     passing the changed contents
//     as stringified JSON.
*/
  public onChangeText: (jsonstr: string) => void;

  /**
   *   {function} onSelectionChange Callback method,
  triggered on node selection change
  Only applicable for modes
  'tree', 'view', and 'form'
   */
  public onSelectionChange: () => void;

  /**
*     {function} onTextSelectionChange Callback method,
  triggered on text selection change
  Only applicable for modes
*/
  public onTextSelectionChange: () => void;

  /**
   *   // {function} onEvent Callback method, triggered
    // when an event occurs in
    // a JSON field or value.
    // Only applicable for
    // modes 'form', 'tree' and
    // 'view'
   */
  public onEvent: () => void;

  /**
* // *   {function} onFocus  Callback method, triggered
//  when the editor comes into focus,
//  passing an object {type, target},
//  Applicable for all modes
*/
  public onFocus: () => void;

  // *   {function} onBlur   Callback method, triggered
  //  when the editor goes out of focus,
  //  passing an object {type, target},
  //  Applicable for all modes
  public onBlur: () => void;

  /**
*  // *   {function} onClassName Callback method, triggered
// when a Node DOM is rendered. Function returns
// a css class name to be set on a node.
// Only applicable for
// modes 'form', 'tree' and
// 'view'
*/
  public onClassName: () => void;

  public onEditable: (
    node: JsonEditorTreeNode | {}
  ) => boolean | { field: boolean; value: boolean };

  /**
   *   {function} onError   Callback method, triggered
  when an error occurs
   */
  public onError: (error: any) => void;
  public onModeChange: (
    newMode: JsonEditorMode,
    oldMode: JsonEditorMode
  ) => void;
  public onValidate: (json: Object) => IError[];
  public onValidationError: (errors: object[]) => void;

  public enableSort: boolean;
  public enableTransform: boolean;
  public escapeUnicode: boolean;
  public expandAll: boolean;
  public sortObjectKeys: boolean;
  public history: boolean;
  public mode: JsonEditorMode;
  public modes: JsonEditorMode[];
  public name: String;
  public schema: Object;
  public search: boolean;
  public indentation: Number;
  public template: Object;
  public theme: Number;
  public language: String;
  public languages: Object;

  /**
   * Adds main menu bar - Contains format, sort, transform, search etc. functionality. True
   * by default. Applicable in all types of mode.
   */
  public mainMenuBar: boolean;

  /**
   * Adds navigation bar to the menu - the navigation bar visualize the current position on
   * the tree structure as well as allows breadcrumbs navigation.
   * True by default.
   * Only applicable when mode is 'tree', 'form' or 'view'.
   */
  public navigationBar: boolean;

  /**
   * Adds status bar to the bottom of the editor - the status bar shows the cursor position
   * and a count of the selected characters.
   * True by default.
   * Only applicable when mode is 'code' or 'text'.
   */
  public statusBar: boolean;

  constructor() {
    this.enableSort = true;
    this.enableTransform = true;
    this.escapeUnicode = false;
    this.expandAll = false;
    this.sortObjectKeys = false;
    this.history = true;
    this.mode = "tree";
    this.search = true;
    this.indentation = 2;
  }
}
