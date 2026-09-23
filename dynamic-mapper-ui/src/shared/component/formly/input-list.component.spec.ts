/*
 * Copyright (c) 2026 Cumulocity GmbH
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
 */

import { NO_ERRORS_SCHEMA } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { InputListComponent } from './input-list.component';

// InputListComponent imports CoreModule for its template (c8yInputGroupListContainer /
// c8y-input-group-list), which reaches into the c8y app-shell DI graph (ApplicationService) that
// a bare TestBed does not provide (NG0201) — same fix as status.renderer.component.spec.ts /
// version-badge.renderer.component.spec.ts. Dropping the import and using NO_ERRORS_SCHEMA is
// fine here since these tests exercise the `data` setter/add()/remove() logic directly, not the
// rendered markup.
describe('InputListComponent', () => {
  let component: InputListComponent;
  let fixture: ComponentFixture<InputListComponent>;

  beforeEach(async () => {
    TestBed.resetTestingModule();
    TestBed.overrideComponent(InputListComponent, {
      set: { imports: [], schemas: [NO_ERRORS_SCHEMA] }
    });
    await TestBed.configureTestingModule({
      imports: [InputListComponent]
    }).compileComponents();

    fixture = TestBed.createComponent(InputListComponent);
    component = fixture.componentInstance;
  });

  it('starts with one blank row when the incoming data is empty', () => {
    component.data = {};
    expect(component.dataInternal).toEqual([{ key: '', value: '' }]);
  });

  it('loads existing entries from an object map', () => {
    component.data = { 'X-Api-Key': 'secret' };
    expect(component.dataInternal).toEqual([{ key: 'X-Api-Key', value: 'secret' }]);
  });

  describe('regression: clicking "+" used to be immediately undone', () => {
    // The formly wrapper (InputListFormlyComponent) binds [data]="getCurrentData()" — a method
    // call — so Angular re-invokes the `data` setter on every change-detection cycle, not just
    // when the underlying form control's value actually changes. Simulate that here by
    // re-assigning the *same* incoming value right after add(), exactly as the next CD tick
    // would, without going through the full formly component.

    it('keeps a newly added blank row when the list started empty', () => {
      component.data = {}; // initial load: one blank row
      component.add(); // user clicks "+": now two blank rows

      // Next change-detection tick: parent re-binds the same (unchanged) persisted value.
      component.data = {};

      expect(component.dataInternal.length).toBe(2);
    });

    it('keeps a newly added blank row alongside an already-completed header', () => {
      component.data = { Accept: 'text/plain' };
      component.add();

      // Next change-detection tick re-delivers the same persisted object (the blank row has no
      // key yet, so the formly wrapper wouldn't have written it back into the form control).
      component.data = { Accept: 'text/plain' };

      expect(component.dataInternal).toEqual([
        { key: 'Accept', value: 'text/plain' },
        { key: '', value: '' }
      ]);
    });

    it('still picks up a genuine external change (e.g. a different connector loaded into the same drawer)', () => {
      component.data = { Accept: 'text/plain' };
      component.add();

      component.data = { 'X-Other': 'value' };

      expect(component.dataInternal).toEqual([{ key: 'X-Other', value: 'value' }]);
    });
  });

  it('removing the only row leaves one blank row behind', () => {
    component.data = { Accept: 'text/plain' };
    component.remove(0);
    expect(component.dataInternal).toEqual([{ key: '', value: '' }]);
  });
});
