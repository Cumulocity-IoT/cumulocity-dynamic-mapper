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

import { NO_ERRORS_SCHEMA } from '@angular/core';
import { provideTranslateStub } from '../../shared/testing/translate-stub';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { PopoverConfirmComponent } from '@c8y/ngx-components';
import '../../shared';
import { RepairStrategy, Substitution } from '../../shared';
import { SubstitutionGridComponent, SubstitutionGridSettings } from './substitution-grid.component';

/**
 * Unit tests for {@link SubstitutionGridComponent} — the substitution list/grid.
 * The interesting behaviour is selection/edit emission and the delete-confirmation flow,
 * which only fires the {@link SubstitutionGridComponent.deleteSub} output once the user
 * confirms the modal.
 */
describe('SubstitutionGridComponent', () => {
  let component: SubstitutionGridComponent;
  let fixture: ComponentFixture<SubstitutionGridComponent>;

  const settings = (): SubstitutionGridSettings => ({
    color: '#fff',
    selectedSubstitutionIndex: -1,
    readOnly: false
  });

  beforeEach(async () => {
    TestBed.overrideComponent(SubstitutionGridComponent, {
      set: { imports: [], providers: [], schemas: [NO_ERRORS_SCHEMA], template: '<div></div>' }
    });

    await TestBed.configureTestingModule({
      imports: [SubstitutionGridComponent],
      providers: [provideTranslateStub()]
    }).compileComponents();

    fixture = TestBed.createComponent(SubstitutionGridComponent);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('settings', settings());
  });

  it('creates', () => {
    expect(component).toBeTruthy();
  });

  describe('in-grid edits', () => {
    const sub = (overrides: Partial<Substitution> = {}): Substitution => ({
      pathSource: '$.a', pathTarget: '$.b', repairStrategy: RepairStrategy.DEFAULT, expandArray: false, ...overrides
    });

    it('announces an expandArray change as a patch and leaves the substitution untouched', () => {
      const original = sub();
      const patches: any[] = [];
      component.substitutionPatch.subscribe(p => patches.push(p));

      component.onExpandArrayChange(2, original, true);

      expect(patches).toEqual([{ index: 2, patch: { expandArray: true } }]);
      expect(original.expandArray).toBeFalse();
    });

    it('also resets an array-only repair strategy when expanding', () => {
      const patches: any[] = [];
      component.substitutionPatch.subscribe(p => patches.push(p));

      component.onExpandArrayChange(0, sub({ repairStrategy: RepairStrategy.USE_FIRST_VALUE_OF_ARRAY }), true);

      expect(patches).toEqual([{ index: 0, patch: { expandArray: true, repairStrategy: RepairStrategy.DEFAULT } }]);
    });

    it('announces a repair strategy change as a patch', () => {
      const patches: any[] = [];
      component.substitutionPatch.subscribe(p => patches.push(p));

      component.onRepairStrategyChange(1, 'IGNORE');

      expect(patches).toEqual([{ index: 1, patch: { repairStrategy: RepairStrategy.IGNORE } }]);
    });
  });

  it('emits the selected index and leaves the settings to the host', (done) => {
    component.selectSub.subscribe((i) => {
      expect(i).toBe(2);
      expect(component.settings().selectedSubstitutionIndex).toBe(-1); // the host owns it
      done();
    });
    component.onSubstitutionSelect(2);
  });

  const fakePopover = (answer: 'confirm' | 'cancel' | 'dismiss') =>
    ({
      show: jasmine.createSpy('show').and.callFake((buttons: { action: () => Promise<boolean> }[]) =>
        answer === 'dismiss' ? Promise.reject() : buttons[answer === 'confirm' ? 1 : 0].action()
      )
    }) as unknown as PopoverConfirmComponent;

  it('emits deleteSub only after the confirmation popover is confirmed', async () => {
    const deleted: number[] = [];
    component.deleteSub.subscribe((i) => deleted.push(i));

    await component.onSubstitutionDelete(3, fakePopover('confirm'));

    expect(deleted).toEqual([3]);
    expect(component.settings().selectedSubstitutionIndex).toBe(-1); // untouched: the host adjusts its own index
  });

  it('does not emit deleteSub when the user cancels the popover', async () => {
    const deleted: number[] = [];
    component.deleteSub.subscribe((i) => deleted.push(i));

    await component.onSubstitutionDelete(3, fakePopover('cancel'));

    expect(deleted).toEqual([]);
  });

  it('does not emit deleteSub when the popover is dismissed with Escape or an outside click', async () => {
    const deleted: number[] = [];
    component.deleteSub.subscribe((i) => deleted.push(i));

    await component.onSubstitutionDelete(3, fakePopover('dismiss'));

    expect(deleted).toEqual([]);
  });
});
