import { Component, Input, OnInit, Output } from '@angular/core';
import { HumanizePipe, ModalLabels } from '@c8y/ngx-components';
import { Subject } from 'rxjs';
import { FormlyFieldConfig } from '@ngx-formly/core';
import { FormGroup } from '@angular/forms';
import {
  ConnectorConfiguration,
  ConnectorSpecification,
  ConnectorPropertyType
} from '../shared/configuration.model';
import { uuidCustom } from '../../shared';
import { FieldTextareaCustom } from '../../mapping/shared/formly/textarea.type.component';

@Component({
  selector: 'd11r-edit-connector-modal',
  template: ` <c8y-modal
    title="Edit connector configuration"
    (onClose)="onSave()"
    (onDismiss)="onDismiss()"
    [labels]="labels"
    [headerClasses]="'modal-header dialog-header'"
  >
    <div class="card-block">
      <div [formGroup]="brokerFormly" *ngIf="add">
        <formly-form
          [form]="brokerFormly"
          [fields]="brokerFormlyFields"
          s
        ></formly-form>
      </div>
      <c8y-form-group style="margin-left: 12px; margin-right: 12px;">
        <label style="margin-left: 4px;">
          <span>
            {{ 'Description' | translate }}
          </span>
        </label>
        <textarea
          rows="3"
          class="form-control"
          placeholder="choose connector ..."
          >{{ description }}</textarea
        >
      </c8y-form-group>
      <div [formGroup]="dynamicFormly">
        <formly-form
          [form]="dynamicFormly"
          [fields]="dynamicFormlyFields"
          [model]="configuration"
        ></formly-form>
      </div>
    </div>
  </c8y-modal>`
})
export class EditConfigurationComponent implements OnInit {
  @Input() add: boolean;
  @Input() configuration: Partial<ConnectorConfiguration>;
  @Input() specifications: ConnectorSpecification[];
  @Output() closeSubject: Subject<any> = new Subject();
  brokerFormlyFields: FormlyFieldConfig[] = [];
  brokerFormly: FormGroup = new FormGroup({});
  dynamicFormlyFields: FormlyFieldConfig[] = [];
  dynamicFormly: FormGroup = new FormGroup({});
  labels: ModalLabels = { ok: 'Save', cancel: 'Dismiss' };
  description: string;

  ngOnInit(): void {
    this.setConnectorDescription();

    this.brokerFormlyFields = [
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
              label: sp.connectorType,
              value: sp.connectorType
            };
          }),
          change: () => {
            this.createDynamicForm(
              this.brokerFormly.get('connectorType').value
            );
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
    console.log('Dismiss');
    this.closeSubject.next(undefined);
  }

  onSave() {
    console.log('Save');
    this.closeSubject.next(this.configuration);
  }

  private async createDynamicForm(connectorType: string): Promise<void> {
    const dynamicFields: ConnectorSpecification = this.specifications.find(
      (c) => c.connectorType == connectorType
    );

    this.configuration.connectorType = connectorType;
    this.setConnectorDescription();
    this.dynamicFormlyFields = [];

    this.dynamicFormlyFields.push({
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
        // {
        //     className: 'col-lg-12',
        //     key: 'supportsWildcardInTopic',
        //     id: 'supportsWildcardInTopic',
        //     type: 'switch',
        //     wrappers: ['c8y-form-field'],
        //     defaultValue: true,
        //     props: {
        //       label: HumanizePipe.humanize('supportsWildcardInTopic'),
        //       description: 'If this option is checked, then topics can contains wildcards characters: +, #',
        //     }
        //   }
      ]
    });
    if (this.add) {
      const n = HumanizePipe.humanize(connectorType);
      this.configuration.name = `${n} - ${uuidCustom()}`;
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
            this.dynamicFormlyFields.push({
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
            this.dynamicFormlyFields.push({
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
            this.dynamicFormlyFields.push({
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
            this.dynamicFormlyFields.push({
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
            this.dynamicFormlyFields.push({
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
            this.dynamicFormlyFields.push({
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
      this.dynamicFormlyFields = [...this.dynamicFormlyFields];
    }
  }
}
