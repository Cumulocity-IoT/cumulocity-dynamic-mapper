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
  Component,
  EventEmitter,
  Input,
  OnDestroy,
  OnInit,
  Output,
  ViewEncapsulation
} from '@angular/core';
import { FormGroup } from '@angular/forms';
import { AlertService } from '@c8y/ngx-components';
import { FormlyFieldConfig } from '@ngx-formly/core';
import { BehaviorSubject } from 'rxjs';
import { BrokerConfigurationService } from '../../configuration';
import {
  API,
  Direction,
  Mapping,
  QOS,
  SAMPLE_TEMPLATES_C8Y,
  SnoopStatus,
  getExternalTemplate
} from '../../shared';
import { MappingService } from '../core/mapping.service';
import { EditorMode, StepperConfiguration } from '../step-main/stepper-model';
import { isDisabled } from '../shared/util';
import { ValidationError } from '../shared/mapping.model';
import { deriveTemplateTopicFromTopic } from '../shared/util';

@Component({
  selector: 'd11r-mapping-properties',
  templateUrl: 'mapping-properties.component.html',
  styleUrls: ['../shared/mapping.style.css'],
  encapsulation: ViewEncapsulation.None
})
export class MappingStepPropertiesComponent implements OnInit, OnDestroy {
  @Input() mapping: Mapping;
  @Input() stepperConfiguration: StepperConfiguration;
  @Input() propertyFormly: FormGroup;
  @Output() targetTemplateChanged = new EventEmitter<any>();

  ValidationError = ValidationError;
  Direction = Direction;
  EditorMode = EditorMode;
  isDisabled = isDisabled;

  propertyFormlyFields: FormlyFieldConfig[];
  selectedResult$: BehaviorSubject<number> = new BehaviorSubject<number>(0);
  sourceSystem: string;
  targetSystem: string;

  constructor(
    public mappingService: MappingService,
    public configurationService: BrokerConfigurationService,
    private alertService: AlertService
  ) {}

