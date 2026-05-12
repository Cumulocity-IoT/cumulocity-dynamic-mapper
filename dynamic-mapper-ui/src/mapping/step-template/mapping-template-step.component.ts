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
  ElementRef,
  EventEmitter,
  inject,
  Input,
  Output,
  ViewChild
} from '@angular/core';
import { FormGroup } from '@angular/forms';
import { CoreModule } from '@c8y/ngx-components';
import { FormlyFieldConfig } from '@ngx-formly/core';
import { Observable } from 'rxjs';
import {
  ContentChanges,
  Feature,
  JsonEditorComponent,
  Mapping,
  StepperConfiguration
} from '../../shared';
import { checkTransformationType, validateProtectedFields } from '../shared/util';
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
export class MappingTemplateStepComponent {
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
  @Input() updateSourceEditor: Observable<any>;
  @Input() updateTargetEditor: Observable<any>;
  @Input() stepperViewModel: StepperViewModel;
  @Input() templateForm: FormGroup;
  @Input() filterFormly: FormGroup;
  @Input() filterFormlyFields: FormlyFieldConfig[];
  @Input() filterModel: { filterMapping?: string; filterExpression?: { result: string; resultType: string; valid: boolean } };
  @Input() sourceTemplateUpdated: any;
  @Input() selectedPathFilterFilterMapping?: string;

  @Output() sourceTemplateChanged = new EventEmitter<ContentChanges>();
  @Output() targetTemplateChanged = new EventEmitter<ContentChanges>();
  @Output() editorSourceInitialized = new EventEmitter<void>();
  @Output() editorTargetInitialized = new EventEmitter<void>();
  @Output() pathFilterMappingChanged = new EventEmitter<string>();
  @Output() filterMappingOverwrite = new EventEmitter<void>();
  @Output() sampleTargetTemplates = new EventEmitter<void>();

  @ViewChild('editorSourceStepTemplate', { static: false })
  editorSourceStepTemplate!: JsonEditorComponent;

  @ViewChild('editorTargetStepTemplate', { static: false })
  editorTargetStepTemplate!: JsonEditorComponent;

  @ViewChild('filterModelFilterExpression')
  filterModelFilterExpression!: ElementRef<HTMLTextAreaElement>;

  private stepperService = inject(MappingStepperService);

  get isContentChangeValid$(): Observable<boolean> {
    return this.stepperService.isContentChangeValid$;
  }

  readonly checkTransformationType = checkTransformationType;
  readonly validateProtectedFields = validateProtectedFields;
}
