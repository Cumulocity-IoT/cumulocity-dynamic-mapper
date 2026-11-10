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
import { FormGroup } from '@angular/forms';
import { gettext } from '@c8y/ngx-components/gettext';
import { FormlyFieldConfig } from '@ngx-formly/core';
import { debounceTime, distinctUntilChanged, Observable, takeUntil } from 'rxjs';
import { API, Direction, Mapping, Qos, QOS_OPTIONS } from '../../shared';
import { ExecutionFilterMode } from './execution-filter.model';
import { deriveSampleTopicFromTopic, topicsHaveSameStructure } from '../../shared/mapping/util';

/** The form's model: the mapping's properties plus view-only helper keys that are never committed. */
export type PropertyFormModel = Partial<Mapping> & {
  executionFilterMode?: ExecutionFilterMode;
  executionFilterValue?: string;
  expertMode?: boolean;
};

/**
 * What the properties form needs from its step. The form is built once, and its handlers run later, so everything that
 * changes (the model, the form, the mapping) is a getter and is read when the handler runs.
 */
export interface PropertyFieldsHost {
  readonly direction: Direction | undefined;
  readonly isFieldDisabled: boolean;
  readonly filterInventoryDescription: string;
  readonly mapping: Mapping;
  readonly model: PropertyFormModel;
  readonly form: FormGroup;
  readonly destroy$: Observable<void>;
  filterInventoryField: FormlyFieldConfig | undefined;
  filterResultField: FormlyFieldConfig | undefined;
  translate(key: string, params?: object): string;
  formatKey(key: string): string;
  syncFilterInventoryResultField(): void;
  updateFilterInventoryExpressionResult(path: string): Promise<void> | void;
  refreshFilterInventoryWarning(path: string): Promise<void> | void;
  onTargetAPIChanged(targetAPI: string): void;
  onExecutionFilterChanged(): void;
  commitModel(): void;
  notifyMappingPropertyChanged(mapping: Mapping): void;
  describeQos(qos: unknown): string;
}

/** The Formly field configuration of the Properties step. */
export function buildPropertyFields(host: PropertyFieldsHost): FormlyFieldConfig[] {
  const isInbound = host.direction === Direction.INBOUND;
  const isOutbound = host.direction === Direction.OUTBOUND;
  // Switches share this shape; hideLabel puts the label next to the toggle.
  const switchProps = (label: string, description: string) => ({
    label,
    description,
    disabled: host.isFieldDisabled,
    required: false,
    switchMode: true,
    indeterminate: false,
    hideLabel: true,
    // The switch already offers the description as a help popover; no second copy below it.
    hideInlineDescription: true
  });

  return [
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
            change: () => host.syncFilterInventoryResultField()
          }
        }
      ]
    },
    {
      type: 'template',
      expressions: {
        template: () =>
          `<div class="legend form-block">${host.translate(gettext('General, topic, and filter'))}</div>`
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
            disabled: host.isFieldDisabled,
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
              `<div class="form-group"><label>${host.translate(gettext('Mapping and transformation type'))}</label>` +
              `<div class="m-t-4"><span class="label label-default m-r-8" data-cy="dm-mapping-properties-mapping-type">${host.mapping.mappingType ?? ''}</span>` +
              `<span class="label label-default" data-cy="dm-mapping-properties-transformation-type">${host.mapping.transformationType ?? ''}</span></div></div>`
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
            disabled: host.isFieldDisabled,
            description:
              gettext('The mapping topic is the key to which this mapping is bound. It is used to organize the mappings internally.'),
            change: () => {
              const newTopic = host.form.get('mappingTopic').value;
              const sampleControl = host.form.get('mappingTopicSample');
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
            disabled: host.isFieldDisabled,
            change: () => {
              const newTopic = host.form.get('publishTopic').value;
              const sampleControl = host.form.get('publishTopicSample');
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
            disabled: host.isFieldDisabled,
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
            disabled: host.isFieldDisabled,
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
            disabled: host.isFieldDisabled,
            description: host.filterInventoryDescription,
            required: false
          },
          // Hidden outside Expert Mode; the stored expression must survive that (formly would
          // otherwise reset the value on hide).
          resetOnHide: false,
          expressions: { hide: () => !host.model.expertMode },
          hooks: {
            onInit: (field: FormlyFieldConfig) => {
              host.filterInventoryField = field;
              // An existing mapping opens with its filter already set: evaluate it right away
              // (result and cache warning) instead of only after the first edit.
              if (host.model.filterInventory) {
                void host.updateFilterInventoryExpressionResult(host.model.filterInventory);
              } else {
                void host.refreshFilterInventoryWarning(host.model.filterInventory);
              }
              field.formControl.valueChanges.pipe(
                debounceTime(1500),
                distinctUntilChanged(),
                takeUntil(host.destroy$)
              ).subscribe(path => {
                host.updateFilterInventoryExpressionResult(path);
              });
            }
          }
        },
        // Result of evaluating the filter against the sample device, shown beside the
        // expression. A template field shares the row's grid; syncFilterInventoryResultField()
        // updates it, because formly re-evaluates expressions on model changes only and the
        // result arrives asynchronously.
        (host.filterResultField = {
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
          `<div class="legend form-block">${host.translate(
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
                return { label: host.translate(host.formatKey(key)), value: key };
              }),
            disabled: host.isFieldDisabled,
            change: () => {
              host.onTargetAPIChanged(
                host.form.get('targetAPI').value
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
            disabled: host.isFieldDisabled,
            required: true,
            change: () => host.onExecutionFilterChanged()
          },
          hide: !isOutbound
        },
        {
          className: 'col-lg-4',
          key: 'executionFilterValue',
          type: 'input',
          wrappers: ['c8y-form-field'],
          props: {
            disabled: host.isFieldDisabled,
            required: true,
            change: () => host.onExecutionFilterChanged()
          },
          expressions: {
            // Undefined mode: nothing chosen yet, the value field would have no meaning.
            hide: () => !isOutbound || !host.model.executionFilterMode || host.model.executionFilterMode === 'custom',
            'props.label': () =>
              host.translate(
                host.model.executionFilterMode === 'fragment' ? gettext('Fragment') : gettext('Type')
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
            hide: () => !isOutbound || host.model.executionFilterMode !== 'custom'
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
          expressions: { hide: () => isOutbound || host.model.targetAPI === API.INVENTORY.name }
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
          expressions: { hide: () => isOutbound || host.model.targetAPI !== API.INVENTORY.name }
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
          expressions: { hide: () => isOutbound || host.model.targetAPI !== API.EVENT.name }
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
          expressions: { hide: () => isInbound || host.model.targetAPI !== API.OPERATION.name }
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
              host.model.useExternalId = !!host.form.get('useExternalId').value;
              host.commitModel();
              host.notifyMappingPropertyChanged(host.mapping);
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
            disabled: host.isFieldDisabled
          },
          expressions: { hide: (field) => !field.model.useExternalId }
        }
      ]
    },
    {
      type: 'template',
      expressions: {
        template: () => `<div class="legend form-block">${host.translate(gettext('Delivery'))}</div>`
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
              .map((option) => ({ label: host.translate(option.label), value: option.value })),
            disabled: host.isFieldDisabled,
            required: true
          },
          expressions: {
            // Recomputed on every change so the user sees immediately when the level they
            // picked will be clamped by one of the connectors this mapping is deployed to.
            'props.description': (field) => host.describeQos(field.model?.qos)
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
            disabled: host.isFieldDisabled,
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
              message: () => host.translate(gettext('The max failure count must not be negative.'))
            }
          }
        }
      ]
    }
  ];
}
