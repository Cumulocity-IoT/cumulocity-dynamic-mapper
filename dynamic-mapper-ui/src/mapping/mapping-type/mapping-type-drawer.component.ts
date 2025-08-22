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
import { BehaviorSubject } from 'rxjs';
import { Direction, MappingType, MappingTypeDescriptionMap } from '../../shared';

@Component({
  selector: 'd11r-mapping-type-drawer',
  templateUrl: './mapping-type-drawer.component.html',
  encapsulation: ViewEncapsulation.None,
  standalone: false
})
export class MappingTypeDrawerComponent implements OnInit, OnDestroy {
  @Input() direction: Direction;

  labels: ModalLabels = { ok: 'Select', cancel: 'Cancel' }; s

  MappingTypeDescriptionMap = MappingTypeDescriptionMap;
  formGroup: FormGroup;
  snoop: boolean = false;
  canOpenInBrowser: boolean = false;
  substitutionsAsCodeSupported$: BehaviorSubject<boolean> = new BehaviorSubject(false);
  errorMessage: string;
  MappingType = MappingType;
  Direction = Direction;

  // Separate properties - one for internal logic, one for display
  mappingTypeDescription: string = '';
  valid: boolean = true;

  // Getter for template access
  selectedMappingType: MappingType = MappingType.JSON;

  // New property - filtered mapping types
  filteredMappingTypes: any;

  private _save: (value: {
    mappingType: MappingType;
    snoop: boolean;
    substitutionsAsCode: boolean;
  }) => void;
  private _cancel: (reason?: any) => void;

  result: Promise<{
    mappingType: MappingType;
    snoop: boolean;
    substitutionsAsCode: boolean;
  } | string> = new Promise((resolve, reject) => {
    this._save = resolve;
    this._cancel = reject;
  });

  bottomDrawerRef = inject(BottomDrawerRef);
  fb = inject(FormBuilder);

  ngOnInit(): void {
    // Initialize everything first
    this.mappingTypeDescription = MappingTypeDescriptionMap[this.selectedMappingType]?.description || '';

    // Create form with initial states
    const initialSnoopSupported = MappingTypeDescriptionMap[this.selectedMappingType]?.properties?.[this.direction]?.snoopSupported || false;
    const initialSubstitutionsSupported = MappingTypeDescriptionMap[this.selectedMappingType]?.properties?.[this.direction]?.substitutionsAsCodeSupported || false;

    this.formGroup = this.fb.group({
      mappingType: [this.selectedMappingType],
      mappingTypeDescription: [this.mappingTypeDescription],
      snoop: [{ value: false, disabled: !initialSnoopSupported }],
      substitutionsAsCode: [{ value: false, disabled: !initialSubstitutionsSupported }],
    });

    this.filteredMappingTypes = Object.entries(MappingType)
      .filter(([key, value]) => (value !== MappingType.CODE_BASED))
      .reduce((obj, [key, value]) => {
        obj[key] = value;
        return obj;
      }, {});

    this.formGroup.get('mappingType')?.valueChanges.subscribe((type: MappingType) => {
      this.updateMappingTypeRelatedControls(type);
    });
  }

  onCancel() {
    this._cancel("User canceled");
    this.bottomDrawerRef.close();
  }

  onSave() {
    if (this.formGroup.valid) {
      const formValue = this.formGroup.getRawValue(); // Get all values including disabled ones
      const { snoopSupported } = MappingTypeDescriptionMap[this.selectedMappingType].properties[this.direction];

      this._save({
        mappingType: this.selectedMappingType,
        snoop: formValue.snoop && snoopSupported,
        substitutionsAsCode: formValue.substitutionsAsCode
      });
      this.bottomDrawerRef.close();
    }
  }
  onSelectMappingType(type: MappingType) {
    // Simply update the form control - this will trigger valueChanges subscription
    this.formGroup.patchValue({ mappingType: type });
  }
  
  private updateMappingTypeRelatedControls(type: MappingType) {
    // Update description
    //this.mappingTypeDescription = MappingTypeDescriptionMap[type]?.description || '';
    
    // Calculate disabled states
    const snoopSupported = MappingTypeDescriptionMap[type]?.properties?.[this.direction]?.snoopSupported || false;
    const substitutionsSupported =  MappingTypeDescriptionMap[type]?.properties?.[this.direction]?.substitutionsAsCodeSupported || false;
    
    const snoopControl = this.formGroup.get('snoop');
    const substitutionsControl = this.formGroup.get('substitutionsAsCode');
    
    this.formGroup.patchValue({ mappingTypeDescription: MappingTypeDescriptionMap[type]?.description });

    // Update snoop control
    if (snoopSupported && snoopControl?.disabled) {
      snoopControl.enable();
    } else if (!snoopSupported && snoopControl?.enabled) {
      snoopControl.disable();
    }

    // Update substitutions control
    if (substitutionsSupported && substitutionsControl?.disabled) {
      substitutionsControl.enable();
    } else if (!substitutionsSupported && substitutionsControl?.enabled) {
      substitutionsControl.disable();
    }
  }

  ngOnDestroy() {
  }
}