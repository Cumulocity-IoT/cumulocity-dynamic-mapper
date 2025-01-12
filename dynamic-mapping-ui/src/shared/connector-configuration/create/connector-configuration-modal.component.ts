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
  ChangeDetectorRef,
  Component,
  Input,
  OnInit,
  Output
} from '@angular/core';
import { ModalLabels } from '@c8y/ngx-components';
import { Subject } from 'rxjs';
import { FormlyFieldConfig } from '@ngx-formly/core';
import { FormGroup } from '@angular/forms';
import {
  ConnectorConfiguration,
  ConnectorPropertyType,
  ConnectorSpecification,
  ConnectorType,
  FormatStringPipe,
  nextIdAndPad
} from '../..';

@Component({
  selector: 'd11r-edit-connector-modal',
  templateUrl: 'connector-configuration-modal.component.html'
})
export class ConnectorConfigurationModalComponent implements OnInit {
  @Input() add: boolean;
  @Input() configuration: Partial<ConnectorConfiguration>;
  @Input() specifications: ConnectorSpecification[];
  @Input() configurationsCount: number;
  @Output() closeSubject: Subject<any> = new Subject();
  brokerFormFields: FormlyFieldConfig[] = [];
  brokerForm: FormGroup = new FormGroup({});
  dynamicFormFields: FormlyFieldConfig[] = [];
  dynamicForm: FormGroup = new FormGroup({});
  labels: ModalLabels = { ok: 'Save', cancel: 'Cancel' };
  description: string;

  constructor(private cd: ChangeDetectorRef,
    private formatStringPipe: FormatStringPipe
  ) { }

  ngOnInit(): void {
    this.setConnectorDescription();
    this.brokerFormFields = [
      {
        className: 'col-lg-12',
        key: 'connectorType',
        type: 'select',
        id: 'connectorType',
        wrappers: ['c8y-form-field'],
        props: {
          label: 'Connector type',
          options: this.specifications.map((sp) => {
            return {
              label: sp.name,
              value: sp.connectorType
            };
          }),
          change: () => {
            this.createDynamicForm(this.brokerForm.get('connectorType').value);
          },
          required: true
        }
      }
    ];
    if (!this.add) {
      this.createDynamicForm(this.configuration.connectorType);
    }
  }

  private setConnectorDescription() {
    const desc = this.specifications.find(
      (sp) => sp.connectorType == this.configuration.connectorType
    );
    if (desc) {
      this.description = desc.description;
      this.configuration['description'] = desc.description;
    }
  }

  onDismiss() {
    // console.log('Dismiss');
    this.closeSubject.next(undefined);
  }

  onSave() {
    // console.log('Save', this.dynamicForm.valid);
    this.closeSubject.next(this.configuration);
  }

