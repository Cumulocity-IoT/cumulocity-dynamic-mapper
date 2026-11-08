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
import { gettext } from '@c8y/ngx-components/gettext';
import { TranslateService } from '@ngx-translate/core';
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
import { CoreModule } from '@c8y/ngx-components';
import { PopoverModule } from 'ngx-bootstrap/popover';
import { Subject, takeUntil } from 'rxjs';

interface RepairStrategyOption {
  label: string;
  value: string;
  description: string;
  disabled: boolean;
}

export interface SubstitutionGridSettings {
  color: string;
  selectedSubstitutionIndex: number;
  readOnly: boolean;
}

@Component({
  selector: 'd11r-mapping-substitution-grid',
  templateUrl: 'substitution-grid.component.html',
  styleUrls: ['./substitution-grid.style.css'],
  encapsulation: ViewEncapsulation.None,
  standalone: true,
  imports: [CoreModule, PopoverModule, SharedModule, FormsModule]
})
export class SubstitutionGridComponent implements OnDestroy {
  @Input()
  mapping!: Mapping;
  @Input()
  settings!: SubstitutionGridSettings;

  @Output() selectSub = new EventEmitter<number>();
  @Output() deleteSub = new EventEmitter<number>();
  /** An in-grid edit of expandArray/repairStrategy. The grid never changes the mapping itself. */
  @Output() substitutionPatch = new EventEmitter<{ index: number; patch: Partial<Substitution> }>();
  /** Fired from the "Cancel / New substitution" control in the header, next to the selection count. */
  @Output() cancelEdit = new EventEmitter<void>();

  constructor(
    private bsModalService: BsModalService,
    private translateService: TranslateService
  ) { }

  readonly id = Math.floor(Math.random() * 1000000);
  readonly definesDeviceIdentifier = definesDeviceIdentifier;
  readonly substitutionTemplateHelp = gettext('Substitutions defining the device identifier are marked with an "*". Before adding a substitution target and source property in templates have to be selected.');
  readonly targetHelp = gettext('The JSON path in the target template where the value extracted from the source is written to.');
  readonly expandArrayHelp = gettext('When the source expression extracts an array, create one Cumulocity request per array element instead of writing the whole array into one request. Arrays from several substitutions are paired by position. Not available for outbound mappings.');
  readonly repairStrategyHelp = gettext('How to handle a missing, null, or array source value: skip the substitution, remove the target node, create the target node if missing, or use the first/last element of an extracted array.');
  /**
   * One combined popover for all column meanings, shown above the table, rather than one
   * popover per header cell. A popover inside a narrow, table-layout: fixed <th> nested in a
   * position: sticky header consistently mispositioned itself (even with container="body") -
   * this sidesteps that fragile combination entirely instead of continuing to chase it.
   */
  get columnsHelpText(): string {
    const t = (text: string) => this.translateService.instant(text);
    return `
    <p><strong>${t(gettext('Source'))}</strong> - ${t(this.substitutionTemplateHelp)}</p>
    <p><strong>${t(gettext('Target'))}</strong> - ${t(this.targetHelp)}</p>
    <p><strong>${t(gettext('Expand as array'))}</strong> - ${t(this.expandArrayHelp)}</p>
    <p><strong>${t(gettext('Repair strategy'))}</strong> - ${t(this.repairStrategyHelp)}</p>
  `;
  }

  private static readonly ARRAY_ONLY_STRATEGIES: string[] = [
    RepairStrategy.USE_FIRST_VALUE_OF_ARRAY,
    RepairStrategy.USE_LAST_VALUE_OF_ARRAY
  ];

  /** Kept in sync with the backend enum `dynamic.mapper.processor.model.RepairStrategy`. */
  private static readonly REPAIR_STRATEGY_DESCRIPTIONS: Record<string, string> = {
    [RepairStrategy.DEFAULT]:
      gettext('Write the extracted value to the target path as-is. The path must already exist in the target template.'),
    [RepairStrategy.USE_FIRST_VALUE_OF_ARRAY]:
      gettext('If the extracted value is an array, use only its first element. Not applicable when "Expand as array" is set.'),
    [RepairStrategy.USE_LAST_VALUE_OF_ARRAY]:
      gettext('If the extracted value is an array, use only its last element. Not applicable when "Expand as array" is set.'),
    [RepairStrategy.IGNORE]:
      gettext('If the extracted value is missing or null, skip the substitution and keep whatever the target template defines.'),
    [RepairStrategy.REMOVE_IF_MISSING_OR_NULL]:
      gettext('If the extracted value is missing or null, delete the target node from the target template.'),
    [RepairStrategy.CREATE_IF_MISSING]:
      gettext('Create the target node, including missing parent nodes, if it does not exist in the target template.')
  };

  private destroy$ = new Subject<void>();

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  onSubstitutionSelect(index: number): void {
    // The host owns the selected index and hands it back through `settings`.
    this.selectSub.emit(index);
  }

  /** OUTBOUND mappings always produce exactly one target message — there is no fan-out to
   *  expand into, so "Expand as array" has no effect and stays locked for that direction. */
  isExpandArrayDisabled(): boolean {
    return this.settings.readOnly || this.mapping.direction === Direction.OUTBOUND;
  }

  isRepairStrategyDisabled(): boolean {
    return this.settings.readOnly;
  }

  repairStrategyOptionsFor(sub: Substitution): RepairStrategyOption[] {
    return Object.keys(RepairStrategy).map(key => ({
      label: key,
      value: key,
      description: this.describeStrategy(key),
      // USE_FIRST/LAST_VALUE_OF_ARRAY collapse an extracted array to a single element - meaningless
      // once "Expand as array" already splits the array into N substitutions.
      disabled: this.isRepairStrategyDisabled() ||
        (SubstitutionGridComponent.ARRAY_ONLY_STRATEGIES.includes(key) && sub.expandArray)
    }));
  }

  private describeStrategy(strategy: string): string {
    const description = SubstitutionGridComponent.REPAIR_STRATEGY_DESCRIPTIONS[strategy];
    return description ? this.translateService.instant(description) : '';
  }

  /** Tooltip on the select itself: what the currently chosen strategy does. */
  repairStrategyDescriptionFor(sub: Substitution): string {
    return this.describeStrategy(sub.repairStrategy);
  }

  onExpandArrayChange(index: number, sub: Substitution, expandArray: boolean): void {
    const patch: Partial<Substitution> = { expandArray };
    if (expandArray && SubstitutionGridComponent.ARRAY_ONLY_STRATEGIES.includes(sub.repairStrategy)) {
      patch.repairStrategy = RepairStrategy.DEFAULT;
    }
    this.substitutionPatch.emit({ index, patch });
  }

  onRepairStrategyChange(index: number, repairStrategy: string): void {
    this.substitutionPatch.emit({ index, patch: { repairStrategy: repairStrategy as RepairStrategy } });
  }

  onSubstitutionDelete(index: number): void {
    const initialState = {
      title: gettext('Delete substitution'),
      message: gettext('You are about to delete a substitution. Do you want to proceed?'),
      labels: {
        ok: gettext('Delete'),
        cancel: gettext('Cancel')
      }
    };
    const confirmDeletionModalRef: BsModalRef = this.bsModalService.show(
      ConfirmationModalComponent,
      { initialState }
    );
    confirmDeletionModalRef.content.closeSubject.pipe(takeUntil(this.destroy$)).subscribe(
      (result: boolean) => {
        if (result) {
          this.deleteSub.emit(index);
        }
        confirmDeletionModalRef.hide();
      }
    );
  }
}
