/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *  @authors Christof Strack, Stefan Witschel
 *
 */

// mapping-substitution-step.component.ts
import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  Output,
  EventEmitter,
  ViewChild,
  OnDestroy,
  OnInit,
  inject, viewChild, input, effect, untracked, Injector } from '@angular/core';
import { afterNextRenderInZone } from '../../shared/misc/after-render';
import { stringifyJSONPathCustom } from '../../shared/component/json-editor/util';
import { FormGroup, PristineChangeEvent } from '@angular/forms';
import {
  AlertService,
  BottomDrawerService,
  CoreModule,
  SplitViewComponent,
  SplitViewDetailsComponent,
  SplitViewFooterComponent,
  SplitViewHeaderActionsComponent,
  SplitViewListComponent,
  SplitViewAlertsComponent
} from '@c8y/ngx-components';
import { gettext } from '@c8y/ngx-components/gettext';
import { TranslateService } from '@ngx-translate/core';
import { FormlyFieldConfig } from '@ngx-formly/core';
import { debounceTime, filter, Observable, Subject, takeUntil, tap } from 'rxjs';
import {
  COLOR_HIGHLIGHTED,
  Direction,
  JsonEditorComponent,
  Mapping,
  StepperConfiguration,
  Feature,
  isSubstitutionsAsCode,
  JsonSchema,
  RepairStrategy,
  Substitution,
  Template
} from '../../shared';
import { SubstitutionModel } from '../../shared/mapping/stepper.model';
import { SubstitutionGridComponent } from '../substitution/substitution-grid.component';
import { AiPromptComponent } from '../prompt/ai-prompt.component';
import { AgentObjectDefinition, AgentTextDefinition, MappingForAiDrawer } from '../../shared/mapping/ai-prompt.model';
import { MappingStepperService } from '../service/mapping-stepper.service';
import { SubstitutionManagementService, SubstitutionsUpdate } from '../service/substitution-management.service';
import { isExpression } from '../../shared/mapping/util';
import { CommonModule } from '@angular/common';
import { PopoverModule } from 'ngx-bootstrap/popover';
import { formatStringAsWords } from '../../shared/misc/format-string.pipe';
import { ARRAY_ONLY_STRATEGIES, REPAIR_STRATEGY_DESCRIPTIONS } from '../substitution/repair-strategy';

interface RepairStrategyOption {
  value: string;
  label: string;
  description: string;
  disabled: boolean;
}

/**
 * Path of the first value in a template: depth first, the keys starting with `_` (helper nodes) last.
 * Empty objects and arrays hold no value and are skipped. Without any value outside the helper nodes,
 * the first top-level key is used.
 */
export function firstLeafPath(template: unknown): string[] | undefined {
  if (!template || typeof template !== 'object' || Array.isArray(template)) return undefined;
  const find = (value: unknown, path: string[]): string[] | undefined => {
    if (value === null || typeof value !== 'object') return path;
    const entries: [string, unknown][] = Array.isArray(value)
      ? value.map((v, i): [string, unknown] => [String(i), v])
      : Object.entries(value);
    for (const [key, child] of entries) {
      const found = find(child, [...path, key]);
      if (found) return found;
    }
    return undefined;
  };
  const keys = Object.keys(template);
  for (const key of keys.filter(k => !k.startsWith('_'))) {
    const found = find((template as Record<string, unknown>)[key], [key]);
    if (found) return found;
  }
  return keys.length ? [keys[0]] : undefined;
}

