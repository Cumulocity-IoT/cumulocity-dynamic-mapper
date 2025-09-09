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
  inject,
  Input,
  OnDestroy,
  OnInit,
  ViewEncapsulation
} from '@angular/core';
import { FormBuilder, FormGroup } from '@angular/forms';
import { BottomDrawerRef, ModalLabels } from '@c8y/ngx-components';
import { Subject, takeUntil } from 'rxjs';
import { Direction, MappingType, MappingTypeDescriptionMap, MappingTypeDescriptions, MappingTypeLabels, TransformationType, TransformationTypeDescriptions, TransformationTypeLabels } from '../../shared';

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

interface SaveResult {
  mappingType: MappingType;
  transformationType: TransformationType;
  snoop: boolean;
}

@Component({
  selector: 'd11r-mapping-type-drawer',
  templateUrl: './mapping-type-drawer.component.html',
  encapsulation: ViewEncapsulation.None,
  standalone: false
})
export class MappingTypeDrawerComponent implements OnInit, OnDestroy {
  @Input() direction: Direction;

  // Template constants
  readonly labels: ModalLabels = { ok: 'Select', cancel: 'Cancel' };
  readonly MappingTypeDescriptionMap = MappingTypeDescriptionMap;
  readonly Direction = Direction;

  // Form and options
  formGroup: FormGroup;
  filteredMappingTypes: MappingTypeOption[] = [];
  transformationTypeOptions: TransformationTypeOption[] = [];

  // State
  showTransformationType = false;
  valid = true;

  // Constants
  private readonly DEFAULT_MAPPING_TYPE = MappingType.JSON;
  private readonly DEFAULT_TRANSFORMATION_TYPE = TransformationType.JSONATA;
  private readonly EXPERT_MODE_EXCLUDED_TYPES = [
    MappingType.EXTENSION_SOURCE,
    MappingType.PROTOBUF_INTERNAL,
    MappingType.EXTENSION_SOURCE_TARGET
  ];

  private readonly destroy$ = new Subject<void>();
  private readonly bottomDrawerRef = inject(BottomDrawerRef);
  private readonly fb = inject(FormBuilder);

  // Promise resolvers
  private _save: (value: SaveResult) => void;
  private _cancel: (reason?: any) => void;

  result: Promise<SaveResult | string> = new Promise((resolve, reject) => {
    this._save = resolve;
    this._cancel = reject;
  });

  ngOnInit(): void {
    this.initializeForm();
    this.setupFormSubscriptions();
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
    const selectedMappingType = formValue.mappingType;
    const snoopSupported = this.getMappingTypeConfig(selectedMappingType).snoopSupported;

    this._save({
      mappingType: selectedMappingType,
      transformationType: formValue.transformationType || TransformationType.DEFAULT,
      snoop: formValue.snoop && snoopSupported
    });
    this.bottomDrawerRef.close();
  }

  getTransformationTypeDescription(): string {
    const currentType = this.formGroup.get('transformationType')?.value;
    return currentType ? TransformationTypeDescriptions[currentType] : '';
  }

  getMappingTypeDescription(): string {
    const currentType = this.formGroup.get('mappingType')?.value;
    return currentType ? MappingTypeDescriptions[currentType] : '';
  }

  private initializeForm(): void {
    const initialConfig = this.getMappingTypeConfig(this.DEFAULT_MAPPING_TYPE);

    this.formGroup = this.fb.group({
      expertMode: [false],
      mappingType: [this.DEFAULT_MAPPING_TYPE],
      transformationType: [this.DEFAULT_TRANSFORMATION_TYPE],
      mappingTypeDescription: [initialConfig.description],
      snoop: [{ value: false, disabled: !initialConfig.snoopSupported }]
    });

    this.updateDerivedState();
  }

  private setupFormSubscriptions(): void {
    this.formGroup.get('mappingType')?.valueChanges
      .pipe(takeUntil(this.destroy$))
      .subscribe(type => this.onMappingTypeChange(type));

    this.formGroup.get('expertMode')?.valueChanges
      .pipe(takeUntil(this.destroy$))
      .subscribe(expertMode => this.onExpertModeChange(expertMode));
  }

  private onMappingTypeChange(type: MappingType): void {
    const config = this.getMappingTypeConfig(type);
    const snoopControl = this.formGroup.get('snoop');

    // Update description
    this.formGroup.patchValue({
      mappingTypeDescription: config.description
    });

    // Update snoop control
    if (config.snoopSupported) {
      snoopControl?.enable();
    } else {
      snoopControl?.disable();
    }

    // Update transformation type options
    this.updateTransformationTypeOptions(config.substitutionsAsCodeSupported);
  }

  private onExpertModeChange(expertMode: boolean): void {
    this.showTransformationType = expertMode;
    this.updateDerivedState();

    const transformationTypeControl = this.formGroup.get('transformationType');

    if (expertMode) {
      transformationTypeControl?.enable();
    } else {
      transformationTypeControl?.disable();
    }

    // Update transformation type options based on current mapping type
    const currentMappingType = this.formGroup.get('mappingType')?.value;
    const config = this.getMappingTypeConfig(currentMappingType);
    this.updateTransformationTypeOptions(config.substitutionsAsCodeSupported);
  }

  private updateDerivedState(): void {
    this.filteredMappingTypes = this.getFilteredMappingTypes();
  }

  private shouldIncludeMappingType(type: MappingType): boolean {
    // Always exclude CODE_BASED
    if (type === MappingType.CODE_BASED) {
      return false;
    }

    // Check direction support
    const config = this.getMappingTypeConfig(type);
    if (!config.directionSupported) {
      return false;
    }

    // In non-expert mode, exclude advanced types
    if (!this.showTransformationType && this.EXPERT_MODE_EXCLUDED_TYPES.includes(type)) {
      return false;
    }

    return true;
  }

  private updateTransformationTypeOptions(substitutionsAsCodeSupported: boolean): void {
    const availableTypes = substitutionsAsCodeSupported
      ? Object.values(TransformationType)
      : [TransformationType.DEFAULT, TransformationType.JSONATA];

    this.transformationTypeOptions = availableTypes.map(type => ({
      label: TransformationTypeLabels[type],
      value: type,
      description: TransformationTypeDescriptions[type]
    }));

    // Reset if current selection is not available
    const currentValue = this.formGroup.get('transformationType')?.value;
    if (!availableTypes.includes(currentValue)) {
      this.formGroup.patchValue({ transformationType: TransformationType.JSONATA });
    }
  }

  private getMappingTypeConfig(type: MappingType) {
    const config = MappingTypeDescriptionMap[type]?.properties?.[this.direction];
    return {
      description: MappingTypeDescriptionMap[type]?.description || '',
      snoopSupported: config?.snoopSupported || false,
      substitutionsAsCodeSupported: config?.substitutionsAsCodeSupported || false,
      directionSupported: config?.directionSupported || false
    };
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

}