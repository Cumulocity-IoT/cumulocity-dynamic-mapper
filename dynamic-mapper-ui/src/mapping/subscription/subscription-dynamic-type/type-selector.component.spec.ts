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

import { TestBed } from '@angular/core/testing';
import { TypeSelectorComponent } from './type-selector.component';

describe('TypeSelectorComponent (inline resync column)', () => {
  let component: TypeSelectorComponent;

  beforeEach(() => {
    // Built in an injection context (signal inputs need one) and never rendered: the inputs the logic reads are
    // replaced by plain functions, and the rows the typeList input would have produced are set directly.
    component = TestBed.runInInjectionContext(() => new TypeSelectorComponent());
    Object.assign(component, { inline: () => true, showResync: () => true, knownTypes: () => ['c8y_Old'] });
    component.typeListInternal = ['c8y_Old', 'c8y_New'];
  });

  it('offers the resync only for types the server does not have yet', () => {
    expect(component.isNewType('c8y_Old')).toBeFalse();
    expect(component.isNewType('c8y_New')).toBeTrue();
    expect(component.isNewType('  ')).toBeFalse();
    expect(component.isNewType(undefined)).toBeFalse();
  });

  it('treats a blank row as neither known nor new, so its checkbox is shown (disabled), not "subscribed already"', () => {
    expect(component.isKnownType('c8y_Old')).toBeTrue();
    expect(component.isKnownType('c8y_New')).toBeFalse();
    expect(component.isKnownType('')).toBeFalse();
    expect(component.isKnownType('  ')).toBeFalse();
    expect(component.isNewType('')).toBeFalse();
  });

  it('emits the ticked types, adding and removing one at a time', () => {
    const emitted: string[][] = [];
    component.resyncTypes.subscribe(v => emitted.push(v));

    component.setResync(' c8y_New ', true);
    expect(emitted.at(-1)).toEqual(['c8y_New']);

    component.resyncTypes.set(['c8y_New', 'c8y_Other']);
    component.setResync('c8y_New', false);
    expect(emitted.at(-1)).toEqual(['c8y_Other']);
    expect(component.isResync('c8y_Other')).toBeTrue();
  });

  it('ignores a blank type', () => {
    const emitted: string[][] = [];
    component.resyncTypes.subscribe(v => emitted.push(v));
    component.setResync('  ', true);
    expect(emitted).toEqual([]);
  });
});
