import {
  Component, ElementRef, Input, OnInit, OnDestroy, ViewChild,
  Output, EventEmitter, forwardRef, ChangeDetectionStrategy, ViewEncapsulation
} from '@angular/core';
import JSONEditor from "jsoneditor";
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';
import { JsonEditorOptions, JsonEditorMode, JsonEditorTreeNode, IError } from './jsoneditor-options';
import { COLOR_HIGHLIGHTED } from '../../../shared/helper';

@Component({
  // tslint:disable-next-line:component-selector
  selector: 'json-editor',
  template: `<div [id]="id" #jsonEditorContainer></div>`,
  providers: [
    {
      provide: NG_VALUE_ACCESSOR,
      useExisting: forwardRef(() => JsonEditorComponent),
      multi: true
    }
  ],
  preserveWhitespaces: false,
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrls: ['./jsoneditor.style.css'],
  encapsulation: ViewEncapsulation.None,
})

export class JsonEditorComponent implements ControlValueAccessor, OnInit, OnDestroy {
  private editor: any;
  public id = 'angjsoneditor' + Math.floor(Math.random() * 1000000);
  disabled = false;
  isFocused = false;
  selectionList: any = [];

  public optionsChanged = false;

  @ViewChild('jsonEditorContainer', { static: true }) jsonEditorContainer: ElementRef;

  private _data: Object = {};

  @Input() options: JsonEditorOptions = new JsonEditorOptions();
  @Input('data')
  set data(value: Object) {
    this._data = value;
    if (this.editor) {
      this.editor.destroy();
      this.ngOnInit();
    }
  }
  @Input() debug = false;

  @Output()
  change: EventEmitter<any> = new EventEmitter<any>();
  @Output()
  jsonChange: EventEmitter<any> = new EventEmitter<any>();
  @Output()
  onPathChanged: EventEmitter<string> = new EventEmitter<string>();

  constructor(private elementRef: ElementRef) {
  }


  ngOnInit() {
    let optionsBefore = this.options;
    if (!this.optionsChanged && this.editor) {
      optionsBefore = this.editor.options;
    }

    if (!this.options.onChangeJSON && this.jsonChange) {
      this.options.onChangeJSON = this.onChangeJSON.bind(this);
    }

    if (!this.options.onChange && this.change) {
      this.options.onChange = this.onChange.bind(this);
    }

    if (!this.options.onEvent) {
      this.options.onEvent = this.delegateSetSelection.bind(this);
    }

    const optionsCopy = Object.assign({}, optionsBefore);

    // expandAll is an option only supported by ang-jsoneditor and not by the the original jsoneditor.
    delete optionsCopy.expandAll;
    if (this.debug) {
      console.log(optionsCopy, this._data);
    }
    if (!this.jsonEditorContainer.nativeElement) {
      console.error(`Can't find the ElementRef reference for jsoneditor)`);
    }

    if (optionsCopy.mode === 'text' || optionsCopy.mode === 'code') {
      optionsCopy.onChangeJSON = null;
    }
    this.editor = new JSONEditor(this.jsonEditorContainer.nativeElement, optionsCopy, this._data);

    if (this.options.expandAll) {
      this.editor.expandAll();
    }


  }

  ngOnDestroy() {
    this.destroy();
  }


  /**
   * ngModel
   * ControlValueAccessor
   */

  // ControlValueAccessor implementation
  writeValue(value: any) {
    this.data = value;
  }

  // Implemented as part of ControlValueAccessor
  registerOnChange(fn) {
    this.onChangeModel = fn;
  }

  // Implemented as part of ControlValueAccessor.
  registerOnTouched(fn) {
    this.onTouched = fn;
  }
    
  // Implemented as part of ControlValueAccessor.
  setDisabledState(isDisabled: boolean): void {
    this.disabled = isDisabled;
  }

  // Implemented as part of ControlValueAccessor.
  private onTouched = () => {
  };


  // Implemented as part of ControlValueAccessor.
  private onChangeModel = (e) => {
  };

