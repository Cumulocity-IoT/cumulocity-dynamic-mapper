/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
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
  DoCheck,
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
import { gettext } from '@c8y/ngx-components/gettext';
import { FormlyFieldConfig } from '@ngx-formly/core';
import { TranslateService } from '@ngx-translate/core';
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
import { ExecutionFilterMode, fromFilterMapping, toFilterMapping } from './execution-filter.model';
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
    /* Expert Mode: out of the layout flow, above the right end of the first section heading line. */
    .d11r-props-compact .expert-mode-row { position: relative; height: 0; margin: 0; }
    .d11r-props-compact .expert-mode-corner {
      position: absolute; top: -36px; right: 0; z-index: 1; width: auto; padding: 0;
    }
    .d11r-props-compact .expert-mode-corner .form-group { margin: 0; }
    /* Same box as .form-control, so the text starts where the neighboring input's text starts. */
    .d11r-props-compact .filter-result {
      padding: 5px 8px;
      border: 1px solid transparent;
      line-height: 20px;
      min-height: 32px;
      box-sizing: border-box;
      white-space: pre-wrap;
      overflow-wrap: anywhere;
    }
  `],
  encapsulation: ViewEncapsulation.None,
  standalone: true,
  imports: [CoreModule, PopoverModule]
})
export class MappingStepPropertiesComponent implements OnInit, OnDestroy, DoCheck {
  @Input() mapping: Mapping;
  @Input() stepperConfiguration: StepperConfiguration;
  @Input() propertyFormly: FormGroup;
  /**
   * The connectors this mapping is deployed to. Used to tell the user when the QoS they picked
   * cannot be honoured by one of those connectors — the backend clamps it silently otherwise.
   */
  @Input() deploymentMapEntry: DeploymentMapEntry;
  @Output() targetAPIChanged = new EventEmitter<string>();
  /**
   * Changed properties, as a patch. The step edits a working copy (`model`), never the host's
   * mapping; the host applies the patch.
   */
  @Output() propertiesChange = new EventEmitter<Partial<Mapping>>();

  /** The properties this step edits. Anything else on the mapping is not copied, so a stale copy can never overwrite it. */
  static readonly PROPERTY_KEYS: (keyof Mapping)[] = [
    'name', 'mappingTopic', 'publishTopic', 'mappingTopicSample', 'publishTopicSample',
    'filterInventory', 'targetAPI', 'createNonExistingDevice', 'updateExistingDevice',
    'eventWithAttachment', 'autoAckOperation', 'useExternalId', 'externalIdType', 'qos',
    'maxFailureCount'
  ];

  /**
   * Formly's model: a working copy of the properties in PROPERTY_KEYS, plus the two helper keys of
   * the execution-filter control (outbound). Those are a view over `filterMapping` and are never
   * committed.
   */
  model: Partial<Mapping> & { executionFilterMode?: ExecutionFilterMode; executionFilterValue?: string; expertMode?: boolean } = {};

  readonly MappingTypeLabels = MappingTypeLabels;
  readonly Direction = Direction;
  readonly readOnlyHelp = ' This mapping is read-only: it uses a deprecated transformation type, or your user lacks the Mapping Admin / Mapping Create role.';

  propertyFormlyFields: FormlyFieldConfig[] = [];
  private filterResultField: FormlyFieldConfig;
  sourceSystem: string;
  targetSystem: string;
  // Initialised here, not only in ngOnInit: ngOnInit awaits before it gets to initializeFilterModels(),
  // and the template (which reads filterInventoryModel.filterExpression) is rendered in between.
  filterInventoryModel: FilterExpressionModel = {
    filterExpression: { result: '', resultType: 'empty', valid: false }
  };

  private static readonly FILTER_INVENTORY_DESCRIPTION = gettext(
    'The filter is applied to the inventory object that is referenced in the payload. The filter has to be defined as boolean expression (JSONata), e.g. <code>type = "lora-device-type"</code>. NOTE: Any property referenced here has to be added in Configuration > Service Configuration > Fragments from inventory to cache.'
  );

  /** The Filter Inventory field, kept so its help text can carry the "not cached" warning. */
  private filterInventoryField?: FormlyFieldConfig;

  /** Attributes of the current filter that are not in the inventory cache configuration. */
  filterInventoryUncached: string[] = [];

  private feature: Feature;
  private readonly destroy$ = new Subject<void>();

  private readonly alertService = inject(AlertService);
  private readonly translateService = inject(TranslateService);
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
    this.initializeModel();

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
        // Top right corner of the tab. Expert Mode (as in the mapping create drawer) reveals the inventory filter. A view-only
        // helper key: it is not a mapping property and never committed.
        fieldGroupClassName: 'row expert-mode-row',
        fieldGroup: [
          {
            className: 'expert-mode-corner',
            key: 'expertMode',
            type: 'switch',
            wrappers: ['d11r-wrapper-form-field'],
            props: {
              ...switchProps(gettext('Expert Mode'), ''),
              hideInlineDescription: true,
              // Never disabled: it only reveals fields, and a read-only mapping may still be inspected.
              disabled: false,
              change: () => this.syncFilterInventoryResultField()
            }
          }
        ]
      },
      {
        type: 'template',
        expressions: {
          template: () =>
            `<div class="legend form-block">${this.translateService.instant(gettext('General, topic, and filter'))}</div>`
        }
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
            props: {
              label: gettext('Mapping name'),
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
                `<div class="form-group"><label>${this.translateService.instant(gettext('Mapping and transformation type'))}</label>` +
                `<div class="m-t-4"><span class="label label-default m-r-8" data-cy="dm-mapping-properties-mapping-type">${this.mapping.mappingType ?? ''}</span>` +
                `<span class="label label-default" data-cy="dm-mapping-properties-transformation-type">${this.mapping.transformationType ?? ''}</span></div></div>`
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
            props: {
              label: gettext('Mapping topic'),
              placeholder: 'plant1/+/+',
              disabled: this.isFieldDisabled,
              description:
                gettext('The mapping topic is the key to which this mapping is bound. It is used to organize the mappings internally.'),
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
            hide: isOutbound
          },
          {
            className: 'col-lg-6',
            key: 'publishTopic',
            wrappers: ['c8y-form-field'],
            type: 'input',
            props: {
              label: gettext('Publish topic'),
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
            hide: isInbound
          },
          {
            className: 'col-lg-6',
            key: 'mappingTopicSample',
            id: 'mappingTopicSample',
            type: 'input',
            wrappers: ['c8y-form-field'],
            props: {
              label: gettext('Mapping topic sample'),
              placeholder: 'e.g. device/110',
              disabled: this.isFieldDisabled,
              description: gettext(
                'The mapping topic sample must have the same structure and number of levels as the mapping topic. Wildcards, i.e. <code>+</code> in the mapping topic are replaced with concrete runtime values. This helps to identify the relevant positions in the substitutions'
              ),
              required: true
            },
            hide: isOutbound
          },
          {
            className: 'col-lg-6',
            key: 'publishTopicSample',
            type: 'input',
            wrappers: ['c8y-form-field'],
            props: {
              label: gettext('Publish topic sample'),
              placeholder: 'e.g. device/110',
              disabled: this.isFieldDisabled,
              description: gettext(
                'The publish topic sample must have the same structure and number of levels as the publish topic. Wildcards, i.e. <code>+</code> in the publish topic are replaced with concrete runtime values. This helps to identify the relevant positions in the substitutions'
              ),
              required: true
            },
            hide: !isOutbound
          }
        ]
      },
      {
        fieldGroupClassName: 'row',
        fieldGroup: [
          {
            className: 'col-lg-6',
            key: 'filterInventory',
            type: 'input',
            // The project wrapper, not the stock c8y one: it can show the "not in the inventory
            // cache" warning under the input (props.warning).
            wrappers: ['d11r-wrapper-form-field'],
            props: {
              label: gettext('Inventory filter'),
              placeholder: 'type = "lora_device_type"',
              disabled: this.isFieldDisabled,
              description: MappingStepPropertiesComponent.FILTER_INVENTORY_DESCRIPTION,
              required: false
            },
            // Hidden outside Expert Mode; the stored expression must survive that (formly would
            // otherwise reset the value on hide).
            resetOnHide: false,
            expressions: { hide: () => !this.model.expertMode },
            hooks: {
              onInit: (field: FormlyFieldConfig) => {
                this.filterInventoryField = field;
                // An existing mapping opens with its filter already set: evaluate it right away
                // (result and cache warning) instead of only after the first edit.
                if (this.model.filterInventory) {
                  void this.updateFilterInventoryExpressionResult(this.model.filterInventory);
                } else {
                  void this.refreshFilterInventoryWarning(this.model.filterInventory);
                }
                field.formControl.valueChanges.pipe(
                  debounceTime(1500),
                  distinctUntilChanged(),
                  takeUntil(this.destroy$)
                ).subscribe(path => {
                  this.updateFilterInventoryExpressionResult(path);
                });
              }
            }
          },
          // Result of evaluating the filter against the sample device, shown beside the
          // expression. A template field shares the row's grid; syncFilterInventoryResultField()
          // updates it, because formly re-evaluates expressions on model changes only and the
          // result arrives asynchronously.
          (this.filterResultField = {
            className: 'col-lg-6',
            type: 'template',
            hide: true,
            // Must be non-empty: formly only resolves a template field's type from a truthy
            // template, and un-hiding an empty one fails with 'type "template" could not be found'.
            template: '<div></div>'
          })
        ]
      },
      {
        type: 'template',
        expressions: {
          template: () =>
            `<div class="legend form-block">${this.translateService.instant(
              isInbound ? gettext('Target and device') : gettext('Source and device')
            )}</div>`
        }
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
            props: {
              label: isInbound ? gettext('Target API') : gettext('Source API'),
              description: isOutbound
                ? gettext('Used as a filter to select which mappings apply when a notification arrives.')
                : undefined,
              options: Object.keys(API)
                .filter((key) => key !== API.ALL.name)
                .map((key) => {
                  return { label: this.translateService.instant(this.formatStringPipe.transform(key)), value: key };
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
          // Outbound only: a structured way to write "Filter execution mapping". Second column of
          // the Source API row; the expression itself is edited in the Templates step.
          {
            className: 'col-lg-2',
            key: 'executionFilterMode',
            type: 'select',
            wrappers: ['c8y-form-field'],
            props: {
              label: gettext('Execution filter'),
              description: gettext(
                'Writes the "Filter execution mapping" of the Templates step: by type (<code>type = \'...\'</code>), by fragment (<code>$exists(...)</code>), or custom (empty, enter the expression there).'
              ),
              options: [
                { label: gettext('Filter by type'), value: 'type' },
                { label: gettext('Filter by fragment'), value: 'fragment' },
                { label: gettext('Custom filter'), value: 'custom' }
              ],
              placeholder: gettext('Select a filter'),
              disabled: this.isFieldDisabled,
              required: true,
              change: () => this.onExecutionFilterChanged()
            },
            hide: !isOutbound
          },
          {
            className: 'col-lg-4',
            key: 'executionFilterValue',
            type: 'input',
            wrappers: ['c8y-form-field'],
            props: {
              disabled: this.isFieldDisabled,
              required: true,
              change: () => this.onExecutionFilterChanged()
            },
            expressions: {
              // Undefined mode: nothing chosen yet, the value field would have no meaning.
              hide: () => !isOutbound || !this.model.executionFilterMode || this.model.executionFilterMode === 'custom',
              'props.label': () =>
                this.translateService.instant(
                  this.model.executionFilterMode === 'fragment' ? gettext('Fragment') : gettext('Type')
                )
            }
          },
          // Custom filter: the expression as it is, read-only. It is edited in the Templates step.
          {
            className: 'col-lg-4',
            key: 'filterMapping',
            type: 'input',
            wrappers: ['c8y-form-field'],
            props: {
              label: gettext('Filter expression'),
              placeholder: gettext('Enter it in the Templates step'),
              readonly: true,
              required: false
            },
            expressions: {
              hide: () => !isOutbound || this.model.executionFilterMode !== 'custom'
            }
          },
          {
            className: 'col-lg-3',
            key: 'createNonExistingDevice',
            type: 'switch',
            wrappers: ['d11r-wrapper-form-field'],
            props: switchProps(
              gettext('Create device'),
              gettext(
                'In case a MEAO (Measurement, Event, Alarm, Operation) is received and the referenced device does not yet exist, it can be created automatically.'
              )
            ),
            expressions: { hide: () => isOutbound || this.model.targetAPI === API.INVENTORY.name }
          },
          {
            className: 'col-lg-3',
            key: 'updateExistingDevice',
            type: 'switch',
            wrappers: ['d11r-wrapper-form-field'],
            props: switchProps(
              gettext('Update existing device'),
              gettext('Update the managed object of the device if it already exists.')
            ),
            expressions: { hide: () => isOutbound || this.model.targetAPI !== API.INVENTORY.name }
          },
          {
            className: 'col-lg-3',
            key: 'eventWithAttachment',
            type: 'switch',
            wrappers: ['d11r-wrapper-form-field'],
            props: switchProps(
              gettext('Event contains attachment'),
              gettext('Event contains attachment, e.g. image, ... that is stored separately.')
            ),
            expressions: { hide: () => isOutbound || this.model.targetAPI !== API.EVENT.name }
          },
          {
            className: 'col-lg-3',
            key: 'autoAckOperation',
            type: 'switch',
            wrappers: ['d11r-wrapper-form-field'],
            props: switchProps(
              gettext('Auto acknowledge'),
              gettext('Auto acknowledge outbound operation.')
            ),
            expressions: { hide: () => isInbound || this.model.targetAPI !== API.OPERATION.name }
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
            props: {
              ...switchProps(
                gettext('Use external id'),
                gettext(
                  'If this is enabled then the device id is identified by its external id which is looked up and translated using the external id type.'
                )
              ),
              change: () => {
                this.model.useExternalId = !!this.propertyFormly.get('useExternalId').value;
                this.commitModel();
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
            props: {
              label: gettext('External id type'),
              disabled: this.isFieldDisabled
            },
            expressions: { hide: (field) => !field.model.useExternalId }
          }
        ]
      },
      {
        type: 'template',
        expressions: {
          template: () => `<div class="legend form-block">${this.translateService.instant(gettext('Delivery'))}</div>`
        }
      },
      {
        fieldGroupClassName: 'row',
        fieldGroup: [
          {
            className: 'col-lg-6',
            key: 'qos',
            type: 'select',
            wrappers: ['c8y-form-field'],
            props: {
              label: gettext('QoS'),
              // Labels and the per-level explanation come from the shared QoS metadata, so the
              // grid renderer and this picker can never describe a level differently.
              options: QOS_OPTIONS
                // Outbound publishing is offered at at-most-once / at-least-once only.
                .filter(
                  (option) =>
                    !isOutbound ||
                    option.value !== Qos.EXACTLY_ONCE
                )
                .map((option) => ({ label: this.translateService.instant(option.label), value: option.value })),
              disabled: this.isFieldDisabled,
              required: true
            },
            expressions: {
              // Recomputed on every change so the user sees immediately when the level they
              // picked will be clamped by one of the connectors this mapping is deployed to.
              'props.description': (field) => this.describeQos(field.model?.qos)
            }
          },
          {
            className: 'col-lg-6',
            key: 'maxFailureCount',
            type: 'input',
            wrappers: ['c8y-form-field'],
            props: {
              label: gettext('Max failure count'),
              type: 'number',
              min: 0,
              disabled: this.isFieldDisabled,
              description:
                gettext(
                  'Number of consecutive failures after which the mapping is automatically deactivated. A value of 0 means no limit. The counter is reset by the first message that is processed without an error, and when the mapping is reactivated.'
                )
            },
            validators: {
              // A negative threshold would silently behave like 0 (check disabled) instead of
              // being rejected, so catch it in the form.
              nonNegative: {
                expression: (control) =>
                  control.value === null || control.value === undefined || control.value === '' ||
                  Number(control.value) >= 0,
                message: () => this.translateService.instant(gettext('The max failure count must not be negative.'))
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

  private renderFilterInventoryResult(): string {
    const { resultType, result } = this.filterInventoryModel.filterExpression;
    const text = this.escapeHtml(String(result ?? ''));
    return (
      `<div class="form-group"><label>${this.escapeHtml(
        this.translateService.instant(gettext('Filter Inventory Result [{{resultType}}]'), { resultType: String(resultType) })
      )}</label>` +
      `<div class="filter-result font-smaller" ` +
      `data-cy="dm-mapping-properties-filter-inventory-result">${text}</div></div>`
    );
  }

  private escapeHtml(value: string): string {
    return value
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
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
      if (path && this.filterInventoryModel.filterExpression.resultType !== 'Boolean') throw Error(
          this.translateService.instant(gettext('The filter expression must return of boolean type'))
        );
      this.model.filterInventory = path;
      this.commitModel();
      await this.refreshFilterInventoryWarning(path);
    } catch (error) {
      this.filterInventoryModel.filterExpression.valid = false;
      this.propertyFormly
        .get('filterInventory')
        .setErrors({ validationError: { message: error.message } });
      this.propertyFormly.get('filterInventory').markAsTouched();
    }
    this.filterInventoryModel = { ...this.filterInventoryModel };
    this.syncFilterInventoryResultField();
  }

  private syncFilterInventoryResultField(): void {
    if (!this.filterResultField) {
      return;
    }
    this.filterResultField.template = this.renderFilterInventoryResult();
    this.filterResultField.hide =
      !this.model.expertMode || this.filterInventoryModel.filterExpression.resultType === 'empty';
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
    const message =
      attributes.length === 1
        ? gettext(
            'Not in the inventory cache: {{attributes}}. The filter evaluates as if the device did not have it. Add it under Configuration > Service Configuration > Fragments from inventory to cache.'
          )
        : gettext(
            'Not in the inventory cache: {{attributes}}. The filter evaluates as if the device did not have them. Add them under Configuration > Service Configuration > Fragments from inventory to cache.'
          );
    return this.translateService.instant(message, { attributes: attributes.join(', ') });
  }

  onTargetAPIChanged(targetAPI: string): void {
    this.model.targetAPI = targetAPI;
    this.commitModel();
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
    const rawDescription = QOS_OPTIONS.find((option) => option.value === qos)?.description;
    const description = rawDescription ? this.translateService.instant(rawDescription) : rawDescription;
    if (!description) {
      // Nothing selected yet (or an unknown value) — there is no level to warn about.
      return '';
    }
    const clamped = this.qosCapabilityPerConnector
      .filter((connector) => clampQos(qos, connector.supportedQos) !== qos)
      .map((connector) =>
        this.translateService.instant(gettext('{{name}} (handled as {{level}})'), {
          name: connector.name,
          level: this.translateService.instant(qosLabel(clampQos(qos, connector.supportedQos))).toLowerCase()
        })
      );
    return clamped.length
      ? `${description} ${this.translateService.instant(gettext('NOTE: this level is not supported by: {{connectors}}.'), {
          connectors: clamped.join(', ')
        })}`
      : description;
  }

  private initializeDirection(): void {
    // A mapping without a direction is treated as inbound; the host's mapping is not modified.
    const direction = this.mapping.direction ?? Direction.INBOUND;
    this.targetSystem = direction === Direction.INBOUND ? 'Cumulocity' : 'Broker';
    this.sourceSystem = direction === Direction.OUTBOUND ? 'Cumulocity' : 'Broker';
  }

  private initializeModel(): void {
    const model: Record<string, unknown> = {};
    for (const key of MappingStepPropertiesComponent.PROPERTY_KEYS) {
      model[key] = this.mapping[key];
    }
    // The field's own defaultValue would only reach this copy, so apply it here and commit it.
    model['externalIdType'] ??= 'c8y_Serial';
    // Show the stored filter expression as one of the three options (an unknown expression is "custom").
    // No expression yet (new mapping): nothing is chosen, the user has to pick an option.
    // An existing inventory filter must not be hidden from the user.
    model['expertMode'] = !!this.mapping.filterInventory?.trim();
    this.model = model as Partial<Mapping>;
    this.deriveExecutionFilter();
    this.commitModel();
  }

  /** The last `filterMapping` this control wrote or read; a different value in the host came from elsewhere. */
  private knownFilterMapping: string | undefined;

  /** Shows the host's `filterMapping` as one of the three options (an unknown expression is "custom"). */
  private deriveExecutionFilter(): void {
    const expression = this.mapping.filterMapping;
    const choice = fromFilterMapping(expression);
    // No expression yet (new mapping): nothing is chosen, the user has to pick an option.
    this.model.executionFilterMode = expression?.trim() ? choice.mode : undefined;
    this.model.executionFilterValue = choice.value ?? '';
    this.model.filterMapping = expression;
    this.knownFilterMapping = expression;
  }

  /**
   * The Templates step edits `filterMapping` too, and this component outlives it in the stepper.
   * Follow such a change so the control never shows (or writes back) a stale expression.
   */
  ngDoCheck(): void {
    if (this.model && this.mapping && this.mapping.filterMapping !== this.knownFilterMapping) {
      this.deriveExecutionFilter();
    }
  }

  /** Writes the execution-filter control to `filterMapping`. Only runs on user input, never on load. */
  onExecutionFilterChanged(): void {
    const mode = (this.propertyFormly.get('executionFilterMode')?.value ?? this.model.executionFilterMode) as ExecutionFilterMode;
    const value = this.propertyFormly.get('executionFilterValue')?.value ?? this.model.executionFilterValue;
    this.model.executionFilterMode = mode;
    this.model.executionFilterValue = value;
    const expression = toFilterMapping({ mode, value });
    this.model.filterMapping = expression;
    this.knownFilterMapping = expression;
    // Not part of commitModel: the Templates step also owns this property.
    if (expression !== this.mapping.filterMapping) {
      this.propertiesChange.emit({ filterMapping: expression });
    }
    this.commitModel();
  }

  /** Emits the properties that differ from the host's mapping. Also called on every form change. */
  commitModel(): void {
    const patch: Record<string, unknown> = {};
    for (const key of MappingStepPropertiesComponent.PROPERTY_KEYS) {
      if (this.model[key] !== this.mapping[key]) {
        patch[key] = this.model[key];
      }
    }
    if (Object.keys(patch).length > 0) {
      this.propertiesChange.emit(patch as Partial<Mapping>);
    }
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
