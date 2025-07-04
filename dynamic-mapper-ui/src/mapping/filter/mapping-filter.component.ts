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
  AfterViewInit,
  Component,
  Input,
  OnDestroy,
  OnInit,
  ViewChild,
  ViewEncapsulation
} from '@angular/core';
import { FormlyFieldConfig } from '@ngx-formly/core';
import { FormGroup } from '@angular/forms';
import { C8yStepper, ModalLabels } from '@c8y/ngx-components';
import { BehaviorSubject, Subject } from 'rxjs';
import { JsonEditorComponent, Mapping } from '../../shared';
import { MappingService } from '../core/mapping.service';
import { getTypeOf } from '../shared/util';

@Component({
  selector: 'd11r-mapping-filter',
  templateUrl: './mapping-filter.component.html',
  styleUrls: ['../shared/mapping.style.css'],
  encapsulation: ViewEncapsulation.None,
  standalone: false
})
export class MappingFilterComponent implements OnInit, OnDestroy, AfterViewInit {
  @Input() mapping: Mapping;
  @Input() sourceSystem: string;

  @ViewChild('editorSourceFilter', { static: false })
  editorSourceFilter: JsonEditorComponent;
  @ViewChild(C8yStepper, { static: true }) closeSubject: Subject<any>;

  labels: ModalLabels = { ok: 'Apply', cancel: 'Cancel' };
  editorOptionsSourceFilter: any ={
    mode: 'tree',
    removeModes: ['text', 'table'],
    mainMenuBar: true,
    navigationBar: false,
    statusBar: false,
    readOnly: true,
    name: 'message'
  };
  sourceTemplate: any;
  filterModel: any = {};
  filterFormly: FormGroup = new FormGroup({});
  filterFormlyFields: FormlyFieldConfig[];
  customMessage$: Subject<string> = new BehaviorSubject(undefined);
  valid: boolean = false;

  constructor(
    public mappingService: MappingService,
  ) {
  }
  async ngAfterViewInit(): Promise<void> {
    await this.editorSourceFilter?.setSelectionToPath(
      this.mapping.filterMapping
    );
  }

  async ngOnInit(): Promise<void> {
    this.closeSubject = new Subject();
    this.sourceTemplate = JSON.parse(this.mapping.sourceTemplate);
    this.filterFormlyFields = [
      {
        fieldGroup: [
          {
            key: 'pathSource',
            type: 'input',
            wrappers: ['custom-form-field-wrapper'],
            templateOptions: {
              label: 'Filter Expression',
              placeholder: '$join([$substring(txt,5), id]) or $number(id)/10',
              description: `Use <a href="https://jsonata.org" target="_blank">JSONata</a>
              in your expressions:
              <ol>
                <li>to convert a UNIX timestamp to ISO date format use:
                  <code>$fromMillis($number(deviceTimestamp))</code>
                </li>
                <li>to join substring starting at position 5 of property <code>txt</code> with
                  device
                  identifier use: <code>$join([$substring(txt,5), "-", id])</code></li>
                <li>function chaining using <code>~</code> is not supported, instead use function
                  notation. The expression <code>Account.Product.(Price * Quantity) ~> $sum()</code>
                  becomes <code>$sum(Account.Product.(Price * Quantity))</code></li>
              </ol>`,
              required: true,
              customMessage: this.customMessage$
            },
            hooks: {
              onInit: (field: FormlyFieldConfig) => {
                this.filterFormly.get('pathSource').setValue(this.mapping.filterMapping);
                this.updateSourceExpressionResult();
                field.formControl.valueChanges.subscribe(() => {
                  this.updateSourceExpressionResult();
                });
              }
            },
          },
        ]
      }
    ];

    // console.log('Mapping in filter:', this.mapping, this.editorSourceFilter);
  }

  onDismiss() {
    this.closeSubject.next(undefined);
    this.closeSubject.complete();
  }

  onClose() {
    this.closeSubject.next(this.filterFormly.get('pathSource').value);
  }

  onSelectedPathSourceChanged(path: string) {
    this.filterFormly.get('pathSource').setValue(path);
    this.filterModel.pathSource = path;
  }

  async updateSourceExpressionResult() {
    try {
      this.filterModel.sourceExpression = {
        msgTxt: '',
        severity: 'text-info'
      };
      this.filterFormly.get('pathSource').setErrors(null);

      const r: JSON = await this.mappingService.evaluateExpression(
        this.sourceTemplate,
        this.filterFormly.get('pathSource').value
      );
      this.filterModel.sourceExpression = {
        resultType: getTypeOf(r),
        result: JSON.stringify(r, null, 4)
      };
      if (this.filterModel.sourceExpression.result != 'true') throw Error('The filter expression must return true');

      this.valid = true;

    } catch (error) {
      this.filterFormly
        .get('pathSource')
        .setErrors({
          validationExpression: { message: error.message },
        });
      this.filterFormly.get('pathSource').markAsTouched();
      this.customMessage$.next(undefined);
      this.valid = false;
    }
    this.filterModel = { ...this.filterModel };
  }

  ngOnDestroy() {
    this.closeSubject.complete();
  }
}
