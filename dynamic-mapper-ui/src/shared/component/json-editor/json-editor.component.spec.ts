/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *  @authors Christof Strack, Stefan Witschel
 *
 */

import { ComponentFixture, TestBed } from '@angular/core/testing';
import { JsonEditorComponent, withoutButton } from './json-editor.component';

describe('JsonEditorComponent', () => {
  let fixture: ComponentFixture<JsonEditorComponent>;
  let component: JsonEditorComponent;

  const create = (options: any = {}) => {
    fixture = TestBed.createComponent(JsonEditorComponent);
    component = fixture.componentInstance;
    component.options = options;
    fixture.detectChanges();
  };

  afterEach(() => fixture?.destroy());

  it('returns programmatically set content and never emits contentChanged for it', async () => {
    create();
    const emitted: unknown[] = [];
    component.contentChanged.subscribe(c => emitted.push(c));

    component.data = { a: 1 };
    expect(component.get()).toEqual({ a: 1 });
    component.set({ a: 2 });
    expect(component.get()).toEqual({ a: 2 });

    await new Promise(r => setTimeout(r, 50));
    expect(emitted.length).toBe(0);
  });

  it('applies data and schema received before the editor was created', () => {
    fixture = TestBed.createComponent(JsonEditorComponent);
    component = fixture.componentInstance;
    component.options = {};
    component.data = { early: true };
    component.schema = { type: 'object' };
    fixture.detectChanges();

    expect(component.get()).toEqual({ early: true });
  });

  it('ignores empty data', () => {
    create();
    component.data = { a: 1 };
    component.data = {};
    component.data = null;
    expect(component.get()).toEqual({ a: 1 });
  });

  it('hides the transform button and the modes listed in removeModes from the menu', () => {
    create({ removeModes: ['text', 'table'] });
    const items: any[] = [
      { type: 'button', text: 'tree' },
      { type: 'button', text: 'text' },
      { type: 'button', text: 'table' },
      { type: 'button', className: 'jse-transform', text: 'transform' }
    ];

    const result = (component as any).onRenderMenu(items);

    expect(result.map((i: any) => i.text)).toEqual(['tree']);
  });

  it('keeps every menu item when nothing matches (no accidental splice of the last item)', () => {
    create({});
    const items: any[] = [{ type: 'button', text: 'tree' }, { type: 'button', text: 'text' }];
    expect((component as any).onRenderMenu(items).length).toBe(2);
  });

  it('removes the Transform button from nested context menu groups and drops emptied groups', () => {
    const items: any[] = [
      { type: 'button', text: 'Copy' },
      { type: 'row', items: [{ type: 'column', items: [{ type: 'button', text: 'Transform' }] }] },
      { type: 'dropdown-button', items: [{ type: 'button', text: 'Sort' }, { type: 'button', text: 'Transform' }] },
      { type: 'separator' }
    ];

    const result: any[] = withoutButton(items, 'Transform');

    expect(result.map(i => i.type)).toEqual(['button', 'dropdown-button', 'separator']);
    expect(result[1].items.map((i: any) => i.text)).toEqual(['Sort']);
  });

  it('emits the path of key, value and multi selections', () => {
    create();
    const paths: string[] = [];
    component.pathChanged.subscribe(p => paths.push(p));
    const onSelect = (s: any) => (component as any).onSelect(s);

    onSelect({ type: 'value', path: ['a', 'b', '0'] });
    onSelect({ type: 'key', path: ['x'] });
    onSelect({ type: 'multi', anchorPath: ['y'], focusPath: ['y'], path: ['y'] });
    onSelect(undefined);

    expect(paths).toEqual(['a.b[0]', 'x', 'y']);
  });

  it('does not select a path that is a JSONata expression', async () => {
    create();
    component.set({ a: 1 });
    const paths: string[] = [];
    component.pathChanged.subscribe(p => paths.push(p));

    await component.setSelectionToPath('$sum(a)');
    await component.setSelectionToPath('');

    expect(paths).toEqual([]);
  });

  describe('selection highlight (real editor)', () => {
    const highlighted = () =>
      Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('.jse-contents .jse-selected'));
    const settle = () => new Promise(r => setTimeout(r, 100));

    async function selectValue() {
      create();
      component.set({ value: 100, other: 1 });
      await settle();
      await component.setSelectionToPath('value');
      await settle();
    }

    it('highlights the node of a plain path', async () => {
      await selectValue();
      expect(highlighted().length).toBeGreaterThan(0);
    });

    it('removes the previous highlight when the path is an expression', async () => {
      await selectValue();
      await component.setSelectionToPath('$now()');
      await settle();
      expect(highlighted().length).toBe(0);
    });

    it('removes the previous highlight when the path is empty', async () => {
      await selectValue();
      await component.setSelectionToPath('');
      await settle();
      expect(highlighted().length).toBe(0);
    });

    it('does not report the clearing as a path change', async () => {
      await selectValue();
      const paths: string[] = [];
      component.pathChanged.subscribe(p => paths.push(p));
      component.clearSelection();
      await settle();
      expect(paths).toEqual([]);
    });
  });

  it('does not pass removeModes to the library', () => {
    create({ readOnly: true, removeModes: ['text'] });
    expect((component as any).libraryProps()).toEqual({ readOnly: true });
  });
});
