import { Component, Input, OnInit, Output } from "@angular/core";
import { ModalLabels } from "@c8y/ngx-components";
import { Subject } from "rxjs";
import { FormlyFieldConfig } from "@ngx-formly/core";
import { FormGroup } from "@angular/forms";
import {
  ConnectorProperty,
  ConnectorPropertyConfiguration,
} from "../../shared/mapping.model";

@Component({
  selector: "my-modal",
  template: ` <c8y-modal
    title="Edit properties broker configuration"
    (onClose)="onSave($event)"
    (onDismiss)="onDismiss($event)"
    [labels]="labels"
    [headerClasses]="'modal-header dialog-header'"
  >
    <div class="card-block">
      <div [formGroup]="brokerFormly" *ngIf="add">
        <formly-form
          [form]="brokerFormly"
          [fields]="brokerFormlyFields"
          [model]="brokerConfigModel"
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
  </c8y-modal>`,
})
export class EditConfigurationComponent implements OnInit {
  @Output() closeSubject: Subject<any> = new Subject();
  @Input() add: boolean;
  @Input() configuration: any;
  @Input() specifications: ConnectorPropertyConfiguration[];
  brokerFormlyFields: FormlyFieldConfig[] = [];
  brokerFormly: FormGroup = new FormGroup({});
  dynamicFormlyFields: FormlyFieldConfig[] = [];
  dynamicFormly: FormGroup = new FormGroup({});
  labels: ModalLabels = { ok: "Save", cancel: "Dismiss" };

  ngOnInit(): void {
    this.brokerFormlyFields = [
          {
            className: "col-lg-12",
            key: "connectorId",
            type: "select",
            wrappers: ["c8y-form-field"],
            templateOptions: {
              label: "Connector Id",
              options: this.specifications.map((sp) => {
                return {
                  label: sp.connectorId,
                  value: sp.connectorId,
                };
              }),
              change: (field: FormlyFieldConfig, event?: any) => {
                this.createDynamicForm(
                  this.brokerFormly.get("connectorId").value
                );
              },
              required: true,
            },
          },
    ];
  }

  onDismiss(event) {
    console.log("Dismiss");
    this.closeSubject.next(undefined);
  }

  onSave(event) {
    console.log("Save");
    this.closeSubject.next(this.configuration);
  }

  private async createDynamicForm(connectorId: string): Promise<void> {
    const dynamicFields: ConnectorPropertyConfiguration =
      this.specifications.find((c) => c.connectorId == connectorId);

    this.dynamicFormlyFields.push({
      fieldGroup: [
        {
          className: "col-lg-12",
          key: "name",
          type: "input",
          wrappers: ["c8y-form-field"],
          templateOptions: {
            label: "Name",
            required: true,
          },
        },
      ],
    });
    if (dynamicFields) {
      for (const key in dynamicFields.properties) {
        const property = dynamicFields.properties[key];
        if (property.property == ConnectorProperty.NUMERIC_PROPERTY) {
          this.dynamicFormlyFields.push({
            // fieldGroupClassName: "row",
            fieldGroup: [
              {
                className: "col-lg-12",
                key: key,
                type: "input",
                wrappers: ["c8y-form-field"],
                templateOptions: {
                  type: "number",
                  label: key,
                  required: property.required,
                },
              },
            ],
          });
        } else if (property.property == ConnectorProperty.STRING_PROPERTY) {
          this.dynamicFormlyFields.push({
            // fieldGroupClassName: "row",
            fieldGroup: [
              {
                className: "col-lg-12",
                key: key,
                type: "input",
                wrappers: ["c8y-form-field"],
                templateOptions: {
                  label: key,
                  required: property.required,
                },
              },
            ],
          });
        } else if (
          property.property == ConnectorProperty.SENSITIVE_STRING_PROPERTY
        ) {
          this.dynamicFormlyFields.push({
            // fieldGroupClassName: "row",
            fieldGroup: [
              {
                className: "col-lg-12",
                key: key,
                type: "input",
                wrappers: ["c8y-form-field"],
                templateOptions: {
                  type: "password",
                  label: key,
                  required: property.required,
                },
              },
            ],
          });
        } else if (property.property == ConnectorProperty.BOOLEAN_PROPERTY) {
          this.dynamicFormlyFields.push({
            //fieldGroupClassName: "row",
            fieldGroup: [
              {
                className: "col-lg-12",
                key: key,
                type: "switch",
                wrappers: ["c8y-form-field"],
                templateOptions: {
                  label: key,
                  required: property.required,
                },
              },
            ],
          });
        }
      }
      this.dynamicFormlyFields = [...this.dynamicFormlyFields];
    }
  }
}