@Component({
  selector: 'd11r-mapping-transformation-step',
  templateUrl: './mapping-substitution-step.component.html',
  styleUrls: ['../../shared/mapping/mapping.style.css', './substitution-editor.style.css'],
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    CoreModule, CommonModule, PopoverModule, JsonEditorComponent, SubstitutionGridComponent,
    SplitViewComponent, SplitViewListComponent, SplitViewDetailsComponent, SplitViewHeaderActionsComponent,
    SplitViewFooterComponent, SplitViewAlertsComponent
  ]
})
export class MappingSubstitutionStepComponent implements OnInit, OnDestroy {
  readonly mapping = input<Mapping>();
  readonly stepperConfiguration = input<StepperConfiguration>();
  private readonly injector = inject(Injector);
  readonly sourceTemplate = input<Template | undefined>();
  readonly targetTemplate = input<Template | undefined>();
  readonly sourceSystem = input<string>();
  readonly targetSystem = input<string>();
  readonly feature = input<Feature>();
  readonly aiAgentDeployed = input<boolean>();
  readonly aiCapabilitiesDisabled = input(false);
  readonly aiAgent = input<AgentObjectDefinition | AgentTextDefinition | null>();
  readonly schemaSource = input<JsonSchema | undefined>();
  readonly schemaTarget = input<JsonSchema | undefined>();
  readonly mappingCode = input<string>();
  readonly codeEditorLabel = input<string>();
  readonly codeEditorHelp = input<string>();
  // A boolean, not the raw step/tab index: this component is shared by both the stepper and the
  // unified editor, which number their steps/tabs independently — comparing a bare index here
  // would silently break if either caller's ordering changes without both staying in sync.
  readonly isBeforeSubstitutionStep = input<boolean>();

  @Output() mappingCodeChange = new EventEmitter<string>();
  /**
   * The substitution list after the user changed it. The host owns the mapping and applies it:
   * this step reads `mapping.substitutions` but never writes it.
   */
  @Output() substitutionsChange = new EventEmitter<Substitution[]>();

  readonly splitView = viewChild<SplitViewComponent<number>>('splitView');

  readonly editorSourceStepSubstitution = viewChild<JsonEditorComponent>('editorSourceStepSubstitution');

  readonly editorTargetStepSubstitution = viewChild<JsonEditorComponent>('editorTargetStepSubstitution');

  private alertService = inject(AlertService);
  private readonly cdr = inject(ChangeDetectorRef);
  private translateService = inject(TranslateService);
  private bottomDrawerService = inject(BottomDrawerService);
  private stepperService = inject(MappingStepperService);
  private substitutionService = inject(SubstitutionManagementService);

  private readonly destroy$ = new Subject<void>();

  readonly COLOR_HIGHLIGHTED = COLOR_HIGHLIGHTED;
  readonly Direction = Direction;

  get identitySubstitutionHint(): string {
    const side = this.mapping()?.direction === Direction.OUTBOUND ? 'source' : 'target';
    return `One substitution with ${side} <code class="text-warning text-10">_IDENTITY_.externalId</code>`
      + ` or <code class="text-warning text-10">_IDENTITY_.c8ySourceId</code> must exist.`;
  }

  templateForm: FormGroup = new FormGroup({});
  substitutionFormly: FormGroup = new FormGroup({});
  substitutionFormlyFieldsSource: FormlyFieldConfig[];
  substitutionFormlyFieldsTarget: FormlyFieldConfig[];
  substitutionModel: SubstitutionModel = {};
  selectedSubstitution: number = -1;
  readonly isExpression = isExpression;
  expandArrayHelp = gettext(
    'If the source expression returns an array:<ul class="m-b-0 p-l-16"><li><strong>On:</strong> creates one request per array element, using the same target template for each (for example several measurements in one payload).</li><li><strong>Off:</strong> writes the whole array to the target as it is, or reduces it to one element with the repair strategy <code>Use First Value Of Array</code> or <code>Use Last Value Of Array</code>.</li></ul>'
  );
  targetTemplateHelp = gettext('The template contains the dummy field <code>_TOPIC_LEVEL_</code>...');

  /** Help shown next to the disabled AI button. */
  get aiDisabledHelp(): string {
    return this.aiCapabilitiesDisabled()
      ? gettext('AI capabilities are disabled in this tenant.')
      : gettext('AI Assistant is disabled if AI Agent is not deployed in this tenant or no agent is defined.');
  }

  isSubstitutionValid$: Observable<boolean>;
  sourceCustomMessage$: Observable<string>;
  targetCustomMessage$: Observable<string>;

