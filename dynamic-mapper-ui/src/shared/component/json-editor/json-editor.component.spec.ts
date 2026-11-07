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

  it('does not pass removeModes to the library', () => {
    create({ readOnly: true, removeModes: ['text'] });
    expect((component as any).libraryProps()).toEqual({ readOnly: true });
  });
});
