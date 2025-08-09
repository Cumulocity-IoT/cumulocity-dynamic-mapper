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
import { ChangeDetectorRef, Component, Input, OnInit, Output } from '@angular/core';
import { ModalLabels } from '@c8y/ngx-components';
import { Subject } from 'rxjs';
import { FormlyFieldConfig } from '@ngx-formly/core';
import { FormGroup } from '@angular/forms';
import {
  ConnectorConfiguration,
  ConnectorProperty,
  ConnectorPropertyType,
  ConnectorSpecification,
  ConnectorType,
  Feature,
  FormatStringPipe,
  nextIdAndPad,
  SharedService
} from '../..';

interface PropertyEntry {
  key: string;
  property: ConnectorProperty;
}

@Component({
  selector: 'd11r-edit-connector-modal',
  templateUrl: 'connector-configuration-modal.component.html',
  standalone: false
})
export class ConnectorConfigurationModalComponent implements OnInit {
  @Input() add: boolean;
  @Input() configuration: Partial<ConnectorConfiguration>;
  @Input() specifications: ConnectorSpecification[];
  @Input() configurationsCount: number;
  @Output() closeSubject = new Subject<any>();

  brokerFormFields: FormlyFieldConfig[] = [];
  brokerForm = new FormGroup({});
  dynamicFormFields: FormlyFieldConfig[] = [];
  dynamicForm = new FormGroup({});
  labels: ModalLabels = { ok: 'Save', cancel: 'Cancel' };
  description: string;
  readOnly: boolean;

  private readonly propertyTypeToFormConfig = new Map([
    [ConnectorPropertyType.NUMERIC_PROPERTY, this.createNumericField.bind(this)],
    [ConnectorPropertyType.STRING_PROPERTY, this.createStringField.bind(this)],
    [ConnectorPropertyType.SENSITIVE_STRING_PROPERTY, this.createSensitiveStringField.bind(this)],
    [ConnectorPropertyType.BOOLEAN_PROPERTY, this.createBooleanField.bind(this)],
    [ConnectorPropertyType.OPTION_PROPERTY, this.createOptionField.bind(this)],
    [ConnectorPropertyType.STRING_LARGE_PROPERTY, this.createLargeStringField.bind(this)],
    [ConnectorPropertyType.MAP_PROPERTY, this.createMapField.bind(this)]
  ]);
  feature: Feature;

  constructor(
    private cd: ChangeDetectorRef,
    private formatStringPipe: FormatStringPipe,
    private sharedService: SharedService
  ) { }

  async ngOnInit() {
    this.feature = await this.sharedService.getFeatures();
    this.setConnectorDescription();
    this.initializeBrokerFormFields();
    this.readOnly = this.configuration.enabled;

    if (!this.add) {
      this.createDynamicForm(this.configuration.connectorType);
    }
  }

  private initializeBrokerFormFields(): void {
    this.brokerFormFields = [{
      className: 'col-lg-12',
      key: 'connectorType',
      type: 'select',
      id: 'connectorType',
      wrappers: ['c8y-form-field'],
      props: {
        label: 'Connector type',
        options: this.specifications.map(sp => ({
          label: sp.name,
          value: sp.connectorType
        })),
        change: () => this.createDynamicForm(this.brokerForm.get('connectorType').value),
        required: true,
        disabled: this.readOnly
      }
    }];
  }

  private setConnectorDescription(): void {
    const spec = this.specifications.find(sp => sp.connectorType === this.configuration.connectorType);
    if (spec) {
      this.description = spec.description;
      this.configuration['description'] = spec.description;
    }
  }

  private createBaseFormField(entry: PropertyEntry, type: string, additionalProps = {}): FormlyFieldConfig {
    return {
      fieldGroup: [{
        className: 'col-lg-12',
        key: `properties.${entry.key}`,
        id: entry.key,
        type,
        wrappers: ['c8y-form-field'],
        props: {
          label: entry.key,
          required: entry.property.required,
          disabled: entry.property.readonly || this.readOnly,
          description: entry.property.description || undefined,
          ...additionalProps
        },
        hideExpression: (model) => {
          if (entry.property?.condition && entry.property?.condition.anyOf) {
            const convertedAnyOf = this.convertBooleanStrings(entry.property.condition.anyOf);
            //console.log("Evaluating:", entry.key, entry.property?.condition.key, entry.property?.condition.anyOf, convertedAnyOf, convertedAnyOf, model.properties[entry.property?.condition.key], model);
            //console.log("Evaluating:", entry.key, convertedAnyOf, entry.property?.condition.key, model.properties[entry.property?.condition.key], !convertedAnyOf.includes(model.properties[entry.property?.condition.key],));
            //console.log("Model:", entry.property.condition.anyOf, model, model.properties);
            //console.log("Model:", model.properties);
            return !convertedAnyOf.includes(model.properties[entry.property?.condition.key])
          } else { return false }
        }
      }]
    };
  }

