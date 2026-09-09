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
  EventEmitter,
  Input,
  OnDestroy,
  Output,
  ViewEncapsulation
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { BsModalRef, BsModalService } from 'ngx-bootstrap/modal';
import {
  ConfirmationModalComponent,
  definesDeviceIdentifier,
  Direction,
  Mapping,
  RepairStrategy,
  SharedModule,
  Substitution
} from '../../shared';
import { EditorMode } from '../shared/stepper.model';
import { CoreModule } from '@c8y/ngx-components';
import { PopoverModule } from 'ngx-bootstrap/popover';
import { Subject, takeUntil } from 'rxjs';

interface RepairStrategyOption {
  label: string;
  value: string;
  disabled: boolean;
}

export interface SubstitutionGridSettings {
  color: string;
  selectedSubstitutionIndex: number;
  editorMode: EditorMode;
}

@Component({
  selector: 'd11r-mapping-substitution-grid',
  templateUrl: 'substitution-grid.component.html',
  styleUrls: ['./substitution-grid.style.css'],
  encapsulation: ViewEncapsulation.None,
  standalone: true,
  imports: [CoreModule, PopoverModule, SharedModule, FormsModule]
})
export class SubstitutionRendererComponent implements OnDestroy {
  @Input()
  mapping!: Mapping;
  @Input()
  settings!: SubstitutionGridSettings;

  @Output() selectSub = new EventEmitter<number>();
  @Output() deleteSub = new EventEmitter<number>();
  @Output() editSub = new EventEmitter<number>();
  /** Fired after an in-grid edit of expandArray/repairStrategy so the parent can re-run validity checks. */
  @Output() substitutionChange = new EventEmitter<void>();
  /** Fired from the "Cancel / New substitution" control in the header, next to the selection count. */
  @Output() cancelEdit = new EventEmitter<void>();

  constructor(private bsModalService: BsModalService) { }

  readonly id = Math.floor(Math.random() * 1000000);
  readonly definesDeviceIdentifier = definesDeviceIdentifier;
  readonly EditorMode = EditorMode;
  readonly substitutionTemplateHelp = 'Substitutions defining the device identifier are marked with an "*". Before adding a substitution target and source property in templates have to be selected.';
  readonly targetHelp = 'The JSON path in the target template where the value extracted from the source is written to.';
  readonly expandArrayHelp = 'When the source expression extracts an array, create one substitution per array element instead of a single combined value. Not available for outbound mappings.';
  readonly repairStrategyHelp = 'How to handle a missing, null, or array source value: skip the substitution, remove the target node, create the target node if missing, or use the first/last element of an extracted array.';
  /**
   * One combined popover for all column meanings, shown above the table, rather than one
   * popover per header cell. A popover inside a narrow, table-layout: fixed <th> nested in a
   * position: sticky header consistently mispositioned itself (even with container="body") -
   * this sidesteps that fragile combination entirely instead of continuing to chase it.
   */
  readonly columnsHelpText = `
    <p><strong>Source</strong> - ${this.substitutionTemplateHelp}</p>
    <p><strong>Target</strong> - ${this.targetHelp}</p>
    <p><strong>Expand as array</strong> - ${this.expandArrayHelp}</p>
    <p><strong>Repair strategy</strong> - ${this.repairStrategyHelp}</p>
  `;

  private static readonly ARRAY_ONLY_STRATEGIES: string[] = [
    RepairStrategy.USE_FIRST_VALUE_OF_ARRAY,
    RepairStrategy.USE_LAST_VALUE_OF_ARRAY
  ];

  private destroy$ = new Subject<void>();

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  onSubstitutionSelect(index: number): void {
    this.settings.selectedSubstitutionIndex = index;
    this.selectSub.emit(index);
  }

  onSubstitutionEdit(index: number): void {
    this.settings.selectedSubstitutionIndex = index;
    this.editSub.emit(index);
  }

  /** OUTBOUND mappings always produce exactly one target message — there is no fan-out to
   *  expand into, so "Expand as array" has no effect and stays locked for that direction. */
  isExpandArrayDisabled(): boolean {
    return this.settings.editorMode === EditorMode.READ_ONLY || this.mapping.direction === Direction.OUTBOUND;
  }

  isRepairStrategyDisabled(): boolean {
    return this.settings.editorMode === EditorMode.READ_ONLY;
  }

  repairStrategyOptionsFor(sub: Substitution): RepairStrategyOption[] {
    return Object.keys(RepairStrategy).map(key => ({
      label: key,
      value: key,
      // USE_FIRST/LAST_VALUE_OF_ARRAY collapse an extracted array to a single element - meaningless
      // once "Expand as array" already splits the array into N substitutions.
      disabled: this.isRepairStrategyDisabled() ||
        (SubstitutionRendererComponent.ARRAY_ONLY_STRATEGIES.includes(key) && sub.expandArray)
    }));
  }

  onExpandArrayChange(sub: Substitution, expandArray: boolean): void {
    sub.expandArray = expandArray;
    if (expandArray && SubstitutionRendererComponent.ARRAY_ONLY_STRATEGIES.includes(sub.repairStrategy)) {
      sub.repairStrategy = RepairStrategy.DEFAULT;
    }
    this.substitutionChange.emit();
  }

  onRepairStrategyChange(sub: Substitution, repairStrategy: string): void {
    sub.repairStrategy = repairStrategy as RepairStrategy;
    this.substitutionChange.emit();
  }

  onSubstitutionDelete(index: number): void {
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
    confirmDeletionModalRef.content.closeSubject.pipe(takeUntil(this.destroy$)).subscribe(
      (result: boolean) => {
        if (result) {
          this.settings.selectedSubstitutionIndex = index;
          this.deleteSub.emit(index);
        }
        confirmDeletionModalRef.hide();
      }
    );
  }
}
