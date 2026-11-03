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
import { AlertService, CoreModule } from '@c8y/ngx-components';
import { FormlyFieldConfig } from '@ngx-formly/core';
import { PopoverModule } from 'ngx-bootstrap/popover';
import { debounceTime, distinctUntilChanged, firstValueFrom, Subject, takeUntil } from 'rxjs';
import {
  API,
  clampQos,
  ConnectorConfiguration,
  ConnectorSpecification,
  DeploymentMapEntry,
  Direction,
  Feature,
  FormatStringPipe,
  Mapping,
  MappingTypeLabels,
  Qos,
  qosLabel,
  QOS_OPTIONS,
  SharedService,
  StepperConfiguration
} from '../../shared';
import { ConnectorConfigurationService } from '../../shared/service/connector-configuration.service';
import { MappingService } from '../core/mapping.service';
import { MappingStepperService } from '../service/mapping-stepper.service';
import { deriveSampleTopicFromTopic, getTypeOf, topicsHaveSameStructure } from '../../shared/mapping/util';

interface FilterExpressionModel {
  filterExpression: {
    result: string;
    resultType: string;
    valid: boolean;
  };
}

import { findUncachedFilterAttributes } from './inventory-filter-fragments';

@Component({
  selector: 'd11r-mapping-properties',
  templateUrl: 'mapping-step-properties.component.html',
  styleUrls: ['../../shared/mapping/mapping.style.css'],
  // Tighter vertical rhythm so the whole tab fits one screen; scoped to this tab's wrapper.
  styles: [`
    .d11r-props-compact .legend.form-block { margin: 12px 0 4px; }
    .d11r-props-compact .form-group { margin-bottom: 8px; }
  `],
  encapsulation: ViewEncapsulation.None,
  standalone: true,
  imports: [CoreModule, PopoverModule]
})
export class MappingStepPropertiesComponent implements OnInit, OnDestroy {
  @Input() mapping: Mapping;
  @Input() stepperConfiguration: StepperConfiguration;
  @Input() propertyFormly: FormGroup;
  /**
   * The connectors this mapping is deployed to. Used to tell the user when the QoS they picked
   * cannot be honoured by one of those connectors — the backend clamps it silently otherwise.
   */
  @Input() deploymentMapEntry: DeploymentMapEntry;
  @Output() targetAPIChanged = new EventEmitter<string>();

  readonly MappingTypeLabels = MappingTypeLabels;
  readonly Direction = Direction;
  readonly readOnlyHelp = ' This mapping is read-only: it uses a deprecated transformation type, or your user lacks the Mapping Admin / Mapping Create role.';

  propertyFormlyFields: FormlyFieldConfig[] = [];
  sourceSystem: string;
  targetSystem: string;
  // Initialised here, not only in ngOnInit: ngOnInit awaits before it gets to initializeFilterModels(),
  // and the template (which reads filterInventoryModel.filterExpression) is rendered in between.
  filterInventoryModel: FilterExpressionModel = {
    filterExpression: { result: '', resultType: 'empty', valid: false }
  };

  private static readonly FILTER_INVENTORY_DESCRIPTION =
    'The filter is applied to the inventory object that is referenced in the payload. The filter has to be defined as boolean expression (JSONata), e.g. <code>type = "lora-device-type"</code>. NOTE: Any property referenced here has to be added in Configuration > Service Configuration > Fragments from inventory to cache.';

  /** The Filter Inventory field, kept so its help text can carry the "not cached" warning. */
  private filterInventoryField?: FormlyFieldConfig;

  /** Attributes of the current filter that are not in the inventory cache configuration. */
  filterInventoryUncached: string[] = [];

  private feature: Feature;
  private readonly destroy$ = new Subject<void>();

  private readonly alertService = inject(AlertService);
  private readonly sharedService = inject(SharedService);
  private readonly mappingService = inject(MappingService);
  private readonly stepperService = inject(MappingStepperService);
  private readonly formatStringPipe = inject(FormatStringPipe);
  private readonly connectorConfigurationService = inject(ConnectorConfigurationService);

  /** QoS levels supported per deployed connector, keyed by connector name (for the hint text). */
  private qosCapabilityPerConnector: { name: string; supportedQos?: Qos[] }[] = [];