  private convertBooleanStrings<T>(array: T[]): (T | boolean)[] {
    return array.map(item => {
      if (typeof item === 'string') {
        const lowerCase = item.toLowerCase();
        if (lowerCase === 'true') return true;
        if (lowerCase === 'false') return false;
      }
      return item;
    });
  }

  private createNumericField(entry: PropertyEntry): FormlyFieldConfig {
    return this.createBaseFormField(entry, 'input', { type: 'number' });
  }

  private createStringField(entry: PropertyEntry): FormlyFieldConfig {
    return this.createBaseFormField(entry, 'input');
  }

  private createSensitiveStringField(entry: PropertyEntry): FormlyFieldConfig {
    return this.createBaseFormField(entry, 'input', { type: 'password' });
  }

  private createBooleanField(entry: PropertyEntry): FormlyFieldConfig {
    return this.createBaseFormField(entry, 'switch');
  }

  private createOptionField(entry: PropertyEntry): FormlyFieldConfig {
    return this.createBaseFormField(entry, 'select', {
      options: Object.values(entry.property['options']).map(key => ({
        label: key,
        value: key
      }))
    });
  }

  private createLargeStringField(entry: PropertyEntry): FormlyFieldConfig {
    return this.createBaseFormField(entry, 'textarea-custom', {
      cols: 120,
      rows: 6
    });
  }

  private createMapField(entry: PropertyEntry): FormlyFieldConfig {
    return {
      fieldGroup: [{
        className: 'col-lg-12',
        key: `properties.${entry.key}`,
        id: entry.key,
        type: 'd11r-input-list',
        wrappers: ['c8y-form-field'],
        props: {
          label: entry.key,
          required: entry.property.required,
          disabled: entry.property.readonly || this.readOnly,
          description: entry.property.description || undefined,
        },
        hideExpression: (model) => {
          if (entry.property?.condition && entry.property?.condition.anyOf) {
            const convertedAnyOf = this.convertBooleanStrings(entry.property.condition.anyOf);
            return !convertedAnyOf.includes(model.properties[entry.property?.condition.key]);
          }
          return false;
        },
        // Initialize with empty object if no default value
        defaultValue: entry.property.defaultValue || {}
      }]
    };
  }

  private async createDynamicForm(connectorType: ConnectorType): Promise<void> {
    const dynamicFields = this.specifications.find(c => c.connectorType === connectorType);
    if (!dynamicFields) return;

    this.configuration.connectorType = connectorType;
    this.setConnectorDescription();
    this.initializeBasicFormFields();

    if (this.add) {
      this.setDefaultConfiguration(connectorType);
    }

    this.createDynamicFormFields(dynamicFields);
    this.cd.detectChanges();
  }

  private initializeBasicFormFields(): void {
    this.dynamicFormFields = [{
      fieldGroup: [
        {
          className: 'col-lg-12',
          key: 'name',
          id: 'name',
          type: 'input',
          wrappers: ['c8y-form-field'],
          props: {
            label: 'Name',
            required: true,
            disabled: this.readOnly,
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
            disabled: this.readOnly,
          },
        }
      ]
    }];
  }

  private setDefaultConfiguration(connectorType: ConnectorType): void {
    const formattedName = this.formatStringPipe.transform(connectorType);
    this.configuration.name = `${formattedName} - ${nextIdAndPad(this.configurationsCount, 2)}`;
    this.configuration.enabled = false;
  }

  private createDynamicFormFields(dynamicFields: ConnectorSpecification): void {
    const sortedFields = this.getSortedFields(dynamicFields);

    sortedFields.forEach(entry => {
      if (!entry) return;

      const formConfigFn = this.propertyTypeToFormConfig.get(entry.property.type);
      if (formConfigFn) {
        this.dynamicFormFields.push(formConfigFn(entry));
      }
    });
  }

  private getSortedFields(dynamicFields: ConnectorSpecification): PropertyEntry[] {
    const numberFields = Object.keys(dynamicFields.properties).length;
    const sortedFields = new Array(numberFields);

    Object.entries(dynamicFields.properties).forEach(([key, property]) => {
      if ('defaultValue' in property && this.add) {
        this.configuration.properties[key] = property.defaultValue;
      }

      if (property.order < numberFields && property.order >= 0 && !property.hidden) {
        if (!sortedFields[property.order]) {
          sortedFields[property.order] = { key, property };
        } else {
          sortedFields.push({ key, property });
        }
      }
    });

    return sortedFields;
  }

  onDismiss(): void {
    this.closeSubject.next(undefined);
  }

  onSave(): void {

    this.closeSubject.next(this.configuration);
  }

  onValidate(): void {
    this.dynamicForm.updateValueAndValidity();
    this.dynamicForm.reset();
  }
}