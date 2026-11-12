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

  describe('row labels', () => {
    const sub = (overrides: Partial<Substitution> = {}): Substitution => ({
      pathSource: '$.a', pathTarget: '$.b', repairStrategy: RepairStrategy.DEFAULT, expandArray: false, ...overrides
    });

    it('shows a repair strategy label only for a non-default strategy', () => {
      expect(component.hasCustomRepairStrategy(sub())).toBeFalse();
      expect(component.hasCustomRepairStrategy(sub({ repairStrategy: undefined }))).toBeFalse();
      expect(component.hasCustomRepairStrategy(sub({ repairStrategy: RepairStrategy.IGNORE }))).toBeTrue();
    });

    it('writes the repair strategy as words', () => {
      expect(component.repairStrategyLabel(sub({ repairStrategy: RepairStrategy.CREATE_IF_MISSING }))).toBe('Create If Missing');
    });
  });

  describe('keyboard', () => {
    it('Enter selects the row, but not when pressed on a control inside it', () => {
      const selected: number[] = [];
      component.selectSub.subscribe(i => selected.push(i));
      const row = document.createElement('div');
      const button = document.createElement('button');
      row.appendChild(button);

      component.onRowEnter({ target: row, currentTarget: row } as unknown as Event, 4);
      component.onRowEnter({ target: button, currentTarget: row } as unknown as Event, 5);

      expect(selected).toEqual([4]);
    });

    it('arrow keys move the focus to the neighbouring row', () => {
      const list = document.createElement('div');
      const rows = [0, 1].map(() => {
        const r = document.createElement('div');
        r.setAttribute('role', 'option');
        r.tabIndex = 0;
        list.appendChild(r);
        return r;
      });
      document.body.appendChild(list);
      const event = { currentTarget: rows[0], preventDefault: jasmine.createSpy('preventDefault') } as unknown as Event;

      component.focusSibling(event, 1);

      expect(document.activeElement).toBe(rows[1]);
      expect(event.preventDefault).toHaveBeenCalled();
      list.remove();
    });
  });

  it('removing a row does not also select it', async () => {
    const selected: number[] = [];
    component.selectSub.subscribe(i => selected.push(i));
    const event = jasmine.createSpyObj('Event', ['stopPropagation']);
    spyOn(component, 'onSubstitutionDelete').and.resolveTo();

    component.onDeleteClick(event, 1, {} as PopoverConfirmComponent);

    expect(event.stopPropagation).toHaveBeenCalled();
    expect(component.onSubstitutionDelete).toHaveBeenCalledWith(1, jasmine.anything());
    expect(selected).toEqual([]);
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