  async ngOnInit(): Promise<void> {
    this.feature = await this.sharedService.getFeatures();
    await this.loadQosCapabilities();
    this.initializeDirection();
    this.initializeFilterModels();

    const isInbound = this.stepperConfiguration.direction === Direction.INBOUND;
    const isOutbound = this.stepperConfiguration.direction === Direction.OUTBOUND;
    // Switches share this shape; hideLabel puts the label next to the toggle.
    const switchProps = (label: string, description: string) => ({
      label,
      description,
      disabled: this.isFieldDisabled,
      required: false,
      switchMode: true,
      indeterminate: false,
      hideLabel: true,
      // The switch already offers the description as a help popover; no second copy below it.
      hideInlineDescription: true
    });

    this.propertyFormlyFields = [
      {
        type: 'template',
        template: '<div class="legend form-block">General, topic, and filter</div>'
      },
      {
        fieldGroupClassName: 'row',
        fieldGroup: [
          {
            className: 'col-lg-6',
            key: 'name',
            id: 'mappingName',
            wrappers: ['c8y-form-field'],
            type: 'input',
            templateOptions: {
              label: 'Mapping name',
              disabled: this.isFieldDisabled,
              required: true
            }
          },
          {
            // Mapping and transformation type cannot be edited here, so they are shown as a
            // summary instead of two disabled inputs.
            className: 'col-lg-6',
            type: 'template',
            expressions: {
              template: (field: FormlyFieldConfig) =>
                `<div class="form-group"><label>Mapping and transformation type</label>` +
                `<div class="m-t-4"><span class="label label-default m-r-8" data-cy="dm-mapping-properties-mapping-type">${field.model?.mappingType ?? ''}</span>` +
                `<span class="label label-default" data-cy="dm-mapping-properties-transformation-type">${field.model?.transformationType ?? ''}</span></div></div>`
            }
          }
        ]
      },
      {
        validators: {
          validation: [isInbound ? 'checkTopicsInboundAreValid' : 'checkTopicsOutboundAreValid']
        },
        fieldGroupClassName: 'row',
        fieldGroup: [
          {
            className: 'col-lg-6',
            key: 'mappingTopic',
            id: 'mappingTopic',
            wrappers: ['c8y-form-field'],
            type: 'input',
            templateOptions: {
              label: 'Mapping topic',
              placeholder: 'plant1/+/+',
              disabled: this.isFieldDisabled,
              description:
                'The mapping topic is the key to which this mapping is bound. It is used to organize the mappings internally.',
              change: () => {
                const newTopic = this.propertyFormly.get('mappingTopic').value;
                const sampleControl = this.propertyFormly.get('mappingTopicSample');
                if (topicsHaveSameStructure(newTopic, sampleControl.value)) {
                  return;
                }
                sampleControl.setValue(deriveSampleTopicFromTopic(newTopic));
              },
              required: true
            },
            hideExpression: isOutbound
          },
          {
            className: 'col-lg-6',
            key: 'publishTopic',
            wrappers: ['c8y-form-field'],
            type: 'input',
            templateOptions: {
              label: 'Publish topic',
              placeholder: 'measurements/+',
              disabled: this.isFieldDisabled,
              change: () => {
                const newTopic = this.propertyFormly.get('publishTopic').value;
                const sampleControl = this.propertyFormly.get('publishTopicSample');
                if (topicsHaveSameStructure(newTopic, sampleControl.value)) {
                  return;
                }
                sampleControl.setValue(deriveSampleTopicFromTopic(newTopic));
              },
              required: true
            },
            hideExpression: isInbound
          },
          {
            className: 'col-lg-6',
            key: 'mappingTopicSample',
            id: 'mappingTopicSample',
            type: 'input',
            wrappers: ['c8y-form-field'],
            templateOptions: {
              label: 'Mapping topic sample',
              placeholder: 'e.g. device/110',
              disabled: this.isFieldDisabled,
              description: `The mapping topic sample
              must have the same structure and number of
              levels as the mapping topic. Wildcards, i.e. <code>+</code> in the mapping topic are replaced with concrete runtime values. This helps to identify the relevant positions in the substitutions`,
              required: true
            },
            hideExpression: isOutbound
          },
          {
            className: 'col-lg-6',
            key: 'publishTopicSample',
            type: 'input',
            wrappers: ['c8y-form-field'],
            templateOptions: {
              label: 'Publish topic sample',
              placeholder: 'e.g. device/110',
              disabled: this.isFieldDisabled,
              description: `The publish topic sample
              must have the same structure and number of
              levels as the publish topic. Wildcards, i.e. <code>+</code> in the publish topic are replaced with concrete runtime values. This helps to identify the relevant positions in the substitutions`,
              required: true
            },
            hideExpression: !isOutbound
          }
        ]
      },
      {
        fieldGroupClassName: 'row',
        fieldGroup: [
          {
            className: 'col-lg-12',
            key: 'filterInventory',
            type: 'input',
            // The project wrapper, not the stock c8y one: it can show the "not in the inventory
            // cache" warning under the input (props.warning).
            wrappers: ['d11r-wrapper-form-field'],
            templateOptions: {
              label: 'Filter inventory',
              placeholder: 'type = "lora_device_type"',
              disabled: this.isFieldDisabled,
              description: MappingStepPropertiesComponent.FILTER_INVENTORY_DESCRIPTION,
              required: false
            },
            hooks: {
              onInit: (field: FormlyFieldConfig) => {
                this.filterInventoryField = field;
                // An existing mapping opens with its filter already set: check it right away
                // instead of only after the first edit.
                void this.refreshFilterInventoryWarning(this.mapping.filterInventory);
                field.formControl.valueChanges.pipe(
                  debounceTime(1500),
                  distinctUntilChanged(),
                  takeUntil(this.destroy$)
                ).subscribe(path => {
                  this.updateFilterInventoryExpressionResult(path);
                });
              }
            }
          }
        ]
      },
      {
        type: 'template',
        template: `<div class="legend form-block">${isInbound ? 'Target' : 'Source'} and device</div>`
      },
      {
        // The API select takes the left half; the option that applies to the chosen API sits in
        // the right half. Hidden options are removed from the DOM, so no spacer is needed.
        fieldGroupClassName: 'row',
        fieldGroup: [
          {
            className: 'col-lg-6',
            key: 'targetAPI',
            type: 'select',
            wrappers: ['c8y-form-field'],
            templateOptions: {
              label: isInbound ? 'Target API' : 'Source API',
              description: isOutbound
                ? 'Used as a filter to select which mappings apply when a notification arrives.'
                : undefined,
              options: Object.keys(API)
                .filter((key) => key !== API.ALL.name)
                .map((key) => {
                  return { label: this.formatStringPipe.transform(key), value: key };
                }),
              disabled: this.isFieldDisabled,
              change: () => {
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
            wrappers: ['d11r-wrapper-form-field'],
            templateOptions: switchProps(
              'Create device',
              'In case a MEAO (Measurement, Event, Alarm, Operation) is received and the referenced device does not yet exist, it can be created automatically.'
            ),
            hideExpression: () => isOutbound || this.mapping.targetAPI === API.INVENTORY.name
          },
          {
            className: 'col-lg-3',
            key: 'updateExistingDevice',
            type: 'switch',
            wrappers: ['d11r-wrapper-form-field'],
            templateOptions: switchProps(
              'Update existing device',
              'Update the managed object of the device if it already exists.'
            ),
            hideExpression: () => isOutbound || this.mapping.targetAPI !== API.INVENTORY.name
          },
          {
            className: 'col-lg-3',
            key: 'eventWithAttachment',
            type: 'switch',
            wrappers: ['d11r-wrapper-form-field'],
            templateOptions: switchProps(
              'Event contains attachment',
              'Event contains attachment, e.g. image, ... that is stored separately.'
            ),
            hideExpression: () => isOutbound || this.mapping.targetAPI !== API.EVENT.name
          },
          {
            className: 'col-lg-3',
            key: 'autoAckOperation',
            type: 'switch',
            wrappers: ['d11r-wrapper-form-field'],
            templateOptions: switchProps(
              'Auto acknowledge',
              'Auto acknowledge outbound operation.'
            ),
            hideExpression: () => isInbound || this.mapping.targetAPI !== API.OPERATION.name
          }
        ]
      },
      {
        fieldGroupClassName: 'row',
        fieldGroup: [
          {
            className: 'col-lg-6',
            key: 'useExternalId',
            type: 'switch',
            wrappers: ['d11r-wrapper-form-field'],
            templateOptions: {
              ...switchProps(
                'Use external id',
                'If this is enabled then the device id is identified by its external id which is looked up and translated using the external id type.'
              ),
              change: () => {
                this.mapping.useExternalId = !!this.propertyFormly.get('useExternalId').value;
                this.stepperService.notifyMappingPropertyChanged(this.mapping);
              }
            }
          },
          {
            className: 'col-lg-6',
            key: 'externalIdType',
            type: 'input',
            wrappers: ['c8y-form-field'],
            defaultValue: 'c8y_Serial',
            templateOptions: {
              label: 'External id type',
              disabled: this.isFieldDisabled
            },
            hideExpression: (model) => !model.useExternalId
          }
        ]
      },
      {
        type: 'template',
        template: '<div class="legend form-block">Delivery</div>'
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
              label: 'QoS',
              // Labels and the per-level explanation come from the shared QoS metadata, so the
              // grid renderer and this picker can never describe a level differently.
              options: QOS_OPTIONS
                // Outbound publishing is offered at at-most-once / at-least-once only.
                .filter(
                  (option) =>
                    !isOutbound ||
                    option.value !== Qos.EXACTLY_ONCE
                )
                .map((option) => ({ label: option.label, value: option.value })),
              disabled: this.isFieldDisabled,
              required: true
            },
            expressions: {
              // Recomputed on every change so the user sees immediately when the level they
              // picked will be clamped by one of the connectors this mapping is deployed to.
              'templateOptions.description': (field) => this.describeQos(field.model?.qos)
            }
          },
          {
            className: 'col-lg-6',
            key: 'maxFailureCount',
            type: 'input',
            wrappers: ['c8y-form-field'],
            templateOptions: {
              label: 'Max failure count',
              type: 'number',
              min: 0,
              disabled: this.isFieldDisabled,
              description:
                'Number of consecutive failures after which the mapping is automatically deactivated. A value of 0 means no limit. The counter is reset by the first message that is processed without an error, and when the mapping is reactivated.'
            },
            validators: {
              // A negative threshold would silently behave like 0 (check disabled) instead of
              // being rejected, so catch it in the form.
              nonNegative: {
                expression: (control) =>
                  control.value === null || control.value === undefined || control.value === '' ||
                  Number(control.value) >= 0,
                message: 'The max failure count must not be negative.'
              }
            }
          }
        ]
      }
    ];
  }

  ngOnDestroy() {
    this.destroy$.next();
    this.destroy$.complete();
  }

  async updateFilterInventoryExpressionResult(path: string) {
    this.clearAlerts();
    // The cache note is part of the field's help text, and a missing attribute is reported right
    // under the field (see refreshFilterInventoryWarning) — not as a toast that disappears.
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
      if (path && this.filterInventoryModel.filterExpression.resultType !== 'Boolean') throw Error('The filter expression must return of boolean type');
      this.mapping.filterInventory = path;
      await this.refreshFilterInventoryWarning(path);
    } catch (error) {
      this.filterInventoryModel.filterExpression.valid = false;
      this.propertyFormly
        .get('filterInventory')
        .setErrors({ validationError: { message: error.message } });
      this.propertyFormly.get('filterInventory').markAsTouched();
    }
    this.filterInventoryModel = { ...this.filterInventoryModel };
  }

  /**
   * The filter runs against the cached copy of the inventory object, which only holds the
   * fragments listed in the service configuration. An attribute that is not listed is simply
   * absent there, so the filter silently evaluates as if the device did not have it. Say so under
   * the field. Not an error (the expression is valid, and the cache may be changed later), so it
   * does not touch the form's validity.
   */
  async refreshFilterInventoryWarning(expression: string): Promise<void> {
    let uncached: string[] = [];
    try {
      const configuration = await this.sharedService.getServiceConfiguration();
      const cachedFragments = [...(configuration?.inventoryFragmentsToCache ?? [])];
      if (configuration?.cacheAliasMaps) {
        cachedFragments.push('sparkPlugB_NBIRTH', 'sparkPlugB_DBIRTH_*');
      }
      uncached = findUncachedFilterAttributes(expression, cachedFragments);
    } catch {
      // Configuration not readable (e.g. missing permission): nothing reliable to warn about.
      uncached = [];
    }
    this.filterInventoryUncached = uncached;
    const field = this.filterInventoryField;
    if (field?.props) {
      field.props['warning'] = uncached.length ? this.uncachedWarningText(uncached) : undefined;
      field.options?.detectChanges?.(field);
    }
  }

  private uncachedWarningText(attributes: string[]): string {
    const single = attributes.length === 1;
    return (
      `Not in the inventory cache: ${attributes.join(', ')}. ` +
      `The filter evaluates as if the device did not have ${single ? 'it' : 'them'}. ` +
      `Add ${single ? 'it' : 'them'} under Configuration > Service Configuration > Fragments from inventory to cache.`
    );
  }

  onTargetAPIChanged(targetAPI: string): void {
    this.mapping.targetAPI = targetAPI;
    this.targetAPIChanged.emit(targetAPI);
  }

  clearAlerts(): void {
    this.alertService.clearAll();
  }

  /**
   * Resolves the QoS capability of every connector this mapping is deployed to. Failures are
   * non-fatal: without the capability the QoS hint simply omits the connector-specific part.
   */
  private async loadQosCapabilities(): Promise<void> {
    const identifiers = this.deploymentMapEntry?.connectors ?? [];
    if (!identifiers.length) {
      return;
    }
    try {
      const [configurations, specifications] = await Promise.all([
        firstValueFrom(this.connectorConfigurationService.getConfigurations()),
        firstValueFrom(this.connectorConfigurationService.getSpecifications())
      ]);
      this.qosCapabilityPerConnector = identifiers
        .map((identifier) =>
          configurations.find((configuration: ConnectorConfiguration) => configuration.identifier === identifier)
        )
        .filter((configuration) => !!configuration)
        .map((configuration) => ({
          name: configuration.name,
          supportedQos: specifications.find(
            (specification: ConnectorSpecification) =>
              specification.connectorType === configuration.connectorType
          )?.supportedQos
        }));
    } catch (error) {
      this.qosCapabilityPerConnector = [];
    }
  }

  /**
   * Description shown under the QoS picker: what the selected level means, plus a warning for
   * every deployed connector that cannot honour it (the backend clamps those to the strongest
   * level they support).
   */
  describeQos(qos: Qos): string {
    const description = QOS_OPTIONS.find((option) => option.value === qos)?.description;
    if (!description) {
      // Nothing selected yet (or an unknown value) — there is no level to warn about.
      return '';
    }
    const clamped = this.qosCapabilityPerConnector
      .filter((connector) => clampQos(qos, connector.supportedQos) !== qos)
      .map(
        (connector) =>
          `${connector.name} (handled as ${qosLabel(clampQos(qos, connector.supportedQos)).toLowerCase()})`
      );
    return clamped.length
      ? `${description} NOTE: this level is not supported by: ${clamped.join(', ')}.`
      : description;
  }

  private initializeDirection(): void {
    if (!this.mapping.direction) {
      this.mapping.direction = Direction.INBOUND;
    }

    this.targetSystem = this.mapping.direction === Direction.INBOUND ? 'Cumulocity' : 'Broker';
    this.sourceSystem = this.mapping.direction === Direction.OUTBOUND ? 'Cumulocity' : 'Broker';
  }

  private initializeFilterModels(): void {
    this.filterInventoryModel = {
      filterExpression: { result: '', resultType: 'empty', valid: false }
    };
  }

  private get isReadOnly(): boolean {
    return !!this.stepperConfiguration.readOnly;
  }

  private get canEdit(): boolean {
    return this.feature?.userHasMappingAdminRole || this.feature?.userHasMappingCreateRole;
  }

  private get isFieldDisabled(): boolean {
    return this.isReadOnly || !this.canEdit;
  }
}
