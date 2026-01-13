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

/*
 * Copyright (c) 2025 Cumulocity GmbH
 * SPDX-License-Identifier: Apache-2.0
 */

import { Component, inject, Input, OnDestroy, OnInit, ViewEncapsulation } from '@angular/core';
import { FormBuilder, FormGroup } from '@angular/forms';
import { BottomDrawerRef, CoreModule, ModalLabels } from '@c8y/ngx-components';
import { BsModalService } from 'ngx-bootstrap/modal';
import { Subject, takeUntil } from 'rxjs';
import {
  CodeExplorerComponent,
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
import { CodeTemplate } from '../../configuration';
import { base64ToString } from '../shared/util';

import { CustomSelectComponent } from '../../shared/component/select/custom-select.component';

// Types
interface SelectOption<T> {
  label: string;
  value: T;
  description?: string;
  id?: string;
}

type MappingTypeOption = SelectOption<MappingType>;
type TransformationTypeOption = SelectOption<TransformationType>;
type CodeTemplateOption = SelectOption<CodeTemplate>;

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
  standalone: true,
  imports: [CoreModule, CustomSelectComponent]
})
export class MappingTypeDrawerComponent implements OnInit, OnDestroy {
  @Input() direction: Direction;

  // Services
  private readonly bottomDrawerRef = inject(BottomDrawerRef);
  private readonly sharedService = inject(SharedService);
  private readonly fb = inject(FormBuilder);
  private readonly bsModalService = inject(BsModalService);
  private readonly destroy$ = new Subject<void>();

  // Constants - Remove 'as const' to allow normal type checking
  private readonly DEFAULTS = {
    MAPPING_TYPE: MappingType.JSON,
    TRANSFORMATION_TYPE: TransformationType.JSONATA
  };

  private readonly EXPERT_MODE_EXCLUDED_TYPES: MappingType[] = [
    MappingType.EXTENSION_SOURCE,
    MappingType.PROTOBUF_INTERNAL,
    MappingType.EXTENSION_SOURCE_TARGET
  ];

  private readonly CODE_TEMPLATE_TYPES: TransformationType[] = [
    TransformationType.SUBSTITUTION_AS_CODE,
    TransformationType.SMART_FUNCTION
  ];

  // Public properties
  readonly labels: ModalLabels = { ok: 'Select', cancel: 'Cancel' };
  
  formGroup: FormGroup;
  mappingTypeOptions: MappingTypeOption[] = [];
  transformationTypeOptions: TransformationTypeOption[] = [];
  codeTemplateOptions: CodeTemplateOption[] = [];
  
  isLoading = true;
  isLoadingCodeTemplates = false;
  showTransformationType = false;
  valid = true;

  // Promise for modal result
  private _resolve: (value: SaveResult) => void;
  private _reject: (reason?: any) => void;
  
