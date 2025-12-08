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
  inject,
  Input,
  OnDestroy,
  OnInit,
  ViewChild,
  ViewEncapsulation
} from '@angular/core';
import { FormBuilder, FormGroup } from '@angular/forms';
import { BottomDrawerRef, ModalLabels } from '@c8y/ngx-components';
import { Subject, takeUntil } from 'rxjs';
import {
  Direction,
  MappingType,
  MappingTypeDescriptionMap,
  MappingTypeDescriptions,
  MappingTypeLabels,
  SharedService,
  TransformationType,
  TransformationTypeDescriptions,
  TransformationTypeLabels
} from '../../shared';
import { CodeTemplate } from 'src/configuration';

interface MappingTypeOption {
  label: string;
  value: MappingType;
  description?: string;
}

interface TransformationTypeOption {
  label: string;
  value: TransformationType;
  description?: string;
}

interface CodeTemplateOption {
  label: string;
  value: CodeTemplate;
  description?: string;
  id?: string;
}

interface SaveResult {
  mappingType: MappingType;
  transformationType: TransformationType;
  snoop: boolean;
  codeTemplate?: CodeTemplate;
}

@Component({
  selector: 'd11r-mapping-type-drawer',
  templateUrl: './mapping-type-drawer.component.html',
  encapsulation: ViewEncapsulation.None,
  standalone: false
})
export class MappingTypeDrawerComponent implements OnInit, OnDestroy {
  @Input() direction: Direction;

  @ViewChild('descriptionTextarea') descriptionTextarea: ElementRef<HTMLTextAreaElement>;

  // Template constants
  readonly labels: ModalLabels = { ok: 'Select', cancel: 'Cancel' };
  readonly MappingTypeDescriptionMap = MappingTypeDescriptionMap;
  readonly Direction = Direction;

  // Form and options
  formGroup: FormGroup;
  filteredMappingTypeOptions: MappingTypeOption[] = [];
  transformationTypeOptions: TransformationTypeOption[] = [];
  codeTemplateOptions: CodeTemplateOption[] = [];

  // State
  showTransformationType = false;
  valid = true;
  isLoading = true; // Add loading state

  // Constants
  private readonly DEFAULT_MAPPING_TYPE = MappingType.JSON;
  private readonly DEFAULT_TRANSFORMATION_TYPE = TransformationType.JSONATA;
  private readonly EXPERT_MODE_EXCLUDED_TYPES = [
    MappingType.EXTENSION_SOURCE,
    MappingType.PROTOBUF_INTERNAL,
    MappingType.EXTENSION_SOURCE_TARGET
  ];
  private readonly CODE_TEMPLATE_TRANSFORMATION_TYPES = [
    TransformationType.SUBSTITUTION_AS_CODE,
    TransformationType.SMART_FUNCTION
  ];

  private readonly destroy$ = new Subject<void>();
  private readonly bottomDrawerRef = inject(BottomDrawerRef);
  private readonly sharedService = inject(SharedService);
  private readonly fb = inject(FormBuilder);

  // Promise resolvers
  private _save: (value: SaveResult) => void;
  private _cancel: (reason?: any) => void;

  result: Promise<SaveResult | string> = new Promise((resolve, reject) => {
    this._save = resolve;
    this._cancel = reject;
  });

