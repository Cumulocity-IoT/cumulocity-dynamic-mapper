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
import { debounceTime, distinctUntilChanged } from 'rxjs/operators';
import {
  ContentChanges,
  Direction,
  Feature,
  getExternalTemplate,
  JsonEditorComponent,
  Mapping,
  SAMPLE_TEMPLATES_C8Y,
  StepperConfiguration
} from '../../shared';
import {
  checkTransformationType,
  expandC8YTemplate,
  expandExternalTemplate,
  splitTopicExcludingSeparator,
  validateProtectedFields
} from '../shared/util';
import { StepperViewModel } from '../stepper-mapping/stepper-view.model';
import { CommonModule } from '@angular/common';
import { MappingStepperService } from '../service/mapping-stepper.service';

@Component({
  selector: 'd11r-mapping-template-step',
  templateUrl: './mapping-template-step.component.html',
  styleUrls: ['../shared/mapping.style.css'],
  standalone: true,
  imports: [CoreModule, CommonModule, JsonEditorComponent]
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
  @Input() snoopedTemplateItems: { label: string; value: string }[] = [];
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

  // ─── Internal state ────────────────────────────────────────────────────────
  filterModel: { filterMapping?: string; filterExpression?: { result: string; resultType: string; valid: boolean } } = {};
  sourceTemplateUpdated: any;
  private selectedPathFilterFilterMapping: string;
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

  // ─── Lifecycle ─────────────────────────────────────────────────────────────

  ngOnChanges(changes: SimpleChanges): void {
    // Subscribe to filterFormly once it is provided by the parent
    if (changes['filterFormly'] && this.filterFormly && !changes['filterFormly'].previousValue) {
      this.filterFormly.get('filterMapping')?.valueChanges.pipe(
        debounceTime(500),
        distinctUntilChanged(),
        takeUntil(this.destroy$)
      ).subscribe(path => this.updateFilterExpressionResult(path));
    }
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  // ─── Filter expression ─────────────────────────────────────────────────────

  onSelectedPathFilterMappingChanged(path: string): void {
    this.selectedPathFilterFilterMapping = path;
  }

  onOverwriteFilterMapping(): void {
    this.filterModel.filterMapping = this.selectedPathFilterFilterMapping;
    this.updateFilterExpressionResult(this.selectedPathFilterFilterMapping);
  }

  async updateFilterExpressionResult(path: string): Promise<void> {
    this.clearAlerts();
    try {
      const result = await this.stepperService.evaluateFilterExpression(
        this.editorSourceStepTemplate?.get(),
        path
      );
      this.filterModel.filterExpression = result;
      this.mapping.filterMapping = path;
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
    const { previousContent, updatedContent } = contentChanges;
    let updatedJson: any;

    if ('text' in updatedContent && updatedContent['text']) {
      try { updatedJson = JSON.parse(updatedContent['text']); }
      catch {
        this.sourceTemplateUpdated = updatedContent;
        this.stepperService.isContentChangeValid$.next(true);
        return;
      }
    } else {
      updatedJson = updatedContent['json'];
    }

    this.sourceTemplateUpdated = updatedJson;
    // Do NOT emit here — emitting would update [sourceTemplate] on the parent
    // which re-seeds the editor and discards the in-progress edit.
    // The parent reads sourceTemplateUpdated via templateStepRef when leaving the step.

    let baseline = this.sourceTemplate;
    if (previousContent) {
      if ('text' in previousContent && previousContent['text']) {
        try { baseline = JSON.parse(previousContent['text']); } catch { /* keep fallback */ }
      } else if ('json' in previousContent) {
        baseline = previousContent['json'];
      }
    }

    const hasProtectedChanges = this.stepperConfiguration.allowTemplateExpansion
      && !validateProtectedFields(baseline, updatedJson);
    const isTransformationTypeValid = checkTransformationType(this.mapping.transformationType, updatedJson);
    const isValid = !hasProtectedChanges && isTransformationTypeValid;
    this.stepperService.isContentChangeValid$.next(isValid);

    if (hasProtectedChanges && this.stepperConfiguration.allowTemplateExpansion) {
      this.raiseAlert({ type: 'warning', text: 'Warning: Changes to _IDENTITY_, _TOPIC_LEVEL_, or _CONTEXT_DATA_ will be reverted when saving.' });
    }
  }

  onTargetTemplateChanged(contentChanges: ContentChanges): void {
    const { previousContent, updatedContent } = contentChanges;
    let updatedJson: any;

    if ('text' in updatedContent && updatedContent['text']) {
      try { updatedJson = JSON.parse(updatedContent['text']); }
      catch {
        this.stepperService.isContentChangeValid$.next(true);
        return;
      }
    } else {
      updatedJson = updatedContent['json'];
    }

    let baseline = this.targetTemplate;
    if (previousContent) {
      if ('text' in previousContent && previousContent['text']) {
        try { baseline = JSON.parse(previousContent['text']); } catch { /* keep fallback */ }
      } else if ('json' in previousContent) {
        baseline = previousContent['json'];
      }
    }

    const hasProtectedChanges = this.stepperConfiguration.allowTemplateExpansion
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
    let newTarget: any;
    if (this.stepperConfiguration.direction === Direction.INBOUND) {
      const template = JSON.parse(SAMPLE_TEMPLATES_C8Y[this.mapping.targetAPI]);
      newTarget = this.stepperConfiguration.allowTemplateExpansion
        ? expandC8YTemplate(template, this.mapping)
        : template;
    } else {
      const levels = splitTopicExcludingSeparator(this.mapping.mappingTopicSample, false);
      const template = JSON.parse(getExternalTemplate(this.mapping));
      newTarget = this.stepperConfiguration.allowTemplateExpansion
        ? expandExternalTemplate(template, this.mapping, levels)
        : template;
    }
    this.editorTargetStepTemplate?.set(newTarget);
    this.targetTemplateChange.emit(newTarget);
  }

  // ─── Helpers ───────────────────────────────────────────────────────────────

  raiseAlert(alert: Alert): void {
    this.alertService.state.forEach(a => {
      if (a.type === 'info' || a.type === 'warning') this.alertService.remove(a);
    });
    this.alertService.add(alert);
  }

  clearAlerts(): void {
    this.alertService.state.forEach(a => {
      if (a.type === 'info' || a.type === 'warning') this.alertService.remove(a);
    });
  }
}