  private async createDynamicForm(connectorType: ConnectorType): Promise<void> {
    const dynamicFields: ConnectorSpecification = this.specifications.find(
      (c) => c.connectorType == connectorType
    );

    this.configuration.connectorType = connectorType;
    this.setConnectorDescription();
    this.dynamicFormFields = [];

    this.dynamicFormFields.push({
      fieldGroup: [
        {
          className: 'col-lg-12',
          key: 'name',
          id: 'name',
          type: 'input',
          wrappers: ['c8y-form-field'],
          props: {
            label: 'Name',
            required: true
          }
        },
        {
          className: 'col-lg-12',
          type: 'textarea-custom',
          key: 'description',
          wrappers: ['c8y-form-field'],
          templateOptions: {
            label: 'Description',
            readonly: true,
            placeholder: 'choose connector ...',
          },
        }
      ]
    });

    if (this.add) {
      const n = this.formatStringPipe.transform(connectorType);
      this.configuration.name = `${n} - ${nextIdAndPad(this.configurationsCount, 2)}`;
      this.configuration.enabled = false;
    }
    if (dynamicFields) {
      const numberFields = Object.keys(dynamicFields.properties).length;
      const sortedFields = new Array(numberFields);
      for (const key in dynamicFields.properties) {
        const property = dynamicFields.properties[key];
        if (property.defaultValue && this.add) {
          this.configuration.properties[key] = property.defaultValue;
        }
        // only display field when it is visible
        if (
          property.order < numberFields &&
          property.order >= 0 &&
          !property.hidden
        ) {
          if (!sortedFields[property.order]) {
            sortedFields[property.order] = { key: key, property: property };
          } else {
            // append property to the end of the list
            sortedFields.push({ key: key, property: property });
          }
        }
      }
      for (let index = 0; index < sortedFields.length; index++) {
        const entry = sortedFields[index];
        // test if the property is a valid entry, this happens when the list of properties is not numbered consecutively
        if (entry) {
          const { property } = entry;
          if (property.type == ConnectorPropertyType.NUMERIC_PROPERTY) {
            this.dynamicFormFields.push({
              // fieldGroupClassName: "row",
              fieldGroup: [
                {
                  className: 'col-lg-12',
                  key: `properties.${entry.key}`,
                  id: `${entry.key}`,
                  type: 'input',
                  wrappers: ['c8y-form-field'],
                  props: {
                    type: 'number',
                    label: entry.key,
                    required: property.required,
                    readonly: property.readonly
                  }
                }
              ]
            });
          } else if (property.type == ConnectorPropertyType.STRING_PROPERTY) {
            this.dynamicFormFields.push({
              // fieldGroupClassName: "row",
              fieldGroup: [
                {
                  className: 'col-lg-12',
                  key: `properties.${entry.key}`,
                  id: `${entry.key}`,
                  type: 'input',
                  wrappers: ['c8y-form-field'],
                  props: {
                    label: entry.key,
                    required: property.required,
                    readonly: property.readonly
                  }
                }
              ]
            });
          } else if (
            property.type == ConnectorPropertyType.SENSITIVE_STRING_PROPERTY
          ) {
            this.dynamicFormFields.push({
              // fieldGroupClassName: "row",
              fieldGroup: [
                {
                  className: 'col-lg-12',
                  key: `properties.${entry.key}`,
                  id: `${entry.key}`,
                  type: 'input',
                  wrappers: ['c8y-form-field'],
                  props: {
                    type: 'password',
                    label: entry.key,
                    required: property.required,
                    readonly: property.readonly
                  }
                }
              ]
            });
          } else if (property.type == ConnectorPropertyType.BOOLEAN_PROPERTY) {
            this.dynamicFormFields.push({
              // fieldGroupClassName: "row",
              fieldGroup: [
                {
                  className: 'col-lg-12',
                  key: `properties.${entry.key}`,
                  id: `${entry.key}`,
                  type: 'switch',
                  wrappers: ['c8y-form-field'],
                  props: {
                    label: entry.key,
                    required: property.required,
                    readonly: property.readonly
                  }
                }
              ]
            });
          } else if (property.type == ConnectorPropertyType.OPTION_PROPERTY) {
            this.dynamicFormFields.push({
              // fieldGroupClassName: "row",
              fieldGroup: [
                {
                  className: 'col-lg-12',
                  key: `properties.${entry.key}`,
                  id: `${entry.key}`,
                  type: 'select',
                  wrappers: ['c8y-form-field'],
                  props: {
                    label: entry.key,
                    required: property.required,
                    readonly: property.readonly,
                    options: Object.values(property.options).map((key) => {
                      return { label: key, value: key };
                    })
                  }
                }
              ]
            });
          } else if (
            property.type == ConnectorPropertyType.STRING_LARGE_PROPERTY
          ) {
            this.dynamicFormFields.push({
              // fieldGroupClassName: "row",
              fieldGroup: [
                {
                  className: 'col-lg-12',
                  key: `properties.${entry.key}`,
                  id: `${entry.key}`,
                  type: 'textarea-custom',
                  wrappers: ['c8y-form-field'],
                  props: {
                    label: entry.key,
                    readonly: property.readonly,
                    cols: 120,
                    rows: 6,
                    required: property.required
                  }
                }
              ]
            });
          }
        }
      }
      this.dynamicFormFields = [...this.dynamicFormFields];
      this.cd.detectChanges();
    }
  }
}
