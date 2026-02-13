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
import { ChangeDetectorRef, Component, inject, Input, OnInit } from '@angular/core';
import { BottomDrawerRef, CoreModule, ModalLabels } from '@c8y/ngx-components';
import { FormlyFieldConfig, FormlyModule } from '@ngx-formly/core';
import { FormGroup, FormsModule } from '@angular/forms';
import {
  ConnectorConfiguration,
  ConnectorProperty,
  ConnectorPropertyType,
  ConnectorSpecification,
  ConnectorType,
  Feature,
  nextIdAndPad,
  SharedService
} from '../..';
import { FormatStringPipe } from '../../misc/format-string.pipe';

import { SharedModule } from '../../shared.module';
import { Action } from '../types';

interface PropertyEntry {
  key: string;
  property: ConnectorProperty;
}

@Component({
  selector: 'd11r-edit-connector-drawer',
  templateUrl: 'connector-configuration-drawer.component.html',
  styleUrls: ['./connector-configuration-drawer.component.css'],
  standalone: true,
  imports: [
    CoreModule,
    FormsModule,
    FormlyModule,
    SharedModule
]
})
export class ConnectorConfigurationDrawerComponent implements OnInit {
  @Input() action!: Action;
  @Input() configuration!: ConnectorConfiguration;
  @Input() specifications!: ConnectorSpecification[];
  @Input() configurationsCount!: number;
  @Input() allowedConnectors: ConnectorType[] = [];

  brokerFormFields: FormlyFieldConfig[] = [];
  brokerForm = new FormGroup({});
  dynamicFormFields: FormlyFieldConfig[] = [];
  dynamicForm = new FormGroup({});
  labels: ModalLabels = { ok: 'Save', cancel: 'Cancel' };
  description?: string;
  readOnly = false;
  feature!: Feature;
  mode: 'Add' | 'Update' | 'View' = 'Add';

  private _save!: (value: ConnectorConfiguration) => void;
  private _cancel!: (reason?: string) => void;

  result: Promise<ConnectorConfiguration> = new Promise<ConnectorConfiguration>((resolve, reject) => {
    this._save = resolve;
    this._cancel = reject;
  });

  private readonly propertyTypeToFormConfig = new Map<
    ConnectorPropertyType,
    (entry: PropertyEntry) => FormlyFieldConfig
  >([
    [ConnectorPropertyType.NUMERIC_PROPERTY, this.createNumericField.bind(this)],
    [ConnectorPropertyType.STRING_PROPERTY, this.createStringField.bind(this)],
    [ConnectorPropertyType.SENSITIVE_STRING_PROPERTY, this.createSensitiveStringField.bind(this)],
    [ConnectorPropertyType.BOOLEAN_PROPERTY, this.createBooleanField.bind(this)],
    [ConnectorPropertyType.OPTION_PROPERTY, this.createOptionField.bind(this)],
    [ConnectorPropertyType.STRING_LARGE_PROPERTY, this.createLargeStringField.bind(this)],
    [ConnectorPropertyType.MAP_PROPERTY, this.createMapField.bind(this)]
  ]);

  private readonly bottomDrawerRef = inject(BottomDrawerRef);
  private readonly sharedService = inject(SharedService);
  private readonly formatStringPipe = inject(FormatStringPipe);
  private readonly cdr = inject(ChangeDetectorRef);

  async ngOnInit() {
    this.feature = await this.sharedService.getFeatures();
    this.mode = this.action === 'create' ? 'Add' : this.action === 'update' ? 'Update' : 'View';
    this.setConnectorDescription();
    this.initializeBrokerFormFields();
    this.readOnly = this.configuration.enabled || this.action === 'view';

    if (this.action !== 'create') {
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
        options: this.specifications
          .filter(sp => sp.connectorType !== ConnectorType.CUMULOCITY_MQTT_SERVICE)
          .map(sp => {
            const directions = sp.supportedDirections?.map(d => d.charAt(0).toUpperCase() + d.slice(1).toLowerCase()).join(', ') || '';
            const directionLabel = directions ? ` ${directions}` : '';
            const singletonSuffix = !this.allowedConnectors.includes(sp.connectorType) ? ' - Only one instance per tenant allowed' : '';
            return {
              label: `${sp.name} - ${directionLabel}${singletonSuffix}`,
              value: sp.connectorType,
              disabled: !this.allowedConnectors.includes(sp.connectorType) // Disable if not allowed
            };
          }),
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

  private createHideExpression(property: ConnectorProperty) {
    return (model) => {
      if (property?.condition?.anyOf) {
        const convertedAnyOf = this.convertBooleanStrings(property.condition.anyOf);
        //console.log("Evaluating:", property, convertedAnyOf, property.condition.key, model.properties[property.condition.key], !convertedAnyOf.includes(model.properties[property.condition.key]));
        return !convertedAnyOf.includes(model.properties[property.condition.key]);
      }
      return false;
    };
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
        hideExpression: this.createHideExpression(entry.property)
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
    const options = (entry.property as any).options;
    return this.createBaseFormField(entry, 'select', {
      options: options ? Object.values(options).map((key: string) => ({
        label: key,
        value: key
      })) : []
    });
  }

  private createLargeStringField(entry: PropertyEntry): FormlyFieldConfig {
    return this.createBaseFormField(entry, 'd11r-textarea', {
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
        hideExpression: this.createHideExpression(entry.property),
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

    if (this.action === 'create') {
      this.setDefaultConfiguration(connectorType);
    }

    this.createDynamicFormFields(dynamicFields);
    this.cdr.detectChanges();
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
          type: 'd11r-textarea',
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
    const formattedName = connectorType === ConnectorType.WEB_HOOK_INTERNAL
      ? 'Cumulocity API'
      : this.formatStringPipe.transform(connectorType);
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
      } else {
        console.warn(`Unsupported property type: ${entry.property.type} for field: ${entry.key}`);
      }
    });
  }

  private getSortedFields(dynamicFields: ConnectorSpecification): PropertyEntry[] {
    const entries: PropertyEntry[] = [];

    Object.entries(dynamicFields.properties).forEach(([key, property]) => {
      // Set default values for create action
      if ('defaultValue' in property && this.action === 'create') {
        this.configuration.properties[key] = property.defaultValue;
      }

      // Only include visible properties
      if (!property.hidden) {
        entries.push({ key, property });
      }
    });

    // Sort by order, handling missing or invalid order values
    return entries.sort((a, b) => {
      const orderA = a.property.order ?? Number.MAX_SAFE_INTEGER;
      const orderB = b.property.order ?? Number.MAX_SAFE_INTEGER;
      return orderA - orderB;
    });
  }

  onCancel() {
    this._cancel("User canceled");
    this.bottomDrawerRef.close();
  }

  onSave(): void {
    this._save(this.configuration);
    this.bottomDrawerRef.close();
  }

  onValidate(): void {
    this.dynamicForm.updateValueAndValidity();
    this.dynamicForm.reset();
  }
}