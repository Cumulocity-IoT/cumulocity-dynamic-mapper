/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
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
  ChangeDetectionStrategy,
  Component,
  EventEmitter,
  OnDestroy,
  Output,
  ViewEncapsulation, input } from '@angular/core';
import { gettext } from '@c8y/ngx-components/gettext';
import { TranslateService } from '@ngx-translate/core';
import {
  definesDeviceIdentifier,
  Mapping,
  RepairStrategy,
  SharedModule,
  Substitution
} from '../../shared';
import { CoreModule, PopoverConfirmComponent } from '@c8y/ngx-components';
import { confirmWithPopover } from '../../shared/component/popover-confirm';
import { PopoverModule } from 'ngx-bootstrap/popover';
import { Subject } from 'rxjs';
import { isExpression } from '../../shared/mapping/util';
import { FormatStringPipe } from '../../shared/misc/format-string.pipe';
import { REPAIR_STRATEGY_DESCRIPTIONS } from './repair-strategy';

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
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CoreModule, PopoverModule, SharedModule]
})
export class SubstitutionGridComponent implements OnDestroy {
  readonly mapping = input<Mapping>();
  readonly settings = input<SubstitutionGridSettings>();

  @Output() selectSub = new EventEmitter<number>();
  @Output() deleteSub = new EventEmitter<number>();

  constructor(
    private translateService: TranslateService
  ) { }

  readonly id = Math.floor(Math.random() * 1000000);
  readonly definesDeviceIdentifier = definesDeviceIdentifier;
  readonly isExpression = isExpression;
  readonly substitutionTemplateHelp = gettext('Substitutions defining the device identifier are marked as "Device identifier". Before adding a substitution target and source property in templates have to be selected.');
  readonly expandArrayHelp = gettext('When the source expression extracts an array, create one Cumulocity request per array element instead of writing the whole array into one request. Arrays from several substitutions are paired by position. Not available for outbound mappings.');

  private readonly formatAsWords = new FormatStringPipe();
  private destroy$ = new Subject<void>();

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  onSubstitutionSelect(index: number): void {
    // The host owns the selected index and hands it back through `settings`.
    this.selectSub.emit(index);
  }

  /** Enter selects the row, but not when it was pressed on a control inside the row (e.g. Remove). */
  onRowEnter(event: Event, index: number): void {
    if (event.target === event.currentTarget) {
      this.onSubstitutionSelect(index);
    }
  }

  /** Arrow keys move the focus between rows. */
  focusSibling(event: Event, step: 1 | -1): void {
    const row = event.currentTarget as HTMLElement;
    const sibling = (step === 1 ? row.nextElementSibling : row.previousElementSibling) as HTMLElement | null;
    if (sibling?.getAttribute('role') === 'option') {
      sibling.focus();
      event.preventDefault();
    }
  }

  hasCustomRepairStrategy(sub: Substitution): boolean {
    return !!sub.repairStrategy && sub.repairStrategy !== RepairStrategy.DEFAULT;
  }

  repairStrategyLabel(sub: Substitution): string {
    return this.formatAsWords.transform(sub.repairStrategy);
  }

  /** Tooltip on the repair strategy label: what the strategy does. */
  repairStrategyDescriptionFor(sub: Substitution): string {
    const description = REPAIR_STRATEGY_DESCRIPTIONS[sub.repairStrategy];
    return description ? this.translateService.instant(description) : '';
  }

  /** Clicking Remove must not also select the row it sits in. */
  onDeleteClick(event: Event, index: number, popover: PopoverConfirmComponent): void {
    event.stopPropagation();
    void this.onSubstitutionDelete(index, popover);
  }

  async onSubstitutionDelete(index: number, popover: PopoverConfirmComponent): Promise<void> {
    const confirmed = await confirmWithPopover(popover, {
      title: gettext('Delete substitution'),
      message: gettext('You are about to delete a substitution. Do you want to proceed?')
    });
    if (confirmed) {
      this.deleteSub.emit(index);
    }
  }
}
