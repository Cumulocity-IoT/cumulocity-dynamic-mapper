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
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { BsModalService } from 'ngx-bootstrap/modal';
import { Subject } from 'rxjs';
import { SubstitutionRendererComponent, SubstitutionGridSettings } from './substitution-grid.component';
import { EditorMode } from '../shared/stepper.model';

/**
 * Unit tests for {@link SubstitutionRendererComponent} — the substitution list/grid.
 * The interesting behaviour is selection/edit emission and the delete-confirmation flow,
 * which only fires the {@link SubstitutionRendererComponent.deleteSub} output once the user
 * confirms the modal.
 */
describe('SubstitutionRendererComponent', () => {
  let component: SubstitutionRendererComponent;
  let fixture: ComponentFixture<SubstitutionRendererComponent>;
  let mockBsModalService: jasmine.SpyObj<BsModalService>;
  let closeSubject: Subject<boolean>;
  let modalHide: jasmine.Spy;

  const settings = (): SubstitutionGridSettings => ({
    color: '#fff',
    selectedSubstitutionIndex: -1,
    editorMode: EditorMode.CREATE
  });

  beforeEach(async () => {
    closeSubject = new Subject<boolean>();
    modalHide = jasmine.createSpy('hide');
    mockBsModalService = jasmine.createSpyObj('BsModalService', ['show']);
    mockBsModalService.show.and.returnValue({ content: { closeSubject }, hide: modalHide } as any);

    TestBed.overrideComponent(SubstitutionRendererComponent, {
      set: { imports: [], providers: [], schemas: [NO_ERRORS_SCHEMA], template: '<div></div>' }
    });

    await TestBed.configureTestingModule({
      imports: [SubstitutionRendererComponent],
      providers: [{ provide: BsModalService, useValue: mockBsModalService }]
    }).compileComponents();

    fixture = TestBed.createComponent(SubstitutionRendererComponent);
    component = fixture.componentInstance;
    component.settings = settings();
  });

  it('creates', () => {
    expect(component).toBeTruthy();
  });

  it('emits the selected index and records it in settings on select', (done) => {
    component.selectSub.subscribe((i) => {
      expect(i).toBe(2);
      expect(component.settings.selectedSubstitutionIndex).toBe(2);
      done();
    });
    component.onSubstitutionSelect(2);
  });

  it('emits the index and records it in settings on edit', (done) => {
    component.editSub.subscribe((i) => {
      expect(i).toBe(1);
      expect(component.settings.selectedSubstitutionIndex).toBe(1);
      done();
    });
    component.onSubstitutionEdit(1);
  });

  it('emits deleteSub only after the confirmation modal is confirmed', () => {
    const deleted: number[] = [];
    component.deleteSub.subscribe((i) => deleted.push(i));

    component.onSubstitutionDelete(3);
    expect(mockBsModalService.show).toHaveBeenCalled();
    expect(deleted).toEqual([]); // nothing yet — awaiting confirmation

    closeSubject.next(true); // user confirms
    expect(deleted).toEqual([3]);
    expect(component.settings.selectedSubstitutionIndex).toBe(3);
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
