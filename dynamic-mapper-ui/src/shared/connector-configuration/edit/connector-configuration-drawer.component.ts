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
import { ChangeDetectorRef, Component, inject, Injector, Input, OnInit } from '@angular/core';
import { BottomDrawerRef, CoreModule, ModalLabels } from '@c8y/ngx-components';
import { FormlyFieldConfig, FormlyModule } from '@ngx-formly/core';
import { FormGroup, FormsModule } from '@angular/forms';
import {
  CONNECTOR_PROPERTY_GROUP_GENERAL,
  CONNECTOR_PROPERTY_GROUP_ORDER,
  isPropertyActive,
  ConnectionTestResult,
  ConnectorConfiguration,
  ConnectorProperty,
  ConnectorPropertyType,
  ConnectorSpecification,
  ConnectorType,
  Direction,
  Feature,
  nextIdAndPad,
  SharedService
} from '../..';
import { FormatStringPipe } from '../../misc/format-string.pipe';

import { SharedModule } from '../../shared.module';
import { Action } from '../types';
import { ConnectorConfigurationService } from '../../service/connector-configuration.service';

interface PropertyEntry {
  key: string;
  property: ConnectorProperty;
}

@Component({
  selector: 'd11r-edit-connector-drawer',
  host: { class: 'flex-grow d-col fit-h' },
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
  directionFilter: Direction | 'ALL' = 'ALL';
  readonly Direction = Direction;

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
    [ConnectorPropertyType.SENSITIVE_STRING_LARGE_PROPERTY, this.createSensitiveLargeStringField.bind(this)],
    [ConnectorPropertyType.MAP_PROPERTY, this.createMapField.bind(this)]
  ]);

  private readonly bottomDrawerRef = inject(BottomDrawerRef);
  private readonly sharedService = inject(SharedService);
  private readonly formatStringPipe = inject(FormatStringPipe);
  private readonly cdr = inject(ChangeDetectorRef);
  // resolved lazily (only when Test is clicked) so the drawer itself needs no extra providers
  private readonly injector = inject(Injector);

  testing = false;
  testResult?: ConnectionTestResult;

  get supportsTest(): boolean {
    return !!this.specifications?.find(sp => sp.connectorType === this.configuration?.connectorType)
      ?.supportsConnectionTest;
  }

  async onTest(): Promise<void> {
    this.testing = true;
    this.testResult = undefined;
    try {
      this.testResult = await this.injector.get(ConnectorConfigurationService).testConfiguration(this.configuration);
    } finally {
      this.testing = false;
      this.cdr.markForCheck();
    }
  }

  async ngOnInit() {
    this.feature = await this.sharedService.getFeatures();
    this.mode = this.action === 'create' ? 'Add' : this.action === 'update' ? 'Update' : 'View';
    this.setConnectorDescription();
    this.initializeBrokerFormFields();
    this.readOnly = this.configuration.enabled || this.action === 'view';

    // 'update'/'view' always have a connectorType; 'create' only does when duplicating an
    // existing connector (see ConnectorGridComponent.onConfigurationCopy) — a brand-new "Add"
    // has none yet and waits for the user to pick one via the select's own change handler.
    if (this.action !== 'create' || this.configuration.connectorType) {
      this.createDynamicForm(this.configuration.connectorType);
    }
  }

  private initializeBrokerFormFields(): void {
    const filteredSpecs = this.specifications
      .filter(sp => sp.connectorType !== ConnectorType.CUMULOCITY_MQTT_SERVICE)
      .filter(sp => this.directionFilter === 'ALL' || sp.supportedDirections?.includes(this.directionFilter as Direction));
    this.brokerFormFields = [{
      className: 'col-lg-12',
      key: 'connectorType',
      type: 'select',
      id: 'connectorType',
      wrappers: ['c8y-form-field'],
      props: {
        label: 'Connector type',
        options: filteredSpecs
          .map(sp => {
            const singletonSuffix = !this.allowedConnectors.includes(sp.connectorType) ? ' - Only one instance per tenant allowed' : '';
            return {
              label: `${sp.name}${singletonSuffix}`,
              value: sp.connectorType,
              disabled: !this.allowedConnectors.includes(sp.connectorType)
            };
          })
          .sort((a, b) => a.label.localeCompare(b.label)),
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
    }
  }

  /** Properties of the specification the form is currently built for; the conditions refer to each other. */
  private specificationProperties: { [name: string]: ConnectorProperty } = {};

  private createHideExpression(entry: PropertyEntry) {
    return (model) => !isPropertyActive(this.specificationProperties, entry.key, model?.properties);
  }

  private labelFor(entry: PropertyEntry): string {
    return entry.property.label || this.formatStringPipe.transform(entry.key);
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
          label: this.labelFor(entry),
          required: entry.property.required,
          disabled: entry.property.readonly || this.readOnly,
          description: entry.property.description || undefined,
          ...additionalProps
        },
        hideExpression: this.createHideExpression(entry)
      }]
    };
  }

  private createNumericField(entry: PropertyEntry): FormlyFieldConfig {
    return this.createBaseFormField(entry, 'input', { type: 'number' });
  }

  private createStringField(entry: PropertyEntry): FormlyFieldConfig {
    return this.createBaseFormField(entry, 'input');
  }

  private createSensitiveStringField(entry: PropertyEntry): FormlyFieldConfig {
    return this.createBaseFormField(entry, 'input', { type: 'password', sensitive: true });
  }

  private createBooleanField(entry: PropertyEntry): FormlyFieldConfig {
    return this.createBaseFormField(entry, 'switch', { hideLabel: true });
  }

  private createOptionField(entry: PropertyEntry): FormlyFieldConfig {
    const options = entry.property.options;
    return this.createBaseFormField(entry, 'select', {
      options: options ? Object.entries(options).map(([key, label]: [string, string]) => ({
        label,
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

  private createSensitiveLargeStringField(entry: PropertyEntry): FormlyFieldConfig {
    return this.createBaseFormField(entry, 'd11r-textarea', {
      cols: 120,
      rows: 6,
      sensitive: true
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
          label: this.labelFor(entry),
          required: entry.property.required,
          disabled: entry.property.readonly || this.readOnly,
          description: entry.property.description || undefined,
        },
        hideExpression: this.createHideExpression(entry),
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
      type: 'template',
      template: `<div class="legend form-block">${this.escapeHtml(CONNECTOR_PROPERTY_GROUP_GENERAL)}</div>`
    }, {
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
          props: {
            label: 'Description',
            placeholder: 'choose connector ...',
            disabled: this.readOnly,
          },
        }
      ]
    }];
  }

  private setDefaultConfiguration(connectorType: ConnectorType): void {
    // A duplicated connector (see ConnectorGridComponent.onConfigurationCopy) already has a
    // name ("<original>_copy") by the time this runs — don't clobber it with a generated one.
    if (!this.configuration.name) {
      const formattedName = connectorType === ConnectorType.WEB_HOOK_INTERNAL
        ? 'Cumulocity API'
        : this.formatStringPipe.transform(connectorType);
      this.configuration.name = `${formattedName} - ${nextIdAndPad(this.configurationsCount, 2)}`;
    }
    this.configuration.enabled = false;
  }

  private createDynamicFormFields(dynamicFields: ConnectorSpecification): void {
    this.specificationProperties = dynamicFields.properties;
    const sortedFields = this.getSortedFields(dynamicFields);

    let currentGroup: string | undefined;
    sortedFields.forEach(entry => {
      if (!entry) return;

      const formConfigFn = this.propertyTypeToFormConfig.get(entry.property.type);
      if (formConfigFn) {
        const group = entry.property.group;
        if (group && group !== currentGroup) {
          this.dynamicFormFields.push(
            this.createGroupLegend(group, sortedFields.filter(e => e?.property.group === group))
          );
        }
        currentGroup = group;
        this.dynamicFormFields.push(formConfigFn(entry));
      } else {
        console.warn(`Unsupported property type: ${entry.property.type} for field: ${entry.key}`);
      }
    });
  }

  /**
   * Legend above the properties of a group, same look as the section legends in the mapping's General Settings.
   * It is hidden together with its properties, so a group whose fields are all conditional (e.g. TLS) leaves no
   * orphaned heading.
   */
  private createGroupLegend(group: string, members: PropertyEntry[]): FormlyFieldConfig {
    const memberHidden = members.map(member => this.createHideExpression(member));
    return {
      type: 'template',
      template: `<div class="legend form-block">${this.escapeHtml(group)}</div>`,
      hideExpression: (model) => memberHidden.every(isHidden => isHidden(model))
    };
  }

  private escapeHtml(value: string): string {
    return value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  private groupRank(group: string | undefined): number {
    if (!group) {
      return Number.MAX_SAFE_INTEGER;
    }
    const index = CONNECTOR_PROPERTY_GROUP_ORDER.indexOf(group);
    return index === -1 ? CONNECTOR_PROPERTY_GROUP_ORDER.length : index;
  }

  private getSortedFields(dynamicFields: ConnectorSpecification): PropertyEntry[] {
    const entries: PropertyEntry[] = [];

    Object.entries(dynamicFields.properties).forEach(([key, property]) => {
      // Set default values for a genuinely blank create — but not when duplicating an existing
      // connector (see ConnectorGridComponent.onConfigurationCopy), whose cloned `properties`
      // already carry the real values that must be preserved, not overwritten with defaults.
      if (property.defaultValue != null && this.action === 'create' && this.configuration.properties[key] === undefined) {
        this.configuration.properties[key] = property.defaultValue;
      }

      // Only include visible properties
      if (!property.hidden) {
        entries.push({ key, property });
      }
    });

    // Sort by group (fixed group order), then by order inside the group; missing or invalid values go last.
    // Groups outside the known list follow alphabetically.
    return entries.sort((a, b) => {
      const groupDiff = this.groupRank(a.property.group) - this.groupRank(b.property.group);
      if (groupDiff !== 0) {
        return groupDiff;
      }
      if (a.property.group !== b.property.group) {
        // Two groups outside the known list: keep each group together
        return (a.property.group ?? '').localeCompare(b.property.group ?? '');
      }
      const orderA = a.property.order ?? Number.MAX_SAFE_INTEGER;
      const orderB = b.property.order ?? Number.MAX_SAFE_INTEGER;
      return orderA - orderB;
    });
  }

  onDirectionFilterChange(): void {
    this.brokerForm.reset();
    this.dynamicFormFields = [];
    this.dynamicForm = new FormGroup({});
    this.brokerFormFields = [];
    this.cdr.detectChanges();
    this.initializeBrokerFormFields();
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