  editorOptionsSourceSubstitution = {
    mode: 'tree' as const,
    removeModes: ['text', 'table'],
    mainMenuBar: true,
    navigationBar: false,
    statusBar: false,
    readOnly: true,
    name: 'message'
  };

  editorOptionsTargetSubstitution = {
    mode: 'tree' as const,
    removeModes: ['text', 'table'],
    mainMenuBar: true,
    navigationBar: false,
    readOnly: true,
    statusBar: true
  };

  constructor() {
    // Whenever the host hands in a changed mapping (an edit applied, templates expanded, another step
    // changed it), the rule "a device identifier substitution must exist" is checked again.
    effect(() => {
      const mapping = this.mapping();
      this.isBeforeSubstitutionStep();
      if (!mapping) return;
      untracked(() => this.refreshSubstitutionValidity(mapping));
    });
    effect(() => {
      if (!this.sourceTemplate()) return;
      untracked(() => afterNextRenderInZone(this.injector, () => this.selectFirstSourceNode()));
    });
  }

  ngOnInit(): void {
    this.isSubstitutionValid$ = this.stepperService.isSubstitutionValid$;
    this.sourceCustomMessage$ = this.stepperService.sourceCustomMessage$;
    this.targetCustomMessage$ = this.stepperService.targetCustomMessage$;

    this.initializeSubstitutionModel();
    this.initializeFormlyFields();
    this.updateEditorPermissions();
  }

  /** Set once the first source node was picked, or when it is not wanted (substitutions exist, code mode). */
  private firstNodeHandled = false;