  result = new Promise<SaveResult>((resolve, reject) => {
    this._resolve = resolve;
    this._reject = reject;
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

  // Public methods
  onCancel(): void {
    this._reject('User canceled');
    this.bottomDrawerRef.close();
  }

  onSave(): void {
    if (!this.formGroup.valid) return;

    const { mappingType, transformationType, snoop, codeTemplate } = this.formGroup.getRawValue();
    const snoopSupported = this.getConfigForMappingType(mappingType.value).snoopSupported;

    this._resolve({
      mappingType: mappingType.value,
      transformationType: transformationType?.value || TransformationType.DEFAULT,
      snoop: snoop && snoopSupported,
      codeTemplate: codeTemplate?.value
    });
    this.bottomDrawerRef.close();
  }

  viewCode(): void {
    const codeTemplate = this.formGroup.get('codeTemplate')?.value as CodeTemplateOption;
    if (!codeTemplate?.value) return;

    this.bsModalService.show(CodeExplorerComponent, {
      initialState: {
        templateCode: base64ToString(codeTemplate.value.code),
        templateName: codeTemplate.description,
        labels: { ok: 'Cancel', cancel: 'Cancel' }
      },
      class: 'modal-lg'
    });
  }

  // Template helpers
  getMappingTypeDescription(): string {
    const option = this.formGroup?.get('mappingType')?.value as MappingTypeOption;
    return option?.value ? MappingTypeDescriptionMap[option.value]?.description : '';
  }

  getTransformationTypeDescription(): string {
    const option = this.formGroup?.get('transformationType')?.value as TransformationTypeOption;
    return option?.value ? TransformationTypeDescriptions[option.value] : '';
  }

  getCodeTemplateDescription(): string {
    const option = this.formGroup?.get('codeTemplate')?.value as CodeTemplateOption;
    return option?.description || 'Select a code template to use as a starting point for your transformation';
  }

  shouldShowTransformationType(): boolean {
    if (!this.showTransformationType) return false;
    
    const option = this.formGroup?.get('mappingType')?.value as MappingTypeOption;
    if (!option?.value) return false;
    
    const config = this.getConfigForMappingType(option.value);
    return config.supportedTransformationTypes.length > 0;
  }

  shouldShowCodeTemplate(): boolean {
    if (!this.showTransformationType) return false;
    
    const option = this.formGroup?.get('transformationType')?.value as TransformationTypeOption;
    return option?.value ? this.CODE_TEMPLATE_TYPES.includes(option.value) : false;
  }

  // Private initialization methods
  private async initializeForm(): Promise<void> {
    const defaultMappingType = this.getDefaultMappingType();
    const config = this.getConfigForMappingType(defaultMappingType);
    const defaultTransformationType = this.getDefaultTransformationType(config.supportedTransformationTypes);

    const initialMappingType = this.createMappingTypeOption(defaultMappingType);
    const initialTransformationType = this.createTransformationTypeOption(defaultTransformationType);
    
    await this.loadCodeTemplates(defaultTransformationType);

    this.formGroup = this.fb.group({
      expertMode: [false],
      mappingType: [initialMappingType],
      transformationType: [initialTransformationType],
      snoop: [{ value: false, disabled: !config.snoopSupported }],
      codeTemplate: [this.codeTemplateOptions[0] || null]
    });

    this.updateMappingTypeOptions();
    this.updateTransformationTypeOptions();
  }

  private setupFormSubscriptions(): void {
    this.formGroup.get('expertMode')?.valueChanges
      .pipe(takeUntil(this.destroy$))
      .subscribe(enabled => this.onExpertModeChange(enabled));

    this.formGroup.get('mappingType')?.valueChanges
      .pipe(takeUntil(this.destroy$))
      .subscribe(option => this.onMappingTypeChange(option));

    this.formGroup.get('transformationType')?.valueChanges
      .pipe(takeUntil(this.destroy$))
      .subscribe(option => this.onTransformationTypeChange(option));
  }

  // Event handlers
  private onExpertModeChange(expertMode: boolean): void {
    this.showTransformationType = expertMode;
    const transformationControl = this.formGroup.get('transformationType');

    if (expertMode) {
      transformationControl?.enable();
    } else {
      transformationControl?.disable();
      this.resetToDefaultsIfNeeded();
    }

    this.updateMappingTypeOptions();
    this.updateTransformationTypeOptions();
  }

  private onMappingTypeChange(option: MappingTypeOption): void {
    const config = this.getConfigForMappingType(option.value);
    const snoopControl = this.formGroup.get('snoop');

    // Update snoop control
    config.snoopSupported ? snoopControl?.enable() : snoopControl?.disable();
    if (!config.snoopSupported) snoopControl?.setValue(false);

    this.updateTransformationTypeOptions();
  }

  private async onTransformationTypeChange(option: TransformationTypeOption): Promise<void> {
    if (!option?.value) return;

    this.isLoadingCodeTemplates = true;
    this.formGroup.patchValue({ codeTemplate: null }, { emitEvent: false });
    this.codeTemplateOptions = [];

    await this.loadCodeTemplates(option.value);

    if (this.codeTemplateOptions.length > 0) {
      this.formGroup.patchValue({ codeTemplate: this.codeTemplateOptions[0] }, { emitEvent: false });
    }

    this.isLoadingCodeTemplates = false;
  }

  // Helper methods
  private getDefaultMappingType(): MappingType {
    const enabledTypes = Object.values(MappingType).filter(type => {
      const config = MappingTypeDescriptionMap[type];
      return config?.enabled && config.properties?.[this.direction]?.directionSupported;
    });

    return enabledTypes.includes(this.DEFAULTS.MAPPING_TYPE) 
      ? this.DEFAULTS.MAPPING_TYPE 
      : enabledTypes[0] || this.DEFAULTS.MAPPING_TYPE;
  }

  private getDefaultTransformationType(supportedTypes: TransformationType[]): TransformationType {
    return supportedTypes.includes(this.DEFAULTS.TRANSFORMATION_TYPE)
      ? this.DEFAULTS.TRANSFORMATION_TYPE
      : supportedTypes[0] || this.DEFAULTS.TRANSFORMATION_TYPE;
  }

  private resetToDefaultsIfNeeded(): void {
    const currentMapping = this.formGroup.get('mappingType')?.value as MappingTypeOption;
    
    if (currentMapping?.value && this.EXPERT_MODE_EXCLUDED_TYPES.includes(currentMapping.value)) {
      const defaultMapping = this.createMappingTypeOption(this.getDefaultMappingType());
      this.formGroup.patchValue({ mappingType: defaultMapping });
    }

    const mappingType = currentMapping?.value || this.DEFAULTS.MAPPING_TYPE;
    const config = this.getConfigForMappingType(mappingType);
    const defaultTransformation = this.getDefaultTransformationType(config.supportedTransformationTypes);
    
    this.formGroup.patchValue({
      transformationType: this.createTransformationTypeOption(defaultTransformation)
    });
  }

  private updateMappingTypeOptions(): void {
    this.mappingTypeOptions = Object.values(MappingType)
      .filter(type => this.shouldIncludeMappingType(type))
      .map(type => this.createMappingTypeOption(type));
  }

  private updateTransformationTypeOptions(): void {
    const currentMapping = this.formGroup?.get('mappingType')?.value as MappingTypeOption;
    if (!currentMapping?.value) {
      this.transformationTypeOptions = [];
      return;
    }

    const config = this.getConfigForMappingType(currentMapping.value);
    this.transformationTypeOptions = config.supportedTransformationTypes.map(type =>
      this.createTransformationTypeOption(type)
    );

    // Reset if current selection is not available
    const currentTransformation = this.formGroup?.get('transformationType')?.value as TransformationTypeOption;
    if (currentTransformation?.value && !config.supportedTransformationTypes.includes(currentTransformation.value)) {
      const defaultType = this.getDefaultTransformationType(config.supportedTransformationTypes);
      this.formGroup.patchValue({ transformationType: this.createTransformationTypeOption(defaultType) });
    }
  }

  private async loadCodeTemplates(transformationType: TransformationType): Promise<void> {
    if (!this.CODE_TEMPLATE_TYPES.includes(transformationType)) {
      this.codeTemplateOptions = [];
      return;
    }

    try {
      const templates = await this.sharedService.getCodeTemplatesByType(this.direction, transformationType);
      
      this.codeTemplateOptions = templates.map(template => ({
        id: template.id,
        label: template.name || template.id,
        value: template,
        description: template.description || `Code template for ${transformationType}`
      }));
    } catch (error) {
      console.error('Failed to load code templates:', error);
      this.codeTemplateOptions = [];
    }
  }

  private shouldIncludeMappingType(type: MappingType): boolean {
    const mappingConfig = MappingTypeDescriptionMap[type];
    if (!mappingConfig?.enabled) return false;

    const config = this.getConfigForMappingType(type);
    if (!config.directionSupported) return false;

    if (!this.showTransformationType && this.EXPERT_MODE_EXCLUDED_TYPES.includes(type)) {
      return false;
    }

    return true;
  }

  private getConfigForMappingType(type: MappingType) {
    const mappingConfig = MappingTypeDescriptionMap[type];
    const directionConfig = mappingConfig?.properties?.[this.direction];

    return {
      description: mappingConfig?.description || '',
      enabled: mappingConfig?.enabled || false,
      snoopSupported: directionConfig?.snoopSupported || false,
      substitutionsAsCodeSupported: directionConfig?.substitutionsAsCodeSupported || false,
      directionSupported: directionConfig?.directionSupported || false,
      supportedTransformationTypes: directionConfig?.supportedTransformationTypes || []
    };
  }

  // Factory methods for options
  private createMappingTypeOption(type: MappingType): MappingTypeOption {
    return {
      label: MappingTypeLabels[type],
      value: type,
      description: MappingTypeDescriptions[type]
    };
  }

  private createTransformationTypeOption(type: TransformationType): TransformationTypeOption {
    return {
      label: TransformationTypeLabels[this.direction][type],
      value: type,
      description: TransformationTypeDescriptions[type]
    };
  }
}