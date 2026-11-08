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

import { NO_ERRORS_SCHEMA } from '@angular/core';
import { provideTranslateStub } from '../../shared/testing/translate-stub';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { BsModalService } from 'ngx-bootstrap/modal';
import { Subject } from 'rxjs';
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
  let mockBsModalService: jasmine.SpyObj<BsModalService>;
  let closeSubject: Subject<boolean>;
  let modalHide: jasmine.Spy;

  const settings = (): SubstitutionGridSettings => ({
    color: '#fff',
    selectedSubstitutionIndex: -1,
    readOnly: false
  });

  beforeEach(async () => {
    closeSubject = new Subject<boolean>();
    modalHide = jasmine.createSpy('hide');
    mockBsModalService = jasmine.createSpyObj('BsModalService', ['show']);
    mockBsModalService.show.and.returnValue({ content: { closeSubject }, hide: modalHide } as any);

    TestBed.overrideComponent(SubstitutionGridComponent, {
      set: { imports: [], providers: [], schemas: [NO_ERRORS_SCHEMA], template: '<div></div>' }
    });

    await TestBed.configureTestingModule({
      imports: [SubstitutionGridComponent],
      providers: [{ provide: BsModalService, useValue: mockBsModalService }, provideTranslateStub()]
    }).compileComponents();

    fixture = TestBed.createComponent(SubstitutionGridComponent);
    component = fixture.componentInstance;
    component.settings = settings();
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
      expect(component.settings.selectedSubstitutionIndex).toBe(-1); // the host owns it
      done();
    });
    component.onSubstitutionSelect(2);
  });

  it('emits deleteSub only after the confirmation modal is confirmed', () => {
    const deleted: number[] = [];
    component.deleteSub.subscribe((i) => deleted.push(i));

    component.onSubstitutionDelete(3);
    expect(mockBsModalService.show).toHaveBeenCalled();
    expect(deleted).toEqual([]); // nothing yet — awaiting confirmation

    closeSubject.next(true); // user confirms
    expect(deleted).toEqual([3]);
    expect(component.settings.selectedSubstitutionIndex).toBe(-1); // untouched: the host adjusts its own index
    expect(modalHide).toHaveBeenCalled();
  });

  it('does not emit deleteSub when the user cancels the modal', () => {
    const deleted: number[] = [];
    component.deleteSub.subscribe((i) => deleted.push(i));

    component.onSubstitutionDelete(3);
    closeSubject.next(false); // user cancels

    expect(deleted).toEqual([]);
    expect(modalHide).toHaveBeenCalled();
  });
});
