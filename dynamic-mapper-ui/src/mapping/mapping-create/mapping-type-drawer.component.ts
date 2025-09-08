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
import { BottomDrawerRef, HumanizePipe, ModalLabels } from '@c8y/ngx-components';
import { BehaviorSubject } from 'rxjs';
import { Direction, MappingType, MappingTypeDescriptionMap, TransformationType } from '../../shared';

@Component({
  selector: 'd11r-mapping-type-drawer',
  templateUrl: './mapping-type-drawer.component.html',
  encapsulation: ViewEncapsulation.None,
  standalone: false
})
export class MappingTypeDrawerComponent implements OnInit, OnDestroy {
  @Input() direction: Direction;

  labels: ModalLabels = { ok: 'Select', cancel: 'Cancel' };

  MappingTypeDescriptionMap = MappingTypeDescriptionMap;
  formGroup: FormGroup;
  snoop: boolean = false;
  canOpenInBrowser: boolean = false;
  substitutionsAsCodeSupported$: BehaviorSubject<boolean> = new BehaviorSubject(false);
  errorMessage: string;
  MappingType = MappingType;
  TransformationType = TransformationType;
  Direction = Direction;

  // Separate properties - one for internal logic, one for display
  mappingTypeDescription: string = '';
  valid: boolean = true;

  // Getter for template access
  initialMappingType: MappingType = MappingType.JSON;
  initialExpertMode = false;
  transformationTypeOptions: { label: string; value: string }[] = [];
  initialTransformationType: TransformationType = TransformationType.JSONATA;
  showTransformationType: boolean = false;

  // New property - filtered mapping types
  filteredMappingTypes: any;

  private _save: (value: {
    mappingType: MappingType;
    transformationType: TransformationType;
    snoop: boolean;
  }) => void;
  private _cancel: (reason?: any) => void;

  result: Promise<{
    mappingType: MappingType;
    transformationType: TransformationType;
    snoop: boolean;
  } | string> = new Promise((resolve, reject) => {
    this._save = resolve;
    this._cancel = reject;
  });

  bottomDrawerRef = inject(BottomDrawerRef);
  fb = inject(FormBuilder);

  ngOnInit(): void {
    // Initialize everything first
    this.mappingTypeDescription = MappingTypeDescriptionMap[this.initialMappingType]?.description || '';

    // Create form with initial states
    const initialSnoopSupported = MappingTypeDescriptionMap[this.initialMappingType]?.properties?.[this.direction]?.snoopSupported || false;
    const initialSubstitutionsSupported = MappingTypeDescriptionMap[this.initialMappingType]?.properties?.[this.direction]?.substitutionsAsCodeSupported || false;

    this.formGroup = this.fb.group({
      expertMode: [this.initialExpertMode],
      mappingType: [this.initialMappingType],
      transformationType: [this.initialTransformationType],
      mappingTypeDescription: [this.mappingTypeDescription],
      snoop: [{ value: false, disabled: !initialSnoopSupported }]
    });

    this.showTransformationType = this.initialExpertMode;

    this.filteredMappingTypes = Object.entries(MappingType)
      .filter(([key, value]) => (value !== MappingType.CODE_BASED))
      .reduce((obj, [key, value]) => {
        obj[key] = value;
        return obj;
      }, {});

    this.formGroup.get('mappingType')?.valueChanges.subscribe((type: MappingType) => {
      this.updateMappingTypeRelatedControls(type);
    });

    this.formGroup.get('expertMode')?.valueChanges.subscribe((expertMode: boolean) => {
      this.updateExpertModeRelatedControls(expertMode);
    });

    // Initialize transformation type options based on initial substitutionsAsCodeSupported
    this.updateTransformationTypeOptions(initialSubstitutionsSupported);

    const humanizePipe = new HumanizePipe();
  }

  onCancel() {
    this._cancel("User canceled");
    this.bottomDrawerRef.close();
  }

  onSave() {
    if (this.formGroup.valid) {
      const formValue = this.formGroup.getRawValue();
      const { snoopSupported } = MappingTypeDescriptionMap[this.initialMappingType].properties[this.direction];
      const selectedMappingType = this.formGroup.get('mappingType').value;

      // Fix this line - you're trying to destructure a string value
      const transformationTypeValue = this.formGroup.get('transformationType')?.value;

      // Handle both cases: if it's an object with value property or just a string
      const selectedTransformationType = typeof transformationTypeValue === 'object'
        ? transformationTypeValue.value
        : transformationTypeValue || TransformationType.DEFAULT;

      this._save({
        mappingType: selectedMappingType,
        transformationType: selectedTransformationType,
        snoop: formValue.snoop && snoopSupported
      });
      this.bottomDrawerRef.close();
    }
  }

  onSelectMappingType(type: MappingType) {
    // Simply update the form control - this will trigger valueChanges subscription
    this.formGroup.patchValue({ mappingType: type });
  }

  private updateMappingTypeRelatedControls(type: MappingType) {
    // Calculate disabled states
    const snoopSupported = MappingTypeDescriptionMap[type]?.properties?.[this.direction]?.snoopSupported || false;
    const substitutionsAsCodeSupported = MappingTypeDescriptionMap[type]?.properties?.[this.direction]?.substitutionsAsCodeSupported || false;

    const snoopControl = this.formGroup.get('snoop');

    // Only patch the description, not the mappingType itself to avoid loops
    this.formGroup.patchValue({
      mappingTypeDescription: MappingTypeDescriptionMap[type]?.description
    });

    // Update snoop control
    if (snoopSupported && snoopControl?.disabled) {
      snoopControl.enable();
    } else if (!snoopSupported && snoopControl?.enabled) {
      snoopControl.disable();
    }

    // Update transformation type options based on substitutionsAsCodeSupported
    this.updateTransformationTypeOptions(substitutionsAsCodeSupported);
  }

  private updateExpertModeRelatedControls(expertMode: boolean) {
    // Update the visibility flag
    this.showTransformationType = expertMode;

    const transformationTypeControl = this.formGroup.get('transformationType');

    if (expertMode) {
      // Enable transformationTypeControl and make it visible
      if (transformationTypeControl) {
        transformationTypeControl.enable();
      } else {
        // Add the control if it doesn't exist
        this.formGroup.addControl('transformationType', this.fb.control(this.initialTransformationType));
      }
    } else {
      // Disable transformationTypeControl
      if (transformationTypeControl) {
        transformationTypeControl.disable();
      }
    }
  }

  private updateTransformationTypeOptions(substitutionsAsCodeSupported: boolean) {
    const humanizePipe = new HumanizePipe();

    if (substitutionsAsCodeSupported) {
      // Include all options
      this.transformationTypeOptions = Object.values(TransformationType).map(type => ({
        label: humanizePipe.transform(type),
        value: type
      }));
    } else {
      // Include only DEFAULT and JSONATA
      this.transformationTypeOptions = [
        TransformationType.DEFAULT,
        TransformationType.JSONATA
      ].map(type => ({
        label: humanizePipe.transform(type),
        value: type
      }));
    }

    // Reset transformation type to JSONATA if current selection is not available
    const currentTransformationType = this.formGroup.get('transformationType')?.value;
    const availableValues = this.transformationTypeOptions.map(option => option.value);

    if (!availableValues.includes(currentTransformationType)) {
      this.formGroup.patchValue({ transformationType: TransformationType.JSONATA });
    }
  }

  ngOnDestroy() {
  }
}