  /**
   * Picks the first value of the source template when the mapping has no substitution yet: it shows how
   * the template, the substitutions and the source expression belong together. Helper nodes the mapper
   * adds itself (`_TOPIC_LEVEL_`, `_IDENTITY_`, `_CONTEXT_DATA_`) are skipped, unless nothing else exists.
   * The template can arrive after the step opened (wizard), so this runs again when it changes, until it
   * has found a node.
   */
  private async selectFirstSourceNode(): Promise<void> {
    if (this.firstNodeHandled) return;
    if (!this.stepperConfiguration().showEditorSource || this.stepperConfiguration().showCodeEditor ||
      this.mapping()?.substitutions?.length || this.selectedSubstitution !== -1) {
      this.firstNodeHandled = true;
      return;
    }
    const editor = this.editorSourceStepSubstitution();
    const path = firstLeafPath(this.sourceTemplate());
    if (!editor || !path) return;
    this.firstNodeHandled = true;
    await editor.setSelectionToPath(stringifyJSONPathCustom(path));
    this.cdr.markForCheck();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  /**
   * The expression field mirrors a node picked in a template tree (by click, or by selecting a row). It is
   * tinted like the node, until the user edits the field by hand.
   */
  sourceLinked = false;
  targetLinked = false;

  /** Last path each field evaluated; reset together with the form so the same node can be picked again. */
  private lastEvaluatedPath: { source?: string; target?: string } = {};

  private initializeSubstitutionModel(): void {
    this.substitutionModel = {
      stepperConfiguration: this.stepperConfiguration(),
      pathSource: '',
      pathTarget: '',
      pathSourceIsExpression: false,
      pathTargetIsExpression: false,
      repairStrategy: RepairStrategy.DEFAULT,
      expandArray: false,
      targetExpression: { result: '', resultType: 'empty', valid: false },
      sourceExpression: { result: '', resultType: 'empty', valid: false }
    };
  }

  private static readonly JSONATA_DESCRIPTION = `Use <a href="https://jsonata.org" target="_blank">JSONata</a>
          in your expressions:
          <ol>
            <li>to convert a UNIX timestamp to ISO date format use:
              <code>$fromMillis($number(deviceTimestamp))</code>
            </li>
            <li>to concat strings use "&"
            </li>
            <li>to join substring starting at position 5 of property <code>txt</code> with
              device
              identifier use: <code>$join([$substring(txt,5), "-", id])</code></li>
            <li>function chaining using <code>~</code> is supported. The expression <code>Account.Product.(Price * Quantity) ~> $sum()</code>
              becomes <code>$sum(Account.Product.(Price * Quantity))</code></li>
          </ol>`;

  private initializeFormlyFields(): void {
    this.substitutionFormlyFieldsSource = [
      {
        key: 'pathSource',
        type: 'd11r-jsonata-input',
        wrappers: ['c8y-form-field'],

        props: {
          label: gettext('Source Expression'),
          class: 'input-sm',
          disabled: this.stepperConfiguration().readOnly ||
            !this.stepperConfiguration().allowDefiningSubstitutions,
          placeholder: '$join([$substring(txt,5), id]) or $number(id)/10',
          // Offers the property names of the source template as completion.
          templateProvider: () => this.editorSourceStepSubstitution()?.get(),
          description: MappingSubstitutionStepComponent.JSONATA_DESCRIPTION,
          required: true,
          customMessage: this.sourceCustomMessage$
        },
        hooks: {
          onInit: (field: FormlyFieldConfig) => {
            field.formControl.valueChanges.pipe(
              debounceTime(500),
              // Not distinctUntilChanged(): cancelEditSubstitution() resets the control silently, so the
              // operator would still remember the old path and swallow picking that same node again.
              filter(path => path !== this.lastEvaluatedPath.source),
              tap(path => (this.lastEvaluatedPath.source = path)),
              takeUntil(this.destroy$)
            ).subscribe(path => this.updateSourceExpressionResult(path));
            // The JSONata input marks the control dirty only for text the user typed; the tree sets it programmatically.
            field.formControl.events.pipe(
              filter(event => event instanceof PristineChangeEvent && !event.pristine),
              takeUntil(this.destroy$)
            ).subscribe(() => this.onManualEdit('source'));
          }
        }
      }
    ];
    this.substitutionFormlyFieldsTarget = [
      {
        key: 'pathTarget',
        type: 'd11r-jsonata-input',
        wrappers: ['c8y-form-field'],
        props: {
          label: gettext('Target Expression'),
          class: 'input-sm',
          disabled: this.stepperConfiguration().readOnly ||
            !this.stepperConfiguration().allowDefiningSubstitutions,
          placeholder: 'c8y_Temperature.T.value',
          templateProvider: () => this.editorTargetStepSubstitution()?.get(),
          description: MappingSubstitutionStepComponent.JSONATA_DESCRIPTION,
          required: true,
          customMessage: this.targetCustomMessage$
        },
        hooks: {
          onInit: (field: FormlyFieldConfig) => {
            field.formControl.valueChanges.pipe(
              debounceTime(500),
              // Not distinctUntilChanged(): cancelEditSubstitution() resets the control silently, so the
              // operator would still remember the old path and swallow picking that same node again.
              filter(path => path !== this.lastEvaluatedPath.target),
              tap(path => (this.lastEvaluatedPath.target = path)),
              takeUntil(this.destroy$)
            ).subscribe(path => this.updateTargetExpressionResult(path));
            // The JSONata input marks the control dirty only for text the user typed; the tree sets it programmatically.
            field.formControl.events.pipe(
              filter(event => event instanceof PristineChangeEvent && !event.pristine),
              takeUntil(this.destroy$)
            ).subscribe(() => this.onManualEdit('target'));
          }
        }
      }
    ];
  }

  private updateEditorPermissions(): void {
    if (!this.feature()?.userHasMappingAdminRole && !this.feature()?.userHasMappingCreateRole) {
      this.editorOptionsSourceSubstitution.readOnly = true;
      this.editorOptionsTargetSubstitution.readOnly = true;
    }
  }

  /**
   * The source expression yields an array but "Expand as array" is off: shown as a hint, not a toast.
   * Not while the expression is invalid: the field's own error message occupies the same space and
   * the model then still holds the previous, stale result.
   */
  get showExpandArrayHint(): boolean {
    const source = this.substitutionModel?.sourceExpression;
    return source?.resultType === 'Array' && source.valid !== false && !this.substitutionModel.expandArray;
  }

  async updateSourceExpressionResult(path: string): Promise<void> {
    try {
      const result = await this.stepperService.evaluateSourceExpression(
        this.editorSourceStepSubstitution()?.get(),
        path
      );
      this.substitutionModel.sourceExpression = result;
      this.substitutionModel.pathSourceIsExpression = isExpression(this.substitutionModel.pathSource);
      this.substitutionFormly.get('pathSource').setErrors(null);
    } catch (error) {
      // sourceExpression can still be unset here (e.g. right after selecting an existing
      // substitution, whose persisted form never carries it) - default it rather than assume it.
      this.substitutionModel.sourceExpression = {
        ...(this.substitutionModel.sourceExpression ?? { result: '', resultType: 'empty' }),
        valid: false
      };
      this.substitutionFormly.get('pathSource').setErrors({
        validationError: { message: error.message }
      });
    }

    this.substitutionModel = { ...this.substitutionModel };
    // OnPush: the result arrives after an await (debounced input, onSelectSubstitution, or a host call).
    this.cdr.markForCheck();
  }

  async updateTargetExpressionResult(path: string): Promise<void> {
    try {
      const result = await this.stepperService.evaluateTargetExpression(
        this.editorTargetStepSubstitution()?.get(),
        path
      );
      this.substitutionModel.targetExpression = result;
      this.substitutionFormly.get('pathTarget').setErrors(null);

      if (path == '$') {
        this.stepperService.setTargetCustomMessage(
          this.translateService.instant(gettext('By specifying "$" you selected the root of the target template...'))
        );
      }
    } catch (error) {
      this.substitutionModel.targetExpression = {
        ...(this.substitutionModel.targetExpression ?? { result: '', resultType: 'empty' }),
        valid: false
      };
      this.substitutionFormly.get('pathTarget').setErrors({
        validationError: { message: error.message }
      });
    }

    this.substitutionModel = { ...this.substitutionModel };
    // OnPush: the result arrives after an await (debounced input, onSelectSubstitution, or a host call).
    this.cdr.markForCheck();
  }

  /** Typing in an expression field cuts its link to the tree: the tint and the node highlight go. */
  onManualEdit(key: 'source' | 'target'): void {
    if (key === 'source') {
      if (!this.sourceLinked) return;
      this.sourceLinked = false;
      this.editorSourceStepSubstitution()?.clearSelection();
    } else {
      if (!this.targetLinked) return;
      this.targetLinked = false;
      this.editorTargetStepSubstitution()?.clearSelection();
    }
    this.cdr.markForCheck();
  }

  onSelectedPathSourceChanged(path: string): void {
    this.sourceLinked = true;
    this.substitutionFormly.get('pathSource').setValue(path);
    this.substitutionFormly.get('pathSource').markAsPristine();
    this.cdr.markForCheck();
    this.substitutionModel.pathSource = path;
    this.substitutionModel.pathSourceIsExpression = isExpression(path);
  }

  onSelectedPathTargetChanged(path: string): void {
    this.targetLinked = true;
    this.substitutionFormly.get('pathTarget').setValue(path);
    this.substitutionFormly.get('pathTarget').markAsPristine();
    this.cdr.markForCheck();
    this.substitutionModel.pathTarget = path;
  }

  /**
   * A field with a validation message shows it right under the input, in the space the preview uses.
   * The preview then moves below the message. Follows the flag the field wrapper shows the message
   * with (Formly's `showError`), so the preview does not move before the message appears: a required
   * field is invalid on load, but its message only shows once the user has interacted.
   */
  hasFieldError(key: 'pathSource' | 'pathTarget'): boolean {
    // `showError` is set on the field by Formly's core, but is not part of the public FormlyFieldConfig type.
    const field = (key === 'pathSource' ? this.substitutionFormlyFieldsSource : this.substitutionFormlyFieldsTarget)?.[0] as
      | (FormlyFieldConfig & { showError?: boolean })
      | undefined;
    if (field && typeof field.showError === 'boolean') {
      return field.showError;
    }
    const control = this.substitutionFormly?.get(key);
    return !!control?.invalid && !!(control.dirty || control.touched);
  }

  /** Header of the editor: which substitution is being edited, or that a new one is being defined. */
  get detailsTitle(): string {
    return this.selectedSubstitution === -1
      ? this.translateService.instant(gettext('New substitution'))
      : this.translateService.instant(gettext('Substitution {{number}}'), { number: this.selectedSubstitution + 1 });
  }

  /** Outbound mappings produce exactly one target message, so there is nothing to expand into. */
  get expandArrayDisabled(): boolean {
    return !!this.stepperConfiguration()?.readOnly || this.mapping()?.direction === Direction.OUTBOUND;
  }

  onExpandArrayChange(expandArray: boolean): void {
    this.substitutionModel.expandArray = expandArray;
    // USE_FIRST/LAST_VALUE_OF_ARRAY collapse an array to one element: meaningless once it is expanded.
    if (expandArray && ARRAY_ONLY_STRATEGIES.includes(this.substitutionModel.repairStrategy)) {
      this.substitutionModel.repairStrategy = RepairStrategy.DEFAULT;
    }
    this.substitutionModel = { ...this.substitutionModel };
  }

  onRepairStrategyChange(repairStrategy: string): void {
    this.substitutionModel = { ...this.substitutionModel, repairStrategy: repairStrategy as RepairStrategy };
  }

  /** Only the language and `expandArray` change the options, so they are built once per combination. */
  private readonly repairStrategyOptionsCache = new Map<string, RepairStrategyOption[]>();

  repairStrategyOptions(): RepairStrategyOption[] {
    const expand = !!this.substitutionModel.expandArray;
    const key = `${this.translateService.getCurrentLang?.() ?? ''}|${expand}`;
    let options = this.repairStrategyOptionsCache.get(key);
    if (!options) {
      options = Object.keys(RepairStrategy).map(strategy => ({
        value: strategy,
        label: formatStringAsWords(strategy),
        description: this.describeStrategy(strategy),
        disabled: ARRAY_ONLY_STRATEGIES.includes(strategy) && expand
      }));
      this.repairStrategyOptionsCache.set(key, options);
    }
    return options;
  }

  describeStrategy(strategy: string | undefined): string {
    const description = strategy ? REPAIR_STRATEGY_DESCRIPTIONS[strategy] : undefined;
    return description ? this.translateService.instant(description) : '';
  }

  /** The one primary action: adds a new substitution, or saves the selected one. */
  onSaveSubstitution(): void {
    if (this.selectedSubstitution === -1) {
      this.onAddSubstitution();
    } else {
      this.onUpdateSubstitution();
    }
  }

  onAddSubstitution(): void {
    // The button is disabled while the form is invalid, so this only guards a programmatic call.
    if (!this.isSubstitutionValid()) return;

    this.applySubstitutions(
      this.substitutionService.addSubstitution(this.substitutionModel, this.mapping().substitutions)
    );

    this.selectedSubstitution = -1;
    this.syncSplitSelection();
    // The next new substitution starts with the defaults, not with the options of the one just added.
    this.substitutionModel = {
      ...this.substitutionModel,
      expandArray: false,
      repairStrategy: RepairStrategy.DEFAULT
    };
  }

  onUpdateSubstitution(): void {
    const update = this.substitutionService.updateSubstitution(
      this.selectedSubstitution,
      this.substitutionModel,
      this.mapping().substitutions
    );
    if (!update) return;

    this.applySubstitutions(update);
    // A completed update is a natural end to the editing session - return to "add" state
    // rather than leaving the just-saved row selected and both buttons still enabled.
    this.cancelEditSubstitution();
  }

  /**
   * Hands a changed substitution list to the host, which owns the mapping and applies it, then
   * re-runs validity against the result. The step never writes `mapping.substitutions` itself.
   */
  private applySubstitutions(update: SubstitutionsUpdate | Substitution[]): void {
    const { substitutions, notice } = Array.isArray(update) ? { substitutions: update, notice: undefined } : update;
    this.substitutionsChange.emit(substitutions);
    if (notice) this.alertService.info(notice);
    // Against the new list: the `mapping` input still holds the old one until the host's change has
    // been rendered, so checking it here would miss the edit that was just made (a deleted device
    // identifier would still count).
    this.refreshSubstitutionValidity({ ...this.mapping(), substitutions });
  }

  /**
   * The split view shows the editor while something is selected, and "new substitution" counts as
   * the selection -1. Keeps it in line with `selectedSubstitution`, which stays the source of truth.
   * Optional calls: the view may not exist yet, or not at all (the code editor replaces it).
   */
  private syncSplitSelection(): void {
    this.splitView?.()?.selectionService.select(this.selectedSubstitution);
  }

  /** Back button of the stacked layout: leaves edit mode and shows the list. */
  onSplitBack(): void {
    this.cancelEditSubstitution();
    this.splitView?.()?.selectionService.clearSelection();
  }

  /** Leaves edit mode: clears the selected row, resets the form back to its "add" state, and
   *  clears the stale node highlight left in both editors from the substitution being edited. */
  cancelEditSubstitution(): void {
    this.selectedSubstitution = -1;
    this.syncSplitSelection();
    this.initializeSubstitutionModel();
    this.lastEvaluatedPath = { source: '', target: '' };
    this.sourceLinked = false;
    this.targetLinked = false;
    // With the event: the JSONata input shows a new control value in its editor only when the control
    // announces it, so a silent reset left the old text standing in the field. The evaluation behind the
    // field ignores '' (lastEvaluatedPath above), so the announcement costs nothing.
    for (const key of ['pathSource', 'pathTarget']) {
      const control = this.substitutionFormly.get(key);
      control?.setValue('');
      control?.markAsPristine();
      control?.markAsUntouched();
    }
    this.editorSourceStepSubstitution()?.clearSelection();
    this.editorTargetStepSubstitution()?.clearSelection();
  }

  onDeleteSubstitution(selected: number): void {
    const remaining = this.substitutionService.deleteSubstitution(selected, this.mapping().substitutions);
    if (remaining) this.applySubstitutions(remaining);
    // Deleting shifts every later index down by one, so a stale selectedSubstitution would
    // otherwise point at the wrong row (or go out of range) for "Update substitution".
    if (this.selectedSubstitution === selected) {
      this.selectedSubstitution = -1;
    } else if (this.selectedSubstitution > selected) {
      this.selectedSubstitution--;
    }
    this.syncSplitSelection();
  }

  private readonly refreshSubstitutionValidity = (mapping: Mapping = this.mapping()): void => {
    this.stepperService.refreshSubstitutionValidity(
      mapping,
      this.stepperConfiguration(),
      this.isBeforeSubstitutionStep()
    );
  };

  async onSelectSubstitution(selected: number): Promise<void> {
    if (selected < 0 || selected >= this.mapping().substitutions.length) return;

    this.selectedSubstitution = selected;
    // Linked again only if the row's path selects a node in the tree (an expression selects none).
    this.sourceLinked = false;
    this.targetLinked = false;
    this.syncSplitSelection();
    this.substitutionModel = {
      ...this.mapping().substitutions[selected],
      stepperConfiguration: this.stepperConfiguration()
    };
    this.substitutionModel.pathSourceIsExpression = isExpression(this.substitutionModel.pathSource);
    // The host calls this directly (to jump to a validation problem), so no event of this view marks it.
    this.cdr.markForCheck();

    await Promise.all([
      this.editorSourceStepSubstitution().setSelectionToPath(this.substitutionModel.pathSource),
      this.editorTargetStepSubstitution().setSelectionToPath(this.substitutionModel.pathTarget)
    ]);

    // Explicitly (re-)evaluate both expressions rather than relying on side effects to trigger
    // it: JsonEditorComponent.setSelectionToPath() above is a no-op for any expression containing
    // special characters (a very common case for expressions), and ngx-formly only patches (and
    // fires valueChanges for) the pathSource/pathTarget controls when their current text differs
    // from the model - so revisiting a row whose text is already showing silently skips
    // evaluation. Either way, the persisted Substitution never carries sourceExpression/
    // targetExpression, so without this the Result [type] label is left reading stale/empty
    // metadata while the result text itself still shows the last thing that WAS evaluated.
    // Sequential, not Promise.all: both methods finish by doing
    // `this.substitutionModel = {...this.substitutionModel}`, so running them concurrently risks
    // one overwriting the other's in-progress mutation.
    await this.updateSourceExpressionResult(this.substitutionModel.pathSource);
    await this.updateTargetExpressionResult(this.substitutionModel.pathTarget);
  }

  async openGenerateSubstitutionDrawer(): Promise<void> {
    const testMapping: MappingForAiDrawer = { ...this.mapping() };
    testMapping.sourceTemplate = JSON.stringify(this.sourceTemplate());
    testMapping.targetTemplate = JSON.stringify(this.targetTemplate());
    // Out-of-band transport fields (e.g. the Kafka record key of the message this mapping was
    // created from) are not part of the payload, so they can only reach AI generation this way.
    // Attached to the drawer's copy only — never persisted on the mapping itself.
    if (this.stepperConfiguration().sampleTransportFields) {
      testMapping.sampleTransportFields = this.stepperConfiguration().sampleTransportFields;
    }

    if (this.stepperConfiguration().sampleMessageInfo) {
      testMapping.sampleMessageInfo = this.stepperConfiguration().sampleMessageInfo;
    }
      if (this.stepperConfiguration().additionalSamples?.length) {
        testMapping.additionalSamples = this.stepperConfiguration().additionalSamples;
      }

    const drawer = this.bottomDrawerService.openDrawer(AiPromptComponent, {
      initialState: { mapping: testMapping, aiAgent: this.aiAgent(), existingMapping: this.stepperConfiguration().persistence === 'draft' }
    });

    try {
      const resultOf = await drawer.instance.result;

      if (isSubstitutionsAsCode(this.mapping())) {
        if (typeof resultOf === 'string' && resultOf.trim()) {
          this.mappingCodeChange.emit(resultOf);
        } else {
          this.alertService.warning(gettext('No valid JavaScript code was generated.'));
        }
      } else {
        if (Array.isArray(resultOf) && resultOf.length > 0) {
          this.alertService.success(
            this.translateService.instant(gettext('Generated {{count}} substitutions.'), { count: resultOf.length })
          );
          this.selectedSubstitution = -1;
          this.syncSplitSelection();
          // Bulk-replace rather than looping addSubstitution(): that method collapses duplicate
          // targets and raises an info alert per hit, which is noise when applying a
          // freshly-generated set wholesale.
          this.applySubstitutions(this.substitutionService.replaceAllSubstitutions(resultOf));
        } else {
          this.alertService.warning(gettext('No substitutions were generated.'));
        }
      }
    } catch (ex) {
      // User canceled
    }
    this.cdr.markForCheck();
  }

  onValueCodeChange(value: string): void {
    this.mappingCodeChange.emit(value);
  }

  /** The editor is read-only for viewers, and Save needs a valid substitution (both expressions evaluate). */
  saveSubstitutionDisabled(): boolean {
    return !this.stepperConfiguration().showEditorSource ||
      this.stepperConfiguration().readOnly ||
      !this.isSubstitutionValid() ||
      this.takenTargetNumber() !== undefined;
  }

  /**
   * 1-based number of ANOTHER substitution that already writes the target in the form, or undefined.
   * Saving would silently replace (add) or merge into (edit) that row, so the button is disabled
   * instead and the footer names the row.
   */
  takenTargetNumber(): number | undefined {
    const target = this.substitutionModel?.pathTarget;
    if (!target) return undefined;
    const index = (this.mapping().substitutions ?? [])
      .findIndex((sub, i) => i !== this.selectedSubstitution && sub.pathTarget === target);
    return index === -1 ? undefined : index + 1;
  }

  private isSubstitutionValid(): boolean {
    return this.substitutionService.isSubstitutionValid(this.substitutionModel);
  }

}