  public onChange(e) {
    if (this.editor) {
      try {
        const json = this.editor.get();
        this.onChangeModel(json);
        this.change.emit(json);
      } catch (e) {
        if (this.debug) {
          console.log(e);
        }
      }
    }
  }

  public onChangeJSON(e) {
    if (this.editor) {
      try {
        this.jsonChange.emit(this.editor.get());
      } catch (e) {
        if (this.debug) {
          console.log(e);
        }
      }
    }
  }


  /**
   * JSON EDITOR FUNCTIONS
   */

  public collapseAll() {
    this.editor.collapseAll();
  }

  public expandAll() {
    this.editor.expandAll();
  }

  public focus() {
    this.editor.focus();
  }

  public get(): JSON {
    return this.editor.get();
  }

  public getMode(): JsonEditorMode {
    return this.editor.getMode() as JsonEditorMode;
  }

  public getName(): string {
    return this.editor.getName();
  }

  public getText(): string {
    return this.editor.getText();
  }

  public set(json: JSON) {
    this.editor.set(json);
  }

  public setMode(mode: JsonEditorMode) {
    this.editor.setMode(mode);
  }

  public setName(name: string) {
    this.editor.setName(name);
  }

  public setSelection(start, end) {
    this.editor.setSelection(start, end);
  }

  public getSelection(): any {
    return this.editor.getSelection();
  }

  public getValidateSchema(): any {
    return this.editor.validateSchema;
  }

  public setSchema(schema: any, schemaRefs: any) {
    this.editor.setSchema(schema, schemaRefs);
  }

  public search(query: string) {
    this.editor.search(query);
  }

  public setOptions(newOptions: JsonEditorOptions) {
    if (this.editor) {
      this.editor.destroy();
    }
    this.optionsChanged = true;
    this.options = newOptions;
    this.ngOnInit();
  }

  public update(json: JSON) {
    this.editor.update(json);
  }

  public destroy() {
    this.editor?.destroy();
  }

  public getEditor() {
    return this.editor;
  }

  public isValidJson() {
    try {
      JSON.parse(this.getText());
      return true;
    } catch (e) {
      return false;
    }
  }

  private delegateSetSelection(node: any, event: any) {
    if (event.type == "click") {
      // determine the json editor where the click happened
      let target = '';
      var eventPath = event.path || (event.composedPath && event.composedPath());
      eventPath.forEach(element => {
        // if (element.localName == "json-editor") {
        if (element.localName == "div" && element.className == 'jsonColumnLarge') {
          target = element.id;
        }
      });

      var path = "";
      node.path.forEach(n => {
        if (typeof n === 'number') {
          path = path.substring(0, path.length - 1);
          path += '[' + n + ']';
        } else {
          path += n;
        }
        path += ".";
      });
      path = path.replace(/\.$/g, '')

      for (let item of this.selectionList) {
        //console.log("Reset item:", item);
        item.setAttribute('style', null);
      }

      this.selectionList = this.elementRef.nativeElement.querySelectorAll('.jsoneditor-selected');
      for (let item of this.selectionList) {
        item.setAttribute('style', `background: ${COLOR_HIGHLIGHTED};`);
      }

      if (path.startsWith("[")) {
        path = "$" + path;
      }
      this.setSelectionToPath(path)
    }
  }

  public setSelectionToPath(path: string) {
    console.log("Set selection to path:", path);
    const ns = path.split(".");
    if (ns[0].startsWith("$")) {
      let rx = /\[(-?\d*)\]/
      let result = ns[0].match(rx)
      if (result && result.length >= 2) {
        ns[0] = result[1]
      }
      console.log("Changed level 0:", ns[0])
    }
    const selection = { path: ns };
    try {
      this.editor.setSelection(selection, selection)
    } catch (error) {
      console.warn("Set selection to path not possible:", ns, error);
    }
    this.onPathChanged.emit(path);
  }
}

export { JsonEditorOptions, JsonEditorMode, JsonEditorTreeNode, IError };