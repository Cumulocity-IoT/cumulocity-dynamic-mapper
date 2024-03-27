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

  ngOnInit(): void {
    this.brokerFormlyFields = [
      {
        className: 'col-lg-12',
        key: 'connectorType',
        type: 'select',
        id: 'connectorType',
        wrappers: ['c8y-form-field'],
        templateOptions: {
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
    this.dynamicFormlyFields = [];

    this.dynamicFormlyFields.push({
      fieldGroup: [
        {
          className: 'col-lg-12',
          key: 'name',
          id: 'name',
          type: 'input',
          wrappers: ['c8y-form-field'],
          templateOptions: {
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
        //     templateOptions: {
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
        // only display field when it is editable
        if (
          property.order < numberFields &&
          property.order >= 0 &&
          property.editable
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
                  templateOptions: {
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
                  templateOptions: {
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
                  templateOptions: {
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
                  templateOptions: {
                    label: entry.key,
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
