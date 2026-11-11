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
  ChangeDetectorRef,
  ChangeDetectionStrategy,
  Component,
  EventEmitter,
  effect,
  inject,
  OnDestroy,
  OnInit,
  Output,
  untracked,
  ViewEncapsulation, input } from '@angular/core';
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
  JsonValue,
  QOS_OPTIONS,
  SharedService,
  StepperConfiguration
} from '../../shared';
import { ConnectorConfigurationService } from '../../shared/service/connector-configuration.service';
import { MappingService } from '../core/mapping.service';
import { MappingStepperService } from '../service/mapping-stepper.service';
import { buildPropertyFields, PropertyFormModel } from './property-form-fields';
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
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CoreModule, PopoverModule]
})
export class MappingStepPropertiesComponent implements OnInit, OnDestroy {
  readonly mapping = input<Mapping>();
  readonly stepperConfiguration = input<StepperConfiguration>();
  readonly propertyFormly = input<FormGroup>();
  /**
   * The connectors this mapping is deployed to. Used to tell the user when the QoS they picked
   * cannot be honoured by one of those connectors — the backend clamps it silently otherwise.
   */
  readonly deploymentMapEntry = input<DeploymentMapEntry>();
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
  model: PropertyFormModel = {};

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
  private readonly cdr = inject(ChangeDetectorRef);
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

    // The host object below exposes live members through getters, so it needs the component under another name.
    const self = this;
    this.propertyFormlyFields = buildPropertyFields({
      direction: this.stepperConfiguration().direction,
      isFieldDisabled: this.isFieldDisabled,
      filterInventoryDescription: MappingStepPropertiesComponent.FILTER_INVENTORY_DESCRIPTION,
      get mapping() { return self.mapping(); },
      get model() { return self.model; },
      get form() { return self.propertyFormly(); },
      destroy$: this.destroy$,
      get filterInventoryField() { return self.filterInventoryField; },
      set filterInventoryField(field) { self.filterInventoryField = field; },
      get filterResultField() { return self.filterResultField; },
      set filterResultField(field) { self.filterResultField = field; },
      translate: (key, params) => self.translateService.instant(key, params),
      formatKey: key => self.formatStringPipe.transform(key),
      syncFilterInventoryResultField: () => self.syncFilterInventoryResultField(),
      updateFilterInventoryExpressionResult: path => self.updateFilterInventoryExpressionResult(path),
      refreshFilterInventoryWarning: path => self.refreshFilterInventoryWarning(path),
      onTargetAPIChanged: targetAPI => self.onTargetAPIChanged(targetAPI),
      onExecutionFilterChanged: () => self.onExecutionFilterChanged(),
      commitModel: () => self.commitModel(),
      notifyMappingPropertyChanged: mapping => self.stepperService.notifyMappingPropertyChanged(mapping),
      describeQos: qos => self.describeQos(qos as Qos)
    });
    // OnPush: the form fields and models above are built after awaits (features, QoS capabilities), so no
    // event of this view announces them.
    this.cdr.markForCheck();
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
      const resultExpression: JsonValue = await this.mappingService.evaluateExpression(
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
      this.propertyFormly()
        .get('filterInventory')
        .setErrors({ validationError: { message: error.message } });
      this.propertyFormly().get('filterInventory').markAsTouched();
    }
    this.filterInventoryModel = { ...this.filterInventoryModel };
    this.syncFilterInventoryResultField();
    // OnPush: the result arrives after an await (debounced input), outside any event of this view.
    this.cdr.markForCheck();
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
    this.cdr.markForCheck();
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
    const identifiers = this.deploymentMapEntry()?.connectors ?? [];
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
    const direction = this.mapping().direction ?? Direction.INBOUND;
    this.targetSystem = direction === Direction.INBOUND ? 'Cumulocity' : 'Broker';
    this.sourceSystem = direction === Direction.OUTBOUND ? 'Cumulocity' : 'Broker';
  }

  private initializeModel(): void {
    const model: Record<string, unknown> = {};
    for (const key of MappingStepPropertiesComponent.PROPERTY_KEYS) {
      model[key] = this.mapping()[key];
    }
    // The field's own defaultValue would only reach this copy, so apply it here and commit it.
    model['externalIdType'] ??= 'c8y_Serial';
    // Show the stored filter expression as one of the three options (an unknown expression is "custom").
    // No expression yet (new mapping): nothing is chosen, the user has to pick an option.
    // An existing inventory filter must not be hidden from the user.
    model['expertMode'] = !!this.mapping().filterInventory?.trim();
    this.model = model as Partial<Mapping>;
    this.deriveExecutionFilter();
    this.commitModel();
  }

  /** The last `filterMapping` this control wrote or read; a different value in the host came from elsewhere. */
  private knownFilterMapping: string | undefined;

  /** Shows the host's `filterMapping` as one of the three options (an unknown expression is "custom"). */
  private deriveExecutionFilter(): void {
    const expression = this.mapping().filterMapping;
    const choice = fromFilterMapping(expression);
    // No expression yet (new mapping): nothing is chosen, the user has to pick an option.
    this.model.executionFilterMode = expression?.trim() ? choice.mode : undefined;
    this.model.executionFilterValue = choice.value ?? '';
    this.model.filterMapping = expression;
    this.knownFilterMapping = expression;
  }

  /**
   * The Templates step edits `filterMapping` too, and this component outlives it in the stepper.
   * The host replaces its mapping object on every edit, so a new `mapping` input is the signal to
   * follow such a change and keep the control from showing (or writing back) a stale expression.
   */
  constructor() {
    effect(() => {
      this.mapping();
      untracked(() => this.onMappingReplaced());
    });
  }

  /** Re-derives the execution filter when the host bound a mapping whose `filterMapping` this step does not know. */
  onMappingReplaced(): void {
    const mapping = this.mapping();
    if (this.model && mapping && mapping.filterMapping !== this.knownFilterMapping) {
      this.deriveExecutionFilter();
    }
  }

  /** Writes the execution-filter control to `filterMapping`. Only runs on user input, never on load. */
  onExecutionFilterChanged(): void {
    const mode = (this.propertyFormly().get('executionFilterMode')?.value ?? this.model.executionFilterMode) as ExecutionFilterMode;
    const value = this.propertyFormly().get('executionFilterValue')?.value ?? this.model.executionFilterValue;
    this.model.executionFilterMode = mode;
    this.model.executionFilterValue = value;
    const expression = toFilterMapping({ mode, value });
    this.model.filterMapping = expression;
    this.knownFilterMapping = expression;
    // Not part of commitModel: the Templates step also owns this property.
    if (expression !== this.mapping().filterMapping) {
      this.propertiesChange.emit({ filterMapping: expression });
    }
    this.commitModel();
  }

  /** Emits the properties that differ from the host's mapping. Also called on every form change. */
  commitModel(): void {
    const patch: Record<string, unknown> = {};
    for (const key of MappingStepPropertiesComponent.PROPERTY_KEYS) {
      if (this.model[key] !== this.mapping()[key]) {
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
    return !!this.stepperConfiguration().readOnly;
  }

  private get canEdit(): boolean {
    return this.feature?.userHasMappingAdminRole || this.feature?.userHasMappingCreateRole;
  }

  private get isFieldDisabled(): boolean {
    return this.isReadOnly || !this.canEdit;
  }
}
