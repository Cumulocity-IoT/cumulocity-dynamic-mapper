/*
 * Copyright (c) 2022 Software AG, Darmstadt, Germany and/or Software AG USA Inc., Reston, VA, USA,
 * and/or its subsidiaries and/or its affiliates and/or their licensors.
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
  Input,
  Output,
  ViewEncapsulation
} from '@angular/core';
import { BsModalRef, BsModalService } from 'ngx-bootstrap/modal';
import {
  ConfirmationModalComponent,
  definesDeviceIdentifier,
  Direction,
  MappingSubstitution
} from '../../shared';
import { EditorMode } from '../shared/stepper.model';
import { isDisabled } from '../shared/util';

@Component({
  selector: 'd11r-mapping-substitution-grid',
  templateUrl: 'substitution-grid.component.html',
  styleUrls: ['./substitution-grid.style.css'],
  encapsulation: ViewEncapsulation.None
})
export class SubstitutionRendererComponent {
  @Input()
  targetAPI: string;
  @Input()
  externalIdType: string;
  @Input()
  direction: Direction;
  @Input()
  substitutions: MappingSubstitution[] = [];
  @Input()
  settings: any;

  @Output() selectSub = new EventEmitter<number>();
  @Output() deleteSub = new EventEmitter<number>();
  @Output() editSub = new EventEmitter<number>();

  id = Math.floor(Math.random() * 1000000);
  definesDeviceIdentifier = definesDeviceIdentifier;
  isDisabled = isDisabled;
  EditorMode = EditorMode;

  constructor(
    private elementRef: ElementRef,
    private bsModalService: BsModalService
  ) {}

  onSubstitutionSelect(index: number) {
    // console.log('Selected substitution:', index);
    this.settings.selectedSubstitutionIndex = index;
    this.selectSub.emit(index);
  }

  scrollToSubstitution(i: number) {
    let ix = i;
    ix++;
    if (!ix || ix < 0 || ix >= this.substitutions.length) {
      ix = 0;
    }
    // console.log('Scroll to:', ix);
    this.elementRef.nativeElement
      .querySelector(`#sub-${this.id}-${ix}`)
      .scrollIntoView();
  }

  onSubstitutionEdit(index: number) {
    // console.log('Delete substitution:', index);
    this.settings.selectedSubstitutionIndex = index;
    this.editSub.emit(index);
  }

  onSubstitutionDelete(index: number) {
    const initialState = {
      title: 'Delete substitution',
      message:
        'You are about to delete a substitution. Do you want to proceed?',
      labels: {
        ok: 'Delete',
        cancel: 'Cancel'
      }
    };
    const confirmDeletionModalRef: BsModalRef = this.bsModalService.show(
      ConfirmationModalComponent,
      { initialState }
    );
    confirmDeletionModalRef.content.closeSubject.subscribe(
      async (result: boolean) => {
        if (result) {
          this.settings.selectedSubstitutionIndex = index;
          this.deleteSub.emit(index);
        }
        confirmDeletionModalRef.hide();
      }
    );
  }
}