  ngOnInit() {
    // set value for backward compatiblility
    if (!this.mapping.direction) this.mapping.direction = Direction.INBOUND;
    this.targetSystem =
      this.mapping.direction == Direction.INBOUND ? 'Cumulocity' : 'Broker';
    this.sourceSystem =
      this.mapping.direction == Direction.OUTBOUND ? 'Cumulocity' : 'Broker';

    console.log(
      'Mapping to be updated:',
      this.mapping,
      this.stepperConfiguration
    );
    const numberSnooped = this.mapping.snoopedTemplates
      ? this.mapping.snoopedTemplates.length
      : 0;
    if (this.mapping.snoopStatus == SnoopStatus.STARTED && numberSnooped > 0) {
      this.alertService.success(
        `Already ${
          numberSnooped
          } templates exist. In the next step you an stop the snooping process and use the templates. Click on Next`
      );
    }
    this.propertyFormlyFields = [
      {
        validators: {
          validation: [
            {
              name:
                this.stepperConfiguration.direction == Direction.INBOUND
                  ? 'checkTopicsInboundAreValid'
                  : 'checkTopicsOutboundAreValid'
            }
          ]
        },
        fieldGroupClassName: 'row',
        fieldGroup: [
          {
            className: 'col-lg-6',
            key: 'name',
            wrappers: ['c8y-form-field'],
            type: 'input',
            templateOptions: {
              label: 'Mapping Name',
              disabled:
                this.stepperConfiguration.editorMode == EditorMode.READ_ONLY,
              required: true
            }
          },
          {
            className: 'col-lg-6',
            key: 'subscriptionTopic',
            wrappers: ['c8y-form-field'],
            type: 'input',
            templateOptions: {
              label: 'Subscription Topic',
              placeholder: 'Subscription Topic ...',
              disabled:
                this.stepperConfiguration.editorMode == EditorMode.READ_ONLY,
              description: 'Subscription Topic',
              change: () => {
                const newDerivedTopic = (this.mapping.templateTopic =
                  deriveTemplateTopicFromTopic(
                    this.propertyFormly.get('subscriptionTopic').value
                  ));
                this.propertyFormly
                  .get('templateTopicSample')
                  .setValue(newDerivedTopic);
                this.propertyFormly
                  .get('templateTopic')
                  .setValue(newDerivedTopic);
                // this.mapping.templateTopic = deriveTemplateTopicFromTopic(
                //   this.propertyFormly.get("subscriptionTopic").value
                // );
                // this.mapping.templateTopicSample = this.mapping.templateTopic;
                // this.mapping = {
                //   ...this.mapping,
                // };
              },
              required: this.stepperConfiguration.direction == Direction.INBOUND
            },
            hideExpression:
              this.stepperConfiguration.direction == Direction.OUTBOUND
          },
          {
            className: 'col-lg-6',
            key: 'publishTopic',
            wrappers: ['c8y-form-field'],
            type: 'input',
            templateOptions: {
              label: 'Publish Topic',
              placeholder: 'Publish Topic ...',
              disabled:
                this.stepperConfiguration.editorMode == EditorMode.READ_ONLY,
              change: () => {
                const newDerivedTopic = deriveTemplateTopicFromTopic(
                  this.propertyFormly.get('publishTopic').value
                );

                this.propertyFormly
                  .get('templateTopicSample')
                  .setValue(newDerivedTopic);
              },
              required:
                this.stepperConfiguration.direction == Direction.OUTBOUND
            },
            hideExpression:
              this.stepperConfiguration.direction != Direction.OUTBOUND
          },
          {
            className: 'col-lg-6',
            key: 'templateTopic',
            type: 'input',
            wrappers: ['c8y-form-field'],
            templateOptions: {
              label: 'Template Topic',
              placeholder: 'Template Topic ...',
              disabled:
                this.stepperConfiguration.editorMode == EditorMode.READ_ONLY,
              description:
                'The TemplateTopic defines the topic to which this mapping is bound to. Name must begin with the Topic name.',
              required: this.stepperConfiguration.direction == Direction.INBOUND
            },
            hideExpression:
              this.stepperConfiguration.direction == Direction.OUTBOUND
          },
          // filler when template topic is not shown
          {
            className: 'col-lg-6',
            type: 'filler',
            hideExpression:
              this.stepperConfiguration.direction != Direction.OUTBOUND
          },
          {
            className: 'col-lg-6',
            key: 'templateTopicSample',
            type: 'input',
            wrappers: ['c8y-form-field'],
            templateOptions: {
              label: 'Template Topic Sample',
              placeholder: 'e.g. device/110',
              disabled:
                this.stepperConfiguration.editorMode == EditorMode.READ_ONLY,
              description: `The TemplateTopicSample name
              must have the same number of
              levels and must match the TemplateTopic.`,
              required: true
            }
          },
          {
            className: 'col-lg-12',
            key: 'filterOutbound',
            type: 'input',
            templateOptions: {
              label: 'Filter Outbound',
              placeholder: 'e.g. custom_OperationFragment',
              disabled:
                this.stepperConfiguration.editorMode == EditorMode.READ_ONLY,
              description:
                'The Filter Outbound can contain one fragment name to associate a mapping to a Cumulocity MEAO. If the Cumulocity MEAO contains this fragment, the mapping is applied. Specify nested elements as follows: custom_OperationFragment.value',
              required:
                this.stepperConfiguration.direction == Direction.OUTBOUND
            },
            hideExpression:
              this.stepperConfiguration.direction != Direction.OUTBOUND
          }
        ]
      },
      {
        fieldGroupClassName: 'row',
        fieldGroup: [
          {
            className: 'col-lg-6',
            key: 'targetAPI',
            type: 'select',
            wrappers: ['c8y-form-field'],
            templateOptions: {
              label: 'Target API',
              options: Object.keys(API).map((key) => {
                return { label: key, value: key };
              }),
              disabled:
                this.stepperConfiguration.editorMode == EditorMode.READ_ONLY,
              change: (field: FormlyFieldConfig, event?: any) => {
                console.log(
                  'Changes:',
                  field,
                  event,
                  this.mapping,
                  this.propertyFormly.valid
                );
                this.onTargetAPIChanged(
                  this.propertyFormly.get('targetAPI').value
                );
              },
              required: true
            }
          },
          {
            className: 'col-lg-6',
            key: 'createNonExistingDevice',
            type: 'switch',
            wrappers: ['c8y-form-field'],
            templateOptions: {
              label: 'Create Non Existing Device',
              disabled:
                this.stepperConfiguration.editorMode == EditorMode.READ_ONLY,
              description:
                'In case a MEAO (Measuremente, Event, Alarm, Operation) is received and the referenced device does not yet exist, it can be created automatically.',
              required: false,
              switchMode: true,
              indeterminate: false
            },
            hideExpression: () =>
              this.stepperConfiguration.direction == Direction.OUTBOUND ||
              this.mapping.targetAPI == API.INVENTORY.name
          },
          {
            className: 'col-lg-6',
            key: 'updateExistingDevice',
            type: 'switch',
            wrappers: ['c8y-form-field'],
            templateOptions: {
              label: 'Update Existing Device',
              disabled:
                this.stepperConfiguration.editorMode == EditorMode.READ_ONLY,
              description: 'Update Existing Device.',
              required: false,
              switchMode: true,
              indeterminate: false
            },
            hideExpression: () =>
              this.stepperConfiguration.direction == Direction.OUTBOUND ||
              (this.stepperConfiguration.direction == Direction.INBOUND &&
                this.mapping.targetAPI != API.INVENTORY.name)
          },
          {
            className: 'col-lg-6',
            key: 'autoAckOperation',
            type: 'switch',
            wrappers: ['c8y-form-field'],
            templateOptions: {
              label: 'Auto acknowledge',
              disabled:
                this.stepperConfiguration.editorMode == EditorMode.READ_ONLY,
              description: 'Auto acknowledge outbound operation.',
              required: false,
              switchMode: true,
              indeterminate: false
            },
            hideExpression: () =>
              this.stepperConfiguration.direction == Direction.INBOUND ||
              (this.stepperConfiguration.direction == Direction.OUTBOUND &&
                this.mapping.targetAPI != API.OPERATION.name)
          }
        ]
      },
      {
        fieldGroupClassName: 'row',
        fieldGroup: [
          {
            className: 'col-lg-6',
            key: 'qos',
            type: 'select',
            wrappers: ['c8y-form-field'],
            templateOptions: {
              label: 'QOS',
              options: Object.values(QOS).map((key) => {
                return { label: key, value: key };
              }),
              disabled:
                this.stepperConfiguration.editorMode == EditorMode.READ_ONLY,
              required: true
            }
          },
          {
            className: 'col-lg-6',
            key: 'snoopStatus',
            type: 'select',
            wrappers: ['c8y-form-field'],
            templateOptions: {
              label: 'Snoop payload',
              options: Object.keys(SnoopStatus).map((key) => {
                return {
                  label: key,
                  value: key,
                  disabled: key != 'ENABLED' && key != 'NONE'
                };
              }),
              disabled:
                this.stepperConfiguration.editorMode == EditorMode.READ_ONLY,
              description:
                'Snooping records the payloads and saves them for later usage. Once the snooping starts and payloads are recorded, they can be used as templates for defining the source format of the mapping.',
              required: true
            }
          }
        ]
      },
      {
        fieldGroupClassName: 'row',
        fieldGroup: [
          {
            className: 'col-lg-6',
            key: 'mapDeviceIdentifier',
            type: 'switch',
            wrappers: ['c8y-form-field'],
            templateOptions: {
              label: 'Map Device Identifier',
              disabled:
                this.stepperConfiguration.editorMode == EditorMode.READ_ONLY,
              switchMode: true,
              description: 'If this is enabled then the device id is treated as an external id which is looked up and translated using th externalIdType.',
              indeterminate: false
            }
          },
          {
            className: 'col-lg-6',
            key: 'externalIdType',
            type: 'input',
            templateOptions: {
              label: 'External Id type',
              disabled:
                this.stepperConfiguration.editorMode == EditorMode.READ_ONLY
            },
            hideExpression: (model) => !model.mapDeviceIdentifier
          }
        ]
      }
    ];
  }

  onTargetAPIChanged(targetAPI) {
    this.mapping.targetAPI = targetAPI;
    if (this.stepperConfiguration.direction == Direction.INBOUND) {
      this.targetTemplateChanged.emit(
        SAMPLE_TEMPLATES_C8Y[this.mapping.targetAPI]
      );
    } else {
      this.targetTemplateChanged.emit(getExternalTemplate(this.mapping));
    }
  }

  ngOnDestroy() {
    this.selectedResult$.complete();
  }
}
