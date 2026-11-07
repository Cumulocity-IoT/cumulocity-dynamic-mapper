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
  ChangeDetectorRef,
  Component,
  ElementRef,
  EventEmitter,
  inject,
  Input,
  OnChanges,
  OnDestroy,
  Output,
  SimpleChanges,
  ViewChild
} from '@angular/core';
import { FormGroup } from '@angular/forms';
import { Alert, AlertService, CoreModule } from '@c8y/ngx-components';
import { FormlyFieldConfig } from '@ngx-formly/core';
import { Observable, Subject, takeUntil } from 'rxjs';
import { debounceTime, distinctUntilChanged, map } from 'rxjs/operators';
import {
  ContentChanges,
  Feature,
  JsonEditorComponent,
  Mapping,
  StepperConfiguration
} from '../../shared';
import {
  checkTransformationType,
  isCodeOrExtensionTransformation,
  tryGetLiveEditorContent,
  validateProtectedFields
} from '../../shared/mapping/util';
import { StepperViewModel } from '../stepper/stepper-view.model';
import { CommonModule } from '@angular/common';
import { MappingStepperService } from '../service/mapping-stepper.service';
import { PROTECTED_TOKENS } from '../../shared/mapping/processor/processor.model';
import { PopoverModule } from 'ngx-bootstrap/popover';

@Component({
  selector: 'd11r-mapping-template-step',
  templateUrl: './mapping-template-step.component.html',
  styleUrls: ['../../shared/mapping/mapping.style.css'],
  styles: [`
    /* Both columns stretch to the taller one, so an empty panel fills the height of the editor
       next to it, and shrinks to a compact block when neither side has an editor. */
    .tpl-col { display: flex; flex-direction: column; min-width: 0; }
    .tpl-empty {
      flex: 1 1 auto;
      min-height: 120px;
      display: flex; flex-direction: column; align-items: center; justify-content: center;
      gap: 4px; padding: 16px; text-align: center;
      border: 1px dashed var(--c8y-root-component-border-color, #cfd4d9);
      border-radius: 4px;
      color: var(--c8y-root-component-text-color-muted, #6c757d);
    }
    .tpl-empty .tpl-empty-title { font-weight: 500; color: var(--c8y-root-component-color-default, inherit); }
    .tpl-empty .tpl-empty-text { font-size: 12px; max-width: 420px; }
    .tpl-transport {
      margin-top: 16px; padding: 8px 12px;
      border: 1px solid var(--c8y-root-component-border-color, #cfd4d9);
      border-left: 3px solid var(--c8y-brand-primary, #1776bf);
      border-radius: 4px;
    }
    .tpl-transport-title { font-weight: 500; }
    .tpl-transport-hint { font-size: 12px; color: var(--c8y-root-component-text-color-muted, #6c757d); }
    .tpl-transport-list { display: grid; grid-template-columns: max-content minmax(0, 1fr); gap: 4px 16px; margin: 8px 0 0; }
    .tpl-transport-list dd { margin: 0; word-break: break-all; }
    .tpl-transport-list dt { font-weight: normal; }
  `],
  standalone: true,
  imports: [CoreModule, CommonModule, PopoverModule,JsonEditorComponent]
})
export class MappingTemplateStepComponent implements OnChanges, OnDestroy {
  // ─── Inputs ───────────────────────────────────────────────────────────────
  @Input() mapping: Mapping;
  @Input() stepperConfiguration: StepperConfiguration;
  @Input() feature: Feature;
  @Input() sourceSystem: string;
  @Input() targetSystem: string;
  @Input() sourceTemplate: any;
  @Input() targetTemplate: any;
  @Input() editorOptionsSourceTemplate: any;
  @Input() editorOptionsTargetTemplate: any;
  @Input() stepperViewModel: StepperViewModel;
  @Input() templateForm: FormGroup;
  @Input() schemaSource: any;
  @Input() schemaTarget: any;
  /** filterFormly stays on the parent (CDK stepper uses it as [stepControl]) */
  @Input() filterFormly: FormGroup;
  @Input() filterFormlyFields: FormlyFieldConfig[];

