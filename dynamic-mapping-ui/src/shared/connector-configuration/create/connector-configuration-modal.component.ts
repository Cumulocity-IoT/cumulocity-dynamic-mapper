import {
  ChangeDetectorRef,
  Component,
  Input,
  NgZone,
  OnInit,
  Output
} from '@angular/core';
import { HumanizePipe, ModalLabels } from '@c8y/ngx-components';
import { Subject } from 'rxjs';
import { FormlyFieldConfig } from '@ngx-formly/core';
import { FormGroup } from '@angular/forms';
import {
  ConnectorConfiguration,
  ConnectorPropertyType,
  ConnectorSpecification,
  nextIdAndPad
} from '../..';
import { FieldTextareaCustom } from '../../../mapping/shared/formly/textarea.type.component';

@Component({
  selector: 'd11r-edit-connector-modal',
  templateUrl: 'connector-configuration-modal.component.html'
})
export class ConfigurationConfigurationModalComponent implements OnInit {
  @Input() add: boolean;
  @Input() readOnly: boolean;
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

  constructor(private cd: ChangeDetectorRef) {}

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

  private async createDynamicForm(connectorType: string): Promise<void> {
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
        }
      ]
    });
    if (this.add) {
      const n = HumanizePipe.humanize(connectorType);
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
                    required: property.required
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
                    required: property.required
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
                    required: property.required
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
                    required: property.required
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
                  type: FieldTextareaCustom,
                  wrappers: ['c8y-form-field'],
                  props: {
                    label: entry.key,
                    readonly: property.readonly,
                    cols: 120,
                    rows: 4,
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
