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
import { CommonModule } from '@angular/common';
import {
  Component,
  EventEmitter,
  inject,
  Input,
  OnDestroy,
  OnInit,
  Output,
  ViewEncapsulation
} from '@angular/core';
import { FormGroup } from '@angular/forms';
import { Alert, AlertService, CoreModule } from '@c8y/ngx-components';
import { FormlyFieldConfig } from '@ngx-formly/core';
import { PopoverModule } from 'ngx-bootstrap/popover';
import { BehaviorSubject, debounceTime, distinctUntilChanged } from 'rxjs';
import {
  API,
  Direction,
  Feature,
  FormatStringPipe,
  Mapping,
  MappingTypeLabels,
  Qos,
  SharedService,
  SnoopStatus,
  StepperConfiguration
} from '../../shared';
import { MappingService } from '../core/mapping.service';
import { ValidationError } from '../shared/mapping.model';
import { EditorMode } from '../shared/stepper.model';
import { deriveSampleTopicFromTopic, getTypeOf } from '../shared/util';

interface FilterExpressionModel {
  filterExpression: {
    result: string;
    resultType: string;
    valid: boolean;
  };
}

@Component({
  selector: 'd11r-mapping-properties',
  templateUrl: 'mapping-properties.component.html',
  styleUrls: ['../shared/mapping.style.css'],
  encapsulation: ViewEncapsulation.None,
  standalone: true,
  imports: [CoreModule, CommonModule, PopoverModule]
})
export class MappingStepPropertiesComponent implements OnInit, OnDestroy {
  @Input() mapping: Mapping;
  @Input() stepperConfiguration: StepperConfiguration;
  @Input() propertyFormly: FormGroup;
  @Input() codeFormly: FormGroup;
  @Output() targetAPIChanged = new EventEmitter<string>();
  @Output() snoopStatusChanged = new EventEmitter<SnoopStatus>();

  readonly ValidationError = ValidationError;
  readonly MappingTypeLabels = MappingTypeLabels;
  readonly Direction = Direction;
  readonly EditorMode = EditorMode;
  readonly readOnlyHelp = ' To edit this mapping deactivate the mapping first in mapping list.';
  readonly selectedResult$ = new BehaviorSubject<number>(0);

  propertyFormlyFields: FormlyFieldConfig[] = [];
  sourceSystem: string;
  targetSystem: string;
  filterMappingModel: FilterExpressionModel;
  filterInventoryModel: FilterExpressionModel;
  feature: Feature;

  private readonly alertService = inject(AlertService);
  private readonly sharedService = inject(SharedService);
  private readonly mappingService = inject(MappingService);
  private readonly formatStringPipe = inject(FormatStringPipe);