  // ─── Outputs ──────────────────────────────────────────────────────────────
  /** Emits the new computed target template when "Reset to default" is clicked */
  @Output() targetTemplateChange = new EventEmitter<any>();
  /** The filter expression the user set; the host owns the mapping and applies it. */
  @Output() filterMappingChange = new EventEmitter<string>();

  // ─── Internal state ────────────────────────────────────────────────────────
  filterModel: { filterMapping?: string; filterExpression?: { result: string; resultType: string; valid: boolean } } = {};
  sourceTemplateUpdated: any;
  targetTemplateUpdated: any;
  selectedPathFilterFilterMapping: string;
  /** _IDENTITY_/_TOPIC_LEVEL_/_CONTEXT_DATA_ are only relevant for the Transformation step; hide them here by default. */
  showSourceMetadata = false;
  showTargetMetadata = false;
  /**
   * Whether the respective template actually carries metadata to toggle. Code- and
   * extension-based transformations never get metadata injected (see expandExternalTemplate /
   * expandC8YTemplate), so the toggle would be a no-op there and is hidden rather than shown
   * doing nothing — matching how the neighbouring "Reset to default" button is handled.
   */
  hasSourceMetadata = false;
  hasTargetMetadata = false;
  readonly metadataHelpText = `Metadata fields (<code>_IDENTITY_</code>, <code>_TOPIC_LEVEL_</code>,
    <code>_CONTEXT_DATA_</code>) identify the target device and carry topic/context information.
    They are not needed to author the templates here - use the Transformation step to build substitutions
    from them. Edits made while they are hidden are preserved.`;
  displayedSourceTemplate: any;
  displayedTargetTemplate: any;
  /**
   * Transport fields observed on the message this mapping was created from (e.g. a Kafka record
   * key), shown read-only below the source editor. Only populated for code/extension
   * transformations: those receive the fields via `msg.transportFields` instead of inside the
   * payload, so — unlike `_CONTEXT_DATA_` for JSONata — they never appear in the source template
   * and would otherwise be invisible to the author.
   */
  sampleTransportFields: { name: string; value: string; accessor: string }[] = [];
  private readonly destroy$ = new Subject<void>();

  // ─── ViewChild refs (public so tests can reach them if needed) ─────────────
  @ViewChild('editorSourceStepTemplate', { static: false })
  editorSourceStepTemplate!: JsonEditorComponent;

  @ViewChild('editorTargetStepTemplate', { static: false })
  editorTargetStepTemplate!: JsonEditorComponent;

  @ViewChild('filterModelFilterExpression')
  filterModelFilterExpression!: ElementRef<HTMLTextAreaElement>;

  // ─── Services ──────────────────────────────────────────────────────────────
  private readonly stepperService = inject(MappingStepperService);
  private readonly alertService = inject(AlertService);
  private readonly cdr = inject(ChangeDetectorRef);

  get isContentChangeValid$(): Observable<boolean> {
    return this.stepperService.isContentChangeValid$;
  }

  readonly checkTransformationType = checkTransformationType;
  readonly validateProtectedFields = validateProtectedFields;

  private computeSampleTransportFields(): { name: string; value: string; accessor: string }[] {
    const fields = this.stepperConfiguration?.sampleTransportFields;
    if (!fields || !isCodeOrExtensionTransformation(this.mapping?.transformationType)) {
      return [];
    }
    return Object.entries(fields)
      .filter(([, value]) => value !== null && value !== undefined && value !== '')
      .map(([name, value]) => ({
        name,
        value,
        // names such as "header.tenant" are not valid property names, so they need bracket access
        accessor: /^[A-Za-z_$][\w$]*$/.test(name) ? `msg.transportFields.${name}` : `msg.transportFields["${name}"]`
      }));
  }