  async ngOnInit(): Promise<void> {
    try {
      await this.initializeForm();
      this.setupFormSubscriptions();
    } finally {
      this.isLoading = false;
    }
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  onCancel(): void {
    this._cancel("User canceled");
    this.bottomDrawerRef.close();
  }

  onSave(): void {
    if (!this.formGroup.valid) return;

    const formValue = this.formGroup.getRawValue();
    const selectedMappingTypeOption = formValue.mappingType as MappingTypeOption;
    const selectedMappingType = selectedMappingTypeOption.value;
    const snoopSupported = this.getMappingTypeConfig(selectedMappingType).snoopSupported;

    const selectedTransformationTypeOption = formValue.transformationType as TransformationTypeOption;
    const selectedTransformationType = selectedTransformationTypeOption?.value || TransformationType.DEFAULT;

    const selectedCodeTemplateOption = formValue.codeTemplate as CodeTemplateOption;
    const selectedCodeTemplate = selectedCodeTemplateOption?.value;

    this._save({
      mappingType: selectedMappingType,
      transformationType: selectedTransformationType,
      snoop: formValue.snoop && snoopSupported,
      codeTemplate: selectedCodeTemplate
    });
    this.bottomDrawerRef.close();
  }

  getTransformationTypeDescription(): string {
    const currentOption = this.formGroup?.get('transformationType')?.value as TransformationTypeOption;
    const currentType = currentOption?.value;
    return currentType ? TransformationTypeDescriptions[currentType] : '';
  }

  getCodeTemplateDescription(): string {
    const currentOption = this.formGroup?.get('codeTemplate')?.value as CodeTemplateOption;
    if (!currentOption) {
      return 'Select a code template to use as a starting point for your transformation';
    }
    return currentOption.description || 'Select a code template to use as a starting point for your transformation';
  }

  private async initializeForm(): Promise<void> {
    // Find the first enabled mapping type for the current direction as default
    const enabledMappingTypes = Object.values(MappingType).filter(type => {
      const mappingTypeConfig = MappingTypeDescriptionMap[type];
      return mappingTypeConfig?.enabled &&
        mappingTypeConfig.properties?.[this.direction]?.directionSupported;
    });

    const defaultMappingType = enabledMappingTypes.includes(this.DEFAULT_MAPPING_TYPE)
      ? this.DEFAULT_MAPPING_TYPE
      : enabledMappingTypes[0] || this.DEFAULT_MAPPING_TYPE;

    const initialConfig = this.getMappingTypeConfig(defaultMappingType);
    const supportedTransformationTypes = initialConfig.supportedTransformationTypes;

    // Choose default transformation type from supported types
    const defaultTransformationType = supportedTransformationTypes.includes(this.DEFAULT_TRANSFORMATION_TYPE)
      ? this.DEFAULT_TRANSFORMATION_TYPE
      : supportedTransformationTypes[0] || this.DEFAULT_TRANSFORMATION_TYPE;

    // Create the initial mapping type option object
    const initialMappingTypeOption: MappingTypeOption = {
      label: MappingTypeLabels[defaultMappingType],
      value: defaultMappingType,
      description: MappingTypeDescriptions[defaultMappingType]
    };

    // Create the initial transformation type option object
    const initialTransformationTypeOption: TransformationTypeOption = {
      label: TransformationTypeLabels[this.direction][defaultTransformationType],
      value: defaultTransformationType,
      description: TransformationTypeDescriptions[defaultTransformationType]
    };

    // Load code templates for initial transformation type
    await this.loadCodeTemplates(defaultTransformationType);
    const initialCodeTemplateOption = this.codeTemplateOptions[0] || null;

    // Create form group
    this.formGroup = this.fb.group({
      expertMode: [false],
      mappingType: [initialMappingTypeOption],
      transformationType: [initialTransformationTypeOption],
      mappingTypeDescription: [{ value: initialConfig.description, disabled: true }],
      snoop: [{ value: false, disabled: !initialConfig.snoopSupported }],
      codeTemplate: [initialCodeTemplateOption]
    });

    // Initialize derived state and options
    this.updateDerivedState();
    this.updateTransformationTypeOptions();
  }

  private setupFormSubscriptions(): void {
    this.formGroup.get('mappingType')?.valueChanges
      .pipe(takeUntil(this.destroy$))
      .subscribe(type => this.onMappingTypeChange(type));

    this.formGroup.get('expertMode')?.valueChanges
      .pipe(takeUntil(this.destroy$))
      .subscribe(expertMode => this.onExpertModeChange(expertMode));

    this.formGroup.get('transformationType')?.valueChanges
      .pipe(takeUntil(this.destroy$))
      .subscribe(type => this.onTransformationTypeChange(type));
  }

  private updateDerivedState(): void {
    this.filteredMappingTypeOptions = this.getFilteredMappingTypes();
  }

  private getFilteredMappingTypes(): MappingTypeOption[] {
    return Object.values(MappingType)
      .filter(type => this.shouldIncludeMappingType(type))
      .map(type => ({
        label: MappingTypeLabels[type],
        value: type,
        description: MappingTypeDescriptions[type]
      }));
  }

  private manualResize() {
    if (this.descriptionTextarea?.nativeElement) {
      const element = this.descriptionTextarea.nativeElement;
      element.style.height = '32px';
      element.style.height = element.scrollHeight + 'px';
    }
  }

  private onMappingTypeChange(selectedOption: MappingTypeOption): void {
    const mappingType = selectedOption.value;
    const config = this.getMappingTypeConfig(mappingType);
    const snoopControl = this.formGroup.get('snoop');

    // Update description
    this.formGroup.patchValue({
      mappingTypeDescription: config.description
    });

    // Trigger resize after content change
    setTimeout(() => this.manualResize(), 0);

    // Update snoop control
    if (config.snoopSupported) {
      snoopControl?.enable();
    } else {
      snoopControl?.disable();
      snoopControl?.patchValue(false);
    }

    // Update transformation type options based on the new mapping type
    this.updateTransformationTypeOptions();
  }

  private async onTransformationTypeChange(selectedOption: TransformationTypeOption): Promise<void> {
    const transformationType = selectedOption?.value;
    if (!transformationType) {
      return;
    }

    await this.loadCodeTemplates(transformationType);

    // Only set a default template if we have options available
    if (this.codeTemplateOptions.length > 0) {
      const firstTemplate = this.codeTemplateOptions[0];
      this.formGroup.patchValue({ codeTemplate: firstTemplate }, { emitEvent: false });
    } else {
      // Clear the code template if no options are available
      this.formGroup.patchValue({ codeTemplate: null }, { emitEvent: false });
    }
  }

  private onExpertModeChange(expertMode: boolean): void {
    this.showTransformationType = expertMode;

    const transformationTypeControl = this.formGroup.get('transformationType');
    const mappingTypeControl = this.formGroup.get('mappingType');

    const currentMappingTypeOption = mappingTypeControl?.value as MappingTypeOption;
    const currentMappingType = currentMappingTypeOption?.value;

    if (expertMode) {
      transformationTypeControl?.enable();
    } else {
      transformationTypeControl?.disable();

      const patchValues: any = {};

      // Reset mapping type if current selection is not available in non-expert mode
      if (currentMappingType && this.EXPERT_MODE_EXCLUDED_TYPES.includes(currentMappingType)) {
        const availableMappingTypes = this.getFilteredMappingTypes();
        const defaultOption = availableMappingTypes.find(
          option => option.value === this.DEFAULT_MAPPING_TYPE
        ) || availableMappingTypes[0];

        if (defaultOption) {
          patchValues.mappingType = defaultOption;
        }
      }

      // Always reset transformation type when leaving expert mode
      const finalMappingType = patchValues.mappingType?.value || currentMappingType;
      if (finalMappingType) {
        const config = this.getMappingTypeConfig(finalMappingType);
        const supportedTypes = config.supportedTransformationTypes;
        const defaultTransformationType = supportedTypes.includes(this.DEFAULT_TRANSFORMATION_TYPE)
          ? this.DEFAULT_TRANSFORMATION_TYPE
          : supportedTypes[0] || this.DEFAULT_TRANSFORMATION_TYPE;

        patchValues.transformationType = {
          label: TransformationTypeLabels[this.direction][defaultTransformationType],
          value: defaultTransformationType,
          description: TransformationTypeDescriptions[defaultTransformationType]
        };
      }

      this.formGroup.patchValue(patchValues);
    }

    this.updateDerivedState();

    // Update transformation type options based on current mapping type
    const finalMappingTypeOption = this.formGroup.get('mappingType')?.value as MappingTypeOption;
    const finalMappingType = finalMappingTypeOption?.value;
    if (finalMappingType) {
      const config = this.getMappingTypeConfig(finalMappingType);
      this.updateTransformationTypeOptions();
    }
  }

  getMappingTypeDescription(): string {
    const currentOption = this.formGroup?.get('mappingType')?.value as MappingTypeOption;
    const currentType = currentOption?.value;
    return currentType ? MappingTypeDescriptionMap[currentType]?.description : '';
  }

  shouldShowTransformationType(): boolean {
    if (!this.showTransformationType) {
      return false;
    }

    const currentMappingTypeOption = this.formGroup?.get('mappingType')?.value as MappingTypeOption;
    if (!currentMappingTypeOption?.value) {
      return false;
    }

    const config = this.getMappingTypeConfig(currentMappingTypeOption.value);
    return config.supportedTransformationTypes.length > 0;
  }

  shouldShowSelectCodeTemplate(): boolean {
    if (!this.showTransformationType) {
      return false;
    }

    const currentTransformationTypeOption = this.formGroup?.get('transformationType')?.value as TransformationTypeOption;
    if (!currentTransformationTypeOption?.value) {
      return false;
    }

    return this.CODE_TEMPLATE_TRANSFORMATION_TYPES.includes(currentTransformationTypeOption.value);
  }

  private shouldIncludeMappingType(type: MappingType): boolean {
    const mappingTypeConfig = MappingTypeDescriptionMap[type];
    if (!mappingTypeConfig?.enabled) {
      return false;
    }

    const config = this.getMappingTypeConfig(type);
    if (!config.directionSupported) {
      return false;
    }

    if (!this.showTransformationType && this.EXPERT_MODE_EXCLUDED_TYPES.includes(type)) {
      return false;
    }

    return true;
  }

  private updateTransformationTypeOptions(): void {
    const currentMappingTypeOption = this.formGroup?.get('mappingType')?.value as MappingTypeOption;
    const currentMappingType = currentMappingTypeOption?.value;

    if (!currentMappingType) {
      this.transformationTypeOptions = [];
      return;
    }

    const config = this.getMappingTypeConfig(currentMappingType);
    const supportedTypes = config.supportedTransformationTypes || [];

    this.transformationTypeOptions = supportedTypes.map(type => ({
      label: TransformationTypeLabels[this.direction][type],
      value: type,
      description: TransformationTypeDescriptions[type]
    }));

    const currentValue = this.formGroup?.get('transformationType')?.value as TransformationTypeOption;
    if (!supportedTypes.includes(currentValue?.value)) {
      const defaultType = supportedTypes.includes(TransformationType.DEFAULT)
        ? TransformationType.DEFAULT
        : supportedTypes[0] || TransformationType.DEFAULT;

      this.formGroup.patchValue({
        transformationType: {
          label: TransformationTypeLabels[this.direction][defaultType],
          value: defaultType,
          description: TransformationTypeDescriptions[defaultType]
        }
      });
    }
  }

  private async loadCodeTemplates(transformationType: TransformationType): Promise<void> {
    // Only load templates for types that support them
    if (!this.CODE_TEMPLATE_TRANSFORMATION_TYPES.includes(transformationType)) {
      this.codeTemplateOptions = [];
      return;
    }

    try {
      const codeTemplates = await this.sharedService.getCodeTemplatesByType(
        this.direction,
        transformationType
      );

      console.log(`Loaded ${codeTemplates.length} code templates for ${this.direction}/${transformationType}`);

      this.codeTemplateOptions = codeTemplates.map(template => ({
        id: template.id,
        label: template.name || template.id,
        value: template,
        description: template.description || `Code template for ${transformationType}`
      }));

      // Log for debugging
      console.log('Code template options:', this.codeTemplateOptions);

    } catch (error) {
      console.error('Failed to load code templates:', error);
      this.codeTemplateOptions = [];
    }
  }

  private getMappingTypeConfig(type: MappingType) {
    const mappingTypeConfig = MappingTypeDescriptionMap[type];
    const config = mappingTypeConfig?.properties?.[this.direction];

    return {
      description: mappingTypeConfig?.description || '',
      enabled: mappingTypeConfig?.enabled || false,
      snoopSupported: config?.snoopSupported || false,
      substitutionsAsCodeSupported: config?.substitutionsAsCodeSupported || false,
      directionSupported: config?.directionSupported || false,
      supportedTransformationTypes: config?.supportedTransformationTypes || []
    };
  }
}