  async ngOnInit(): Promise<void> {
    this.feature = await this.sharedService.getFeatures();

    this.initializeDirection();
    this.initializeFilterModels();

    this.propertyFormlyFields = [
      {
        validators: {
          validation: [
            this.stepperConfiguration.direction == Direction.INBOUND
              ? 'checkTopicsInboundAreValid'
              : 'checkTopicsOutboundAreValid'
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
                this.stepperConfiguration.editorMode == EditorMode.READ_ONLY || (!this.feature?.userHasMappingAdminRole && !this.feature?.userHasMappingCreateRole),
              required: true
            }
          },
          {
            className: 'col-lg-6',
            key: 'mappingTopic',
            wrappers: ['c8y-form-field'],
            type: 'input',
            templateOptions: {
              label: 'Mapping Topic',
              placeholder: 'The MappingTopic defines a key to which this mapping is bound. It is a kind of key to organize the mappings internally',
              disabled:
                this.stepperConfiguration.editorMode == EditorMode.READ_ONLY || (!this.feature?.userHasMappingAdminRole && !this.feature?.userHasMappingCreateRole),
              description: 'Mapping Topic',
              change: () => {
                const newDerivedTopic = deriveSampleTopicFromTopic(
                  this.propertyFormly.get('mappingTopic').value
                );
                if (this.stepperConfiguration.direction == Direction.INBOUND) {
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
                this.stepperConfiguration.editorMode == EditorMode.READ_ONLY || (!this.feature?.userHasMappingAdminRole && !this.feature?.userHasMappingCreateRole),
              change: () => {
                const newDerivedTopic = deriveSampleTopicFromTopic(
                  this.propertyFormly.get('publishTopic').value
                );
                this.propertyFormly
                  .get('publishTopicSample')
                  .setValue(newDerivedTopic);
              },
              required:
                true
            },
            hideExpression:
              this.stepperConfiguration.direction == Direction.INBOUND
          },
          // {
          //   className: 'col-lg-6',
          //   key: 'filterMapping',
          //   type: 'input',
          //   templateOptions: {
          //     label: 'Filter Mapping',
          //     placeholder: 'custom_OperationFragment',
          //     disabled:
          //       this.stepperConfiguration.editorMode == EditorMode.READ_ONLY || (!this.feature?.userHasMappingAdminRole && !this.feature?.userHasMappingCreateRole),
          //     description:
          //       'The filter has to be defined as boolean expression (JSONata), e.g. <code>$exists(<C8Y_FRAGMENT>)</code>',
          //     required:
          //       this.stepperConfiguration.direction == Direction.OUTBOUND
          //   },
          //   hideExpression:
          //     this.stepperConfiguration.direction == Direction.INBOUND || (this.stepperConfiguration.direction == Direction.OUTBOUND && (this.mapping.snoopStatus == SnoopStatus.NONE || this.mapping.snoopStatus == SnoopStatus.STOPPED)),
          //   hooks: {
          //     onInit: (field: FormlyFieldConfig) => {
          //       field.formControl.valueChanges.pipe(
          //         // Wait for 1500ms pause in typing before processing
          //         debounceTime(1500),

          //         // Only trigger if the value has actually changed
          //         distinctUntilChanged()
          //       ).subscribe(path => {
          //         this.updateFilterMappingExpressionResult(path);
          //       });
          //     }
          //   }
          // },
          {
            className: 'col-lg-6',
            key: 'filterInventory',
            type: 'input',
            templateOptions: {
              label: 'Filter Inventory',
              placeholder: `type = "lora_device_type`,
              disabled:
                this.stepperConfiguration.editorMode == EditorMode.READ_ONLY || (!this.feature?.userHasMappingAdminRole && !this.feature?.userHasMappingCreateRole),
              description:
                'The filter is applied to the inventory object that is referenced in the payload. The filter has to be defined as boolean expression (JSONata), e.g. <code>type = "lora-device-type"</code>. NOTE: Any property referenced here has to be added in Configuration > Service Configuration > Fragments from inventory to cache.',
              required:
                false
            },
            // hideExpression:
            //this.stepperConfiguration.direction == Direction.INBOUND,
            hooks: {
              onInit: (field: FormlyFieldConfig) => {
                field.formControl.valueChanges.pipe(
                  // Wait for 1500ms pause in typing before processing
                  debounceTime(1500),

                  // Only trigger if the value has actually changed
                  distinctUntilChanged()
                ).subscribe(path => {
                  this.updateFilterInventoryExpressionResult(path);
                });
              }
            }
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
                this.stepperConfiguration.editorMode == EditorMode.READ_ONLY || (!this.feature?.userHasMappingAdminRole && !this.feature?.userHasMappingCreateRole),
              description: `The Mapping Topic Sample name
              must have the same structure and number of
              levels as the MappingTopic. Wildcards, i.e. <code>+</code> in the Mapping Topic are replaced with concrete runtime values. This helps to identify the relevant positions in the substitutions`,
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
                this.stepperConfiguration.editorMode == EditorMode.READ_ONLY || (!this.feature?.userHasMappingAdminRole && !this.feature?.userHasMappingCreateRole),
              description: `The Publish Topic Sample name
              must have the same structure and number of
              levels as the PublishTopic. Wildcards, i.e. <code>+</code> in the PublishTopic are replaced with concrete runtime values. This helps to identify the relevant positions in the substitutions`,
              required: true
            },
            hideExpression:
              this.stepperConfiguration.direction != Direction.OUTBOUND
          }
        ]
      },
      {
        type: 'template',
        template: '<div class="legend form-block col-xs-12">Properties</div>'
      },
      {
        fieldGroupClassName: 'row',
        fieldGroup: [
          {
            className: 'col-lg-3',
            key: 'targetAPI',
            type: 'select',
            wrappers: ['c8y-form-field'],
            templateOptions: {
              label: this.stepperConfiguration.direction == Direction.INBOUND ? 'Target API' : 'Source API',
              options: Object.keys(API)
                .filter((key) => key != API.ALL.name)
                .map((key) => {
                  return { label: this.formatStringPipe.transform(key), value: key };
                }),
              disabled:
                this.stepperConfiguration.editorMode == EditorMode.READ_ONLY || (!this.feature?.userHasMappingAdminRole && !this.feature?.userHasMappingCreateRole),
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
            className: 'col-lg-3',
            key: 'createNonExistingDevice',
            type: 'switch',
            wrappers: ['custom-form-field-wrapper'],
            templateOptions: {
              label: 'Create device',
              disabled:
                this.stepperConfiguration.editorMode == EditorMode.READ_ONLY || (!this.feature?.userHasMappingAdminRole && !this.feature?.userHasMappingCreateRole),
              description:
                'In case a MEAO (Measuremente, Event, Alarm, Operation) is received and the referenced device does not yet exist, it can be created automatically.',
              required: false,
              switchMode: true,
              indeterminate: false,
              hideLabel: true
            },
            hideExpression: () =>
              this.stepperConfiguration.direction == Direction.OUTBOUND ||
              this.mapping.targetAPI == API.INVENTORY.name
          },
          {
            className: 'col-lg-3',
            key: 'updateExistingDevice',
            type: 'switch',
            wrappers: ['custom-form-field-wrapper'],
            templateOptions: {
              label: 'Update Existing Device',
              disabled:
                this.stepperConfiguration.editorMode == EditorMode.READ_ONLY || (!this.feature?.userHasMappingAdminRole && !this.feature?.userHasMappingCreateRole),
              description: 'Update Existing Device.',
              required: false,
              switchMode: true,
              indeterminate: false,
              hideLabel: true
            },
            hideExpression: () =>
              this.stepperConfiguration.direction == Direction.OUTBOUND ||
              (this.stepperConfiguration.direction == Direction.INBOUND &&
                this.mapping.targetAPI != API.INVENTORY.name)
          },
          {
            className: 'col-lg-3',
            key: 'autoAckOperation',
            type: 'switch',
            wrappers: ['custom-form-field-wrapper'],
            templateOptions: {
              label: 'Auto acknowledge',
              disabled:
                this.stepperConfiguration.editorMode == EditorMode.READ_ONLY || (!this.feature?.userHasMappingAdminRole && !this.feature?.userHasMappingCreateRole),
              description: 'Auto acknowledge outbound operation.',
              required: false,
              switchMode: true,
              indeterminate: false,
              hideLabel: true
            },
            hideExpression: () =>
              this.stepperConfiguration.direction == Direction.INBOUND ||
              (this.stepperConfiguration.direction == Direction.OUTBOUND &&
                this.mapping.targetAPI != API.OPERATION.name)
          },

          {
            className: 'col-lg-3',
            key: 'eventWithAttachment',
            type: 'switch',
            wrappers: ['custom-form-field-wrapper'],
            templateOptions: {
              label: 'Event contains attachment',
              disabled:
                this.stepperConfiguration.editorMode == EditorMode.READ_ONLY || (!this.feature?.userHasMappingAdminRole && !this.feature?.userHasMappingCreateRole),
              description: 'Event contains attachment, e.g. image, ... that is stored separately.',
              required: false,
              switchMode: true,
              indeterminate: false,
              hideLabel: true
            },
            hideExpression: () =>
              this.stepperConfiguration.direction == Direction.OUTBOUND ||
              this.mapping.targetAPI !== API.EVENT.name
          },



          {
            className: 'col-lg-3',
            template: '<div class="form-group row" style="height:80px"></div>',
            hideExpression: () =>
              this.stepperConfiguration.direction == Direction.INBOUND ||
              (this.stepperConfiguration.direction == Direction.OUTBOUND &&
                this.mapping.targetAPI == API.OPERATION.name)
          },
          {
            className: 'col-lg-6',
            key: 'qos',
            type: 'select',
            wrappers: ['c8y-form-field'],
            templateOptions: {
              label: 'QoS',
              options: Object.values(Qos).filter(key => {
                // When direction is OUTBOUND, only include AT_MOST_ONCE and AT_LEAST_ONCE
                if (this.stepperConfiguration.direction === Direction.OUTBOUND) {
                  return key === Qos.AT_MOST_ONCE || key === Qos.AT_LEAST_ONCE;
                }
                // Otherwise, include all QoS options
                return true;
              }).
                map((key) => {
                  return { label: this.formatStringPipe.transform(key), value: key };
                }),
              disabled:
                this.stepperConfiguration.editorMode == EditorMode.READ_ONLY || (!this.feature?.userHasMappingAdminRole && !this.feature?.userHasMappingCreateRole),
              required: true
            }
          }
        ]
      },
      {
        fieldGroupClassName: 'row',
        fieldGroup: [
          {
            className: 'col-lg-3',
            key: 'useExternalId',
            type: 'switch',
            wrappers: ['custom-form-field-wrapper'],
            templateOptions: {
              label: 'Use external id',
              switchMode: true,
              disabled:
                this.stepperConfiguration.editorMode == EditorMode.READ_ONLY || (!this.feature?.userHasMappingAdminRole && !this.feature?.userHasMappingCreateRole),
              description:
                'If this is enabled then the device id is identified by its  external id which is looked up and translated using the externalIdType.',
              indeterminate: false,
              hideLabel: true
            }
          },
          {
            className: 'col-lg-3',
            key: 'externalIdType',
            type: 'input',
            defaultValue: 'c8y_Serial',
            templateOptions: {
              label: 'External Id type',
              disabled:
                this.stepperConfiguration.editorMode == EditorMode.READ_ONLY || (!this.feature?.userHasMappingAdminRole && !this.feature?.userHasMappingCreateRole),
            },
            hideExpression: (model) => !model.useExternalId
          },
          // filler
          {
            className: 'col-lg-3',
            template: '<div class="form-group row" style="height:80px"></div>',
            hideExpression: (model) => model.useExternalId
          },
          {
            className: 'col-lg-6',
            key: 'maxFailureCount',
            type: 'input',
            wrappers: ['c8y-form-field'],
            templateOptions: {
              label: 'Max failure count',
              disabled:
                this.stepperConfiguration.editorMode == EditorMode.READ_ONLY || (!this.feature?.userHasMappingAdminRole && !this.feature?.userHasMappingCreateRole),
              description:
                'Max failure count, if this is exceeded the mapping is automatically deactivated. A value of 0 means no limit. The failure count is reset to 0 if the mapping is reactivated.',
            },
          }
        ]
      }
    ];
  }

  ngOnDestroy() {
    this.selectedResult$.complete();
  }

  async updateFilterMappingExpressionResult(path) {
    try {
      const resultExpression: JSON = await this.mappingService.evaluateExpression(
        JSON.parse('{}'),
        path
      );
      this.filterMappingModel.filterExpression = {
        resultType: getTypeOf(resultExpression),
        result: JSON.stringify(resultExpression, null, 4),
        valid: true
      };
      if (path && this.filterMappingModel.filterExpression.resultType != 'Boolean') throw Error('The filter expression must return of boolean type');
      this.mapping.filterMapping = path;
      // this.propertyFormly
      // .get('filterMapping')
      // .setErrors(null);
    } catch (error) {
      this.filterMappingModel.filterExpression.valid = false;
      this.propertyFormly
        .get('filterMapping')
        .setErrors({ validationError: { message: error.message } });
      this.propertyFormly.get('filterMapping').markAsTouched();
    }
    this.filterMappingModel = { ...this.filterMappingModel };
  }

  async updateFilterInventoryExpressionResult(path) {
    this.clearAlerts();
    this.raiseAlert({ type: 'info', text: 'NOTE: Any property referenced here has to be added in Configuration > Service Configuration > Fragments from inventory to cache.' })
    try {
      const resultExpression: JSON = await this.mappingService.evaluateExpression(
        JSON.parse('{}'),
        path
      );
      this.filterInventoryModel.filterExpression = {
        resultType: getTypeOf(resultExpression),
        result: JSON.stringify(resultExpression, null, 4),
        valid: true
      };
      if (path && this.filterInventoryModel.filterExpression.resultType != 'Boolean') throw Error('The filter expression must return of boolean type');
      this.mapping.filterInventory = path;
      // this.propertyFormly
      // .get('filterInventory')
      // .setErrors(null);
    } catch (error) {
      this.filterInventoryModel.filterExpression.valid = false;
      this.propertyFormly
        .get('filterInventory')
        .setErrors({ validationError: { message: error.message } });
      this.propertyFormly.get('filterInventory').markAsTouched();
    }
    this.filterInventoryModel = { ...this.filterInventoryModel };
  }

  onTargetAPIChanged(targetAPI: string): void {
    this.mapping.targetAPI = targetAPI;
    this.targetAPIChanged.emit(targetAPI);
  }

  clearAlerts(): void {
    this.alertService.clearAll();
  }

  raiseAlert(alert: Alert): void {
    this.alertService.state.forEach(a => {
      if (a.type === 'info') {
        this.alertService.remove(a);
      }
    });
    this.alertService.add(alert);
  }

  private initializeDirection(): void {
    if (!this.mapping.direction) {
      this.mapping.direction = Direction.INBOUND;
    }

    this.targetSystem = this.mapping.direction === Direction.INBOUND ? 'Cumulocity' : 'Broker';
    this.sourceSystem = this.mapping.direction === Direction.OUTBOUND ? 'Cumulocity' : 'Broker';
  }

  private initializeFilterModels(): void {
    const emptyFilterExpression = {
      result: '',
      resultType: 'empty',
      valid: false
    };

    this.filterMappingModel = {
      filterExpression: { ...emptyFilterExpression }
    };

    this.filterInventoryModel = {
      filterExpression: { ...emptyFilterExpression }
    };
  }

  private get isReadOnly(): boolean {
    return this.stepperConfiguration.editorMode === EditorMode.READ_ONLY;
  }

  private get canEdit(): boolean {
    return this.feature?.userHasMappingAdminRole || this.feature?.userHasMappingCreateRole;
  }

  private get isFieldDisabled(): boolean {
    return this.isReadOnly || !this.canEdit;
  }
}
