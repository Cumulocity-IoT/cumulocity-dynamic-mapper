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
import { FormlyConfig, FormlyFieldConfig } from '@ngx-formly/core';
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
import { EditorMode, StepperConfiguration } from '../shared/stepper-model';
import {
  checkTopicsInboundAreValidWithOption,
  checkTopicsOutboundAreValid,
  isDisabled
} from '../shared/util';
import { ValidationError } from '../shared/mapping.model';
import { deriveMappingTopicFromTopic } from '../shared/util';

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
  @Output() snoopStatusChanged = new EventEmitter<SnoopStatus>();

  ValidationError = ValidationError;
  Direction = Direction;
  EditorMode = EditorMode;
  isDisabled = isDisabled;

  propertyFormlyFields: FormlyFieldConfig[] = [];
  selectedResult$: BehaviorSubject<number> = new BehaviorSubject<number>(0);
  sourceSystem: string;
  targetSystem: string;

  constructor(
    mappingService: MappingService,
    configurationService: BrokerConfigurationService,
    private alertService: AlertService,
    private configService: FormlyConfig
  ) {}

  ngOnInit() {
    // set value for backward compatiblility
    if (!this.mapping.direction) this.mapping.direction = Direction.INBOUND;
    this.targetSystem =
      this.mapping.direction == Direction.INBOUND ? 'Cumulocity' : 'Broker';
    this.sourceSystem =
      this.mapping.direction == Direction.OUTBOUND ? 'Cumulocity' : 'Broker';
    // console.log(
    //  'Mapping to be updated:',
    //  this.mapping,
    //  this.stepperConfiguration
    // );
    const numberSnooped = this.mapping.snoopedTemplates
      ? this.mapping.snoopedTemplates.length
      : 0;
    if (this.mapping.snoopStatus == SnoopStatus.STARTED && numberSnooped > 0) {
      this.alertService.success(
        `Already ${numberSnooped} templates exist. To stop the snooping process click on Cancel, select the respective mapping in the list of all mappings and choose the action Toggle Snooping.`,
        `The recording process is in state ${this.mapping.snoopStatus}.`
      );
    }
    this.propertyFormlyFields = [
      {
        // validators: {
        //   validation: [
        //     {
        //       name:
        //         this.stepperConfiguration.direction == Direction.INBOUND
        //           ? 'checkTopicsInboundAreValid'
        //           : 'checkTopicsOutboundAreValid'
        //     }
        //   ]
        // },

        validators: {
          validation: [
            this.stepperConfiguration.direction == Direction.INBOUND
              ? checkTopicsInboundAreValidWithOption({ sampleOption: 3 })
              : checkTopicsOutboundAreValid
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
                const newDerivedTopic = deriveMappingTopicFromTopic(
                  this.propertyFormly.get('subscriptionTopic').value
                );
                if (this.stepperConfiguration.direction == Direction.INBOUND) {
                  this.propertyFormly
                    .get('mappingTopic')
                    .setValue(newDerivedTopic);
                  this.propertyFormly
                    .get('mappingTopicSample')
                    .setValue(newDerivedTopic);
                } else {
                  this.propertyFormly
                    .get('publishTopicSample')
                    .setValue(newDerivedTopic);
                }
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
                const newDerivedTopic = deriveMappingTopicFromTopic(
                  this.propertyFormly.get('publishTopic').value
                );

                this.propertyFormly
                  .get('publishTopicSample')
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
            key: 'mappingTopic',
            type: 'input',
            wrappers: ['c8y-form-field'],
            templateOptions: {
              label: 'Mapping Topic',
              placeholder: 'Mapping Topic ...',
              disabled:
                this.stepperConfiguration.editorMode == EditorMode.READ_ONLY,
              description:
                'The MappingTopic defines a key to which this mapping is bound. It is a kind of key to organize the mappings internally. Name must begin with the SubscriptionTopic.',
              required: this.stepperConfiguration.direction == Direction.INBOUND
            },
            hideExpression:
              this.stepperConfiguration.direction == Direction.OUTBOUND
          },
          // filler when template topic is not shown
          {
            className: 'col-lg-6',
            template: '<div class="form-group row" style="height:80px"></div>',
            hideExpression:
              this.stepperConfiguration.direction != Direction.OUTBOUND
          },
          {
            className: 'col-lg-6',
            key: 'mappingTopicSample',
            type: 'input',
            wrappers: ['c8y-form-field'],
            templateOptions: {
              label: 'Mapping Topic Sample',
              placeholder: 'e.g. device/110',
              disabled:
                this.stepperConfiguration.editorMode == EditorMode.READ_ONLY,
              description: `The MappingTopicSample name
              must have the same structure and number of
              levels as the MappingTopic. Wildcards, i.e. "+" in the MappingTopic are replaced with concrete runtime values. This helps to identify the relevant positions in the substitutions`,
              required: true
            },
            hideExpression:
              this.stepperConfiguration.direction == Direction.OUTBOUND
          },
          {
            className: 'col-lg-6',
            key: 'publishTopicSample',
            type: 'input',
            wrappers: ['c8y-form-field'],
            templateOptions: {
              label: 'Publish Topic Sample',
              placeholder: 'e.g. device/110',
              disabled:
                this.stepperConfiguration.editorMode == EditorMode.READ_ONLY,
              description: `The PublishTopicSample name
              must have the same structure and number of
              levels as the PublishTopic. Wildcards, i.e. "+" in the PublishTopic are replaced with concrete runtime values. This helps to identify the relevant positions in the substitutions`,
              required: true
            },
            hideExpression:
              this.stepperConfiguration.direction != Direction.OUTBOUND
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
              // eslint-disable-next-line @typescript-eslint/no-unused-vars
              change: (field: FormlyFieldConfig, event?: any) => {
                // console.log(
                //  'Changes:',
                //  field,
                //  event,
                //  this.mapping,
                //  this.propertyFormly.valid
                // );
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
              label: 'Create non existing device',
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
        //   {
        //     className: 'col-lg-6',
        //     key: 'snoopStatus',
        //     type: 'switch',
        //     wrappers: ['c8y-form-field'],
        //     templateOptions: {
        //       label: 'Snoop payload',
        //       switchMode: true,
        //       disabled: false
        //     },
        //     // validators: {
        //     //   snoopStatus: {
        //     //     // eslint-disable-next-line @typescript-eslint/no-unused-vars
        //     //     expression: (c: AbstractControl) => {
        //     //       return false;
        //     //     },
        //     //     message: (error, field: FormlyFieldConfig) =>
        //     //       `"${field.formControl.value}" is not valid`
        //     //   }
        //     // },
        //     hide: false,
        //     hooks: {
        //       onInit: (field: FormlyFieldConfig) => {
        //         // Set initial value based on the model
        //         field.formControl.setValue(
        //           field.model.snoopStatus === SnoopStatus.ENABLED ||
        //             field.model.snoopStatus === SnoopStatus.STARTED
        //         );
        //       }
        //     }
        //   }
          //   {
          //     className: 'col-lg-6',
          //     key: 'snoopStatus',
          //     type: 'switch',
          //     wrappers: ['c8y-form-field'],
          //     templateOptions: {
          //       label: 'Snoop payload',
          //       switchMode: true,
          //       disabled:
          //         this.stepperConfiguration.editorMode == EditorMode.READ_ONLY,
          //       description:
          //         'Snooping records the payloads and saves them for later usage. Once the snooping starts and payloads are recorded, they can be used as templates for defining the source format of the mapping.'
          //     }
          //   },
          //   {
          //     className: 'col-lg-6',
          //     key: 'isSnoopStatus',
          //     wrappers: ['c8y-form-field'],
          //     type: 'switch',
          //     templateOptions: {
          //       label: 'Snoop Status',
          //       switchMode: true,
          //       disabled:
          //         this.stepperConfiguration.editorMode == EditorMode.READ_ONLY,
          //       description:
          //         'Snooping records the payloads and saves them for later usage. Once the snooping starts and payloads are recorded, they can be used as templates for defining the source format of the mapping.'
          //     },
          //     hooks: {
          //       onInit: (field: FormlyFieldConfig) => {
          //         // Set initial value based on the model
          //         field.formControl.setValue(
          //           field.model.snoopStatus === SnoopStatus.ENABLED ||
          //             field.model.snoopStatus === SnoopStatus.STARTED
          //         );
          //         field.formControl.valueChanges
          //           .pipe(
          //             startWith(field.formControl.value),
          //             distinctUntilChanged(),
          //             skip(1),
          //             tap((value) => {
          //               field.model.snoopStatus = value
          //                 ? SnoopStatus.ENABLED
          //                 : SnoopStatus.NONE;
          //             })
          //           )
          //           .subscribe();
          //       }
          //     }
          //     // expressionProperties: {
          //     //   // 'model.snoopStatus': 'model.snoopStatus ? "ENABLED" : "NONE"'
          //     //   // 'model.snoopStatus':  'model.snoopStatus ? SnoopStatus.NONE : SnoopStatus.NONE'
          //     //   'model.snoopStatus': (model) => {
          //     // 	console.log('Hier', model);
          //     //     return model.snoopStatus ? true : true;
          //     //   }
          //     // }
          //   }
          //   {
          //     className: 'col-lg-6',
          //     key: 'snoopStatus',
          //     type: 'select',
          //     wrappers: ['c8y-form-field'],
          //     templateOptions: {
          //       label: 'Snoop payload',
          //       options: Object.keys(SnoopStatus).map((key) => {
          //         return {
          //           label: key,
          //           value: key,
          //           disabled: key != 'ENABLED' && key != 'NONE'
          //         };
          //       }),
          //       disabled:
          //         this.stepperConfiguration.editorMode == EditorMode.READ_ONLY,
          //       description:
          //         'Snooping records the payloads and saves them for later usage. Once the snooping starts and payloads are recorded, they can be used as templates for defining the source format of the mapping.',
          //       required: true
          //     }
          //   }
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
              label: 'Map device identifier',
              disabled:
                this.stepperConfiguration.editorMode == EditorMode.READ_ONLY,
              switchMode: true,
              description:
                'If this is enabled then the device id is treated as an external id which is looked up and translated using th externalIdType.',
              indeterminate: false
            }
          },
          {
            className: 'col-lg-6',
            key: 'externalIdType',
            type: 'input',
            defaultValue: 'c8y_Serial',
            templateOptions: {
              label: 'External Id type',
              disabled:
                this.stepperConfiguration.editorMode == EditorMode.READ_ONLY
            },
            hideExpression: (model) => !model.mapDeviceIdentifier
          }
        ]
      },
      //   {
      //     fieldGroupClassName: 'row',
      //     fieldGroup: [
      //       {
      //         className: 'col-lg-6',
      //         key: 'messageContextKeys',
      //         type: 'input',
      //         validators: {
      //             messageContextKeys: {
      //               expression: (c: AbstractControl) => /(^[a-zA-Z][a-zA-Z0-9_]*([ ]*,[ ]*[a-zA-Z][a-zA-Z0-9_]*)*$|^$)/.test(c.value),
      //               message: (error: any, field: FormlyFieldConfig) => `"${field.formControl.value}" is not a valid list of keys`,
      //             },
      //           },
      //         templateOptions: {
      //           label: 'Message context keys',
      //           disabled:
      //             this.stepperConfiguration.editorMode == EditorMode.READ_ONLY,
      //             description: 'Comma separated list of names for keys, e.g. partition keys for Kafka',
      //         },
      //       }
      //     ]
      //   }
      {
        fieldGroupClassName: 'row',
        fieldGroup: [
          {
            className: 'col-lg-6',
            key: 'supportsMessageContext',
            type: 'switch',
            wrappers: ['c8y-form-field'],
            templateOptions: {
              switchMode: true,
              label: 'Supports key message context',
              disabled:
                this.stepperConfiguration.editorMode == EditorMode.READ_ONLY,
              description:
                'Supports key from message context, e.g. partition keys for Kafka. This property only applies to certain connectors.'
            }
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
