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
import { JsonEditor2Component, Mapping, whatIsIt } from '../../shared';
import { MappingService } from '../core/mapping.service';

@Component({
  selector: 'd11r-mapping-filter',
  templateUrl: './mapping-filter.component.html',
  encapsulation: ViewEncapsulation.None
})
export class MappingFilterComponent implements OnInit, OnDestroy, AfterViewInit {
  @Input() mapping: Mapping;

  @ViewChild('editorSourceFilter', { static: false })
  editorSourceFilter: JsonEditor2Component;
  @ViewChild(C8yStepper, { static: true }) closeSubject: Subject<any>;

  labels: ModalLabels = { ok: 'Apply', cancel: 'Cancel' };
  editorOptionsSourceFilter: any;
  templateSource: any;
  substitutionModel: any = {};
  substitutionFormly: FormGroup = new FormGroup({});
  substitutionFormlyFields: FormlyFieldConfig[];
  pathSource$: Subject<string> = new BehaviorSubject<string>('');
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
    this.templateSource = JSON.parse(this.mapping.source);
    this.editorOptionsSourceFilter = {
      mode: 'tree',
      mainMenuBar: true,
      navigationBar: false,
      statusBar: false,
      readOnly: true,
      name: 'message'
    };

    this.substitutionFormlyFields = [
      {
        fieldGroup: [
          {
            className: 'text-monospace',
            key: 'pathSource',
            type: 'input-custom',
            wrappers: ['custom-form-field'],
            templateOptions: {
              label: 'Filer Expression',
              class: 'input-sm disabled-animate-background',
              customWrapperClass: 'm-b-24',
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
              required: true
            },
            hooks: {
              onInit: (field: FormlyFieldConfig) => {
                field.formControl.valueChanges.subscribe(() => {
                  this.updateSourceExpressionResult();
                });
              }
            }
          },
        ]
      },
      {
        fieldGroupClassName: 'row',
        fieldGroup: [
          {
            className: 'reduced-top col-lg-offset-1 not-p-b-24',
            type: 'message-field',
            expressionProperties: {
              'templateOptions.content': (model) =>
                model.sourceExpression?.msgTxt,
              'templateOptions.textClass': (model) =>
                model.sourceExpression?.severity,
              'templateOptions.enabled': () => true
            }
          }
        ]
      }
    ];
    console.log('Mapping in filter:', this.mapping, this.editorSourceFilter);

    // console.log('Subject:', this.closeSubject, this.labels);
  }


  onDismiss() {
    this.closeSubject.next(undefined);
    this.closeSubject.complete();
  }

  onClose() {
    this.closeSubject.next(this.substitutionFormly.get('pathSource').value);
  }

  onSelectedPathSourceChanged(path: string) {
    this.substitutionFormly.get('pathSource').setValue(path);
    this.substitutionModel.pathSource = path;
    this.pathSource$.next(path);
  }

  async updateSourceExpressionResult() {
    try {
      this.substitutionModel.sourceExpression = {
        msgTxt: '',
        severity: 'text-info'
      };
      this.substitutionFormly.get('pathSource').setErrors(null);

      const r: JSON = await this.mappingService.evaluateExpression(
        this.templateSource,
        this.substitutionFormly.get('pathSource').value
      );
      this.substitutionModel.sourceExpression = {
        resultType: whatIsIt(r),
        result: JSON.stringify(r, null, 4)
      };
      this.valid = true;

    } catch (error) {
      // console.log('Error evaluating source expression: ', error);
      this.substitutionModel.sourceExpression = {
        msgTxt: error.message,
        severity: 'text-danger'
      };
      this.substitutionFormly
        .get('pathSource')
        .setErrors({ error: error.message });
        this.valid = false;
    }
    this.substitutionModel = { ...this.substitutionModel };
  }


  ngOnDestroy() {
    this.closeSubject.complete();
  }
}