  // ─── Lifecycle ─────────────────────────────────────────────────────────────

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['stepperConfiguration'] || changes['mapping']) {
      this.sampleTransportFields = this.computeSampleTransportFields();
    }

    // Subscribe to filterFormly once when it is first provided by the parent.
    if (changes['filterFormly'] && this.filterFormly && !changes['filterFormly'].previousValue) {
      // Pre-populate filterModel so Formly shows the existing expression on first render.
      if (this.mapping?.filterMapping) {
        this.filterModel = { ...this.filterModel, filterMapping: this.mapping.filterMapping };
      }
      // Subscribe at GROUP level: the filterMapping FormControl does not exist yet here
      // (Formly creates it during template rendering, which runs after ngOnChanges).
      // Using filterFormly.valueChanges (always available) and extracting filterMapping
      // avoids the silent no-op that occurred with the optional-chained control access.
      this.filterFormly.valueChanges.pipe(
        map((v: any) => v?.filterMapping as string),
        debounceTime(500),
        distinctUntilChanged(),
        takeUntil(this.destroy$)
      ).subscribe(path => {
        if (path) this.updateFilterExpressionResult(path);
      });
    }

    // When the parent expands templates and propagates a non-null sourceTemplate, evaluate
    // the filter expression immediately.  This covers the CDK-stepper lazy-render case where
    // templateStepRef is null when handleSelectTemplatesStep fires, so the parent's direct
    // call was a no-op and the debounced subscription hadn't fired yet.
    if (changes['sourceTemplate'] && this.sourceTemplate && this.mapping?.filterMapping) {
      this.updateFilterExpressionResult(this.mapping.filterMapping);
    }

    if (changes['sourceTemplate']) {
      this.displayedSourceTemplate = this.computeDisplayedTemplate(this.sourceTemplate, this.showSourceMetadata);
      this.hasSourceMetadata = this.containsMetadata(this.sourceTemplate);
    }
    if (changes['targetTemplate']) {
      this.displayedTargetTemplate = this.computeDisplayedTemplate(this.targetTemplate, this.showTargetMetadata);
      this.hasTargetMetadata = this.containsMetadata(this.targetTemplate);
    }
  }

  /**
   * The source template as the user currently has it, hidden metadata keys included. The editor
   * only holds the displayed template (metadata stripped while hidden), so reading it directly
   * would drop `_IDENTITY_`/`_TOPIC_LEVEL_`/`_CONTEXT_DATA_` from whatever the host stores.
   * `undefined` while the editor does not exist (step not rendered, or still being created).
   */
  getSourceTemplate(): any {
    return this.currentTemplate(this.editorSourceStepTemplate, this.sourceTemplateUpdated, this.sourceTemplate, this.showSourceMetadata);
  }

  /** The target-side counterpart of {@link getSourceTemplate}. */
  getTargetTemplate(): any {
    return this.currentTemplate(this.editorTargetStepTemplate, this.targetTemplateUpdated, this.targetTemplate, this.showTargetMetadata);
  }

  private currentTemplate(editor: JsonEditorComponent | undefined, mirror: any, full: any, showMetadata: boolean): any {
    const live = tryGetLiveEditorContent(editor);
    if (live === undefined) return mirror;
    return showMetadata ? live : this.restoreMetadataKeys(live, full);
  }

  /** True when the template carries at least one metadata token that the toggle could hide. */
  private containsMetadata(template: any): boolean {
    if (!template || typeof template !== 'object' || Array.isArray(template)) {
      return false;
    }
    return PROTECTED_TOKENS.some(token => token in template);
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  // ─── Metadata visibility ────────────────────────────────────────────────────

  toggleSourceMetadataVisibility(): void {
    this.showSourceMetadata = !this.showSourceMetadata;
    this.displayedSourceTemplate = this.computeDisplayedTemplate(this.sourceTemplate, this.showSourceMetadata);
  }

  toggleTargetMetadataVisibility(): void {
    this.showTargetMetadata = !this.showTargetMetadata;
    this.displayedTargetTemplate = this.computeDisplayedTemplate(this.targetTemplate, this.showTargetMetadata);
  }

  private computeDisplayedTemplate(template: any, showMetadata: boolean): any {
    return showMetadata ? template : this.stripMetadataKeys(template);
  }

  private stripMetadataKeys(template: any): any {
    if (!template || typeof template !== 'object' || Array.isArray(template)) {
      return template;
    }
    const result: any = { ...template };
    PROTECTED_TOKENS.forEach(token => delete result[token]);
    return result;
  }

  /** Restores top-level metadata keys stripped for display so they are never lost on save. */
  private restoreMetadataKeys(updated: any, full: any): any {
    if (!updated || typeof updated !== 'object' || Array.isArray(updated)) {
      return updated;
    }
    if (!full || typeof full !== 'object') {
      return updated;
    }
    const merged = { ...updated };
    PROTECTED_TOKENS.forEach(token => {
      if (token in full && !(token in merged)) {
        merged[token] = full[token];
      }
    });
    return merged;
  }

  // ─── Filter expression ─────────────────────────────────────────────────────

  onSelectedPathFilterMappingChanged(path: string): void {
    this.selectedPathFilterFilterMapping = path;
  }

  onOverwriteFilterMapping(): void {
    this.filterModel.filterMapping = this.selectedPathFilterFilterMapping;
    this.updateFilterExpressionResult(this.selectedPathFilterFilterMapping);
  }

  async updateFilterExpressionResult(path: string, templateOverride?: any): Promise<void> {
    this.clearAlerts();
    try {
      // Use an explicitly supplied template first (passed by the parent when it
      // has already expanded the templates but Angular hasn't yet propagated the
      // @Input change to this child). Fall back to the live editor content, then
      // to the @Input sourceTemplate as a last resort.
      const template = templateOverride ?? this.getSourceTemplate() ?? this.sourceTemplate;
      const result = await this.stepperService.evaluateFilterExpression(
        template,
        path
      );
      this.filterModel.filterExpression = result;
      this.filterModel.filterMapping = path;
      this.filterMappingChange.emit(path);
      // Patch the FormControl so the input field shows the value.
      // emitEvent: false prevents triggering the valueChanges subscription again.
      this.filterFormly.get('filterMapping')?.setValue(path, { emitEvent: false });
      if (result.valid) {
        // Clear any error left by an earlier failed evaluation: setValue({emitEvent: false})
        // does not re-run validation, so without this the control stays invalid forever once
        // a single evaluation has failed (e.g. the first one, fired before the source template
        // was expanded), even though the current expression evaluates fine.
        this.filterFormly.get('filterMapping')?.setErrors(null);
      } else {
        // A non-boolean result still populates filterModel.filterExpression above, so the
        // Filter Result box shows what the expression actually evaluated to alongside the error.
        this.filterFormly.get('filterMapping')?.setErrors({ validationError: { message: result.message } });
        this.filterFormly.get('filterMapping')?.markAsTouched();
      }
    } catch (error) {
      this.filterModel.filterExpression ??= { result: '', resultType: '', valid: false };
      this.filterModel.filterExpression.valid = false;
      this.filterFormly.get('filterMapping')?.setErrors({ validationError: { message: error.message } });
      this.filterFormly.get('filterMapping')?.markAsTouched();
    }
    this.filterModel = { ...this.filterModel };
    queueMicrotask(() => {
      this.manualResize();
      this.cdr.markForCheck();
    });
  }

  private manualResize(): void {
    const el = this.filterModelFilterExpression?.nativeElement;
    if (el) {
      el.style.height = '32px';
      el.style.height = el.scrollHeight + 'px';
    }
  }

  // ─── Template content changes ───────────────────────────────────────────────

  onSourceTemplateChanged(contentChanges: ContentChanges): void {
    // sourceTemplateChange has no @Output emitter; the parent reads sourceTemplateUpdated
    // via templateStepRef when leaving the step.
    this.onTemplateChanged(contentChanges, this.sourceTemplate, this.showSourceMetadata, json => { this.sourceTemplateUpdated = json; });
  }

  onTargetTemplateChanged(contentChanges: ContentChanges): void {
    // Like onSourceTemplateChanged: only update the local mirror here, don't emit upward.
    // This used to emit targetTemplateChange on every keystroke so the parent's targetTemplate
    // stayed "live" — but that reassignment flows straight back down through the parent's
    // [data]="targetTemplate" binding into JsonEditorComponent's data setter, which calls
    // editor.set() and resets the editor's content/cursor, visibly interrupting typing. The
    // parent instead reads targetTemplateUpdated via templateStepRef when it actually needs the
    // value (leaving the step, triggering AI generation, etc.).
    this.onTemplateChanged(contentChanges, this.targetTemplate, this.showTargetMetadata, json => { this.targetTemplateUpdated = json; });
  }

  private onTemplateChanged(
    contentChanges: ContentChanges,
    baseline: any,
    showMetadata: boolean,
    onUpdated?: (json: any) => void
  ): void {
    const { previousContent, updatedContent } = contentChanges;
    let updatedJson: any;

    if ('text' in updatedContent && updatedContent['text']) {
      try { updatedJson = JSON.parse(updatedContent['text']); }
      catch {
        onUpdated?.(updatedContent);
        this.stepperService.isContentChangeValid$.next(true);
        return;
      }
    } else {
      updatedJson = updatedContent['json'];
    }

    // Metadata keys (_IDENTITY_, _TOPIC_LEVEL_, _CONTEXT_DATA_) are hidden from the editor
    // when showMetadata is false, so they are absent from updatedJson. Restore them from the
    // original (full) template so they are never dropped on save.
    if (!showMetadata) {
      updatedJson = this.restoreMetadataKeys(updatedJson, baseline);
    }

    onUpdated?.(updatedJson);

    if (previousContent) {
      if ('text' in previousContent && previousContent['text']) {
        try { baseline = JSON.parse(previousContent['text']); } catch { /* keep fallback */ }
      } else if ('json' in previousContent) {
        baseline = previousContent['json'];
      }
    }

    // Metadata keys can't be edited via the UI while hidden, so there is nothing to protect
    // against — skip the check to avoid false positives from their absence in the editor content.
    const hasProtectedChanges = showMetadata
      && this.stepperConfiguration.allowTemplateExpansion
      && !validateProtectedFields(baseline, updatedJson);
    const isTransformationTypeValid = checkTransformationType(this.mapping.transformationType, updatedJson);
    const isValid = !hasProtectedChanges && isTransformationTypeValid;
    this.stepperService.isContentChangeValid$.next(isValid);

    if (hasProtectedChanges && this.stepperConfiguration.allowTemplateExpansion) {
      this.raiseAlert({ type: 'warning', text: 'Warning: Changes to _IDENTITY_, _TOPIC_LEVEL_, or _CONTEXT_DATA_ will be reverted when saving.' });
    }
  }

  // ─── Sample target templates ────────────────────────────────────────────────

  onSampleTargetTemplatesButton(): void {
    const newTarget = this.stepperService.computeSampleTargetTemplate(this.mapping, this.stepperConfiguration);
    this.editorTargetStepTemplate?.set(this.computeDisplayedTemplate(newTarget, this.showTargetMetadata));
    this.targetTemplateChange.emit(newTarget);
  }

  // ─── Helpers ───────────────────────────────────────────────────────────────

  raiseAlert(alert: Alert): void {
    this.clearAlerts();
    this.alertService.add(alert);
  }

  clearAlerts(): void {
    this.alertService.state.forEach(a => {
      if (a.type === 'info' || a.type === 'warning') this.alertService.remove(a);
    });
  }
}
