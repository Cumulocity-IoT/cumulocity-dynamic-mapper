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

import { Component, forwardRef, NO_ERRORS_SCHEMA, Pipe, PipeTransform } from '@angular/core';
import { ControlValueAccessor, FormsModule, NG_VALUE_ACCESSOR } from '@angular/forms';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { MappingCodeEditorSectionComponent } from './mapping-code-editor-section.component';

// CoreModule cannot be imported in a TestBed (see the ui-component-unit-test-pattern note), so the
// c8y elements are left unknown and only the translate pipe is stubbed.
@Pipe({ name: 'translate', standalone: true })
class TranslateStubPipe implements PipeTransform {
  transform(value: string): string {
    return value;
  }
}

describe('MappingCodeEditorSectionComponent', () => {
  let fixture: ComponentFixture<MappingCodeEditorSectionComponent>;
  let component: MappingCodeEditorSectionComponent;
  const el = () => fixture.nativeElement as HTMLElement;

  beforeEach(() => {
    TestBed.overrideComponent(MappingCodeEditorSectionComponent, {
      set: { imports: [TranslateStubPipe], schemas: [NO_ERRORS_SCHEMA] }
    });
    fixture = TestBed.createComponent(MappingCodeEditorSectionComponent);
    component = fixture.componentInstance;
    // Inputs are set the way a parent binds them: the component is OnPush, so assigning a property on
    // the instance would not re-render it.
    fixture.componentRef.setInput('dataCyPrefix', 'dm-x');
    fixture.componentRef.setInput('showEditor', false);
  });

  it('derives data-cy names from the prefix', () => {
    fixture.detectChanges();
    expect(el().querySelector('[data-cy="dm-x-generate-ai"]')).not.toBeNull();
    expect(el().querySelector('[data-cy="dm-x-code-template-select"]')).not.toBeNull();
    expect(el().querySelector('[data-cy="dm-x-code-template-create"]')).not.toBeNull();
  });

  it('emits generate and createTemplate from the buttons', () => {
    const events: string[] = [];
    component.generate.subscribe(() => events.push('generate'));
    component.createTemplate.subscribe(() => events.push('create'));
    fixture.detectChanges();

    (el().querySelector('[data-cy="dm-x-generate-ai"]') as HTMLButtonElement).click();
    (el().querySelector('[data-cy="dm-x-code-template-create"]') as HTMLButtonElement).click();

    expect(events).toEqual(['generate', 'create']);
  });

  it('emits replaceCode from the Replace code button, which needs a selected template', () => {
    const events: string[] = [];
    component.replaceCode.subscribe(() => events.push('replace'));
    fixture.detectChanges();
    const button = () => el().querySelector('[data-cy="dm-x-code-template-replace"]') as HTMLButtonElement;

    expect(button().disabled).toBeTrue();

    fixture.componentRef.setInput('templateId', 'INBOUND_SMART_FUNCTION');
    fixture.detectChanges();
    expect(button().disabled).toBeFalse();

    button().click();
    expect(events).toEqual(['replace']);
  });

  it('emits the template key when c8y-select reports the selected item', () => {
    const keys: string[] = [];
    component.templateSelected.subscribe(key => keys.push(key));

    component.onTemplateSelected({ label: 'Sample parsing payload as CSV (INBOUND_SMART_FUNCTION)', value: 'csv-key' });
    component.onTemplateSelected('plain-key');
    component.onTemplateSelected(null);
    component.onTemplateSelected(undefined);

    expect(keys).toEqual(['csv-key', 'plain-key']);
  });

  it('disables the AI button and shows the label it is given', () => {
    fixture.componentRef.setInput('aiGenerationDisabled', true);
    fixture.componentRef.setInput('aiButtonLabel', 'Review / Refine with AI');
    fixture.detectChanges();

    const button = el().querySelector('[data-cy="dm-x-generate-ai"]') as HTMLButtonElement;
    expect(button.disabled).toBeTrue();
    expect(button.textContent).toContain('Review / Refine with AI');
  });

  it('shows the AI help button only while no agent is deployed', () => {
    fixture.componentRef.setInput('aiAgentDeployed', false);
    fixture.detectChanges();
    expect(el().querySelectorAll('.btn-help--sm').length).toBe(1);

    fixture.componentRef.setInput('aiAgentDeployed', true);
    fixture.detectChanges();
    expect(el().querySelectorAll('.btn-help--sm').length).toBe(0);
  });

  it('shows the Protobuf note only when asked', () => {
    fixture.detectChanges();
    expect(el().textContent).not.toContain('No template to edit');

    fixture.componentRef.setInput('showInternalNote', true);
    fixture.detectChanges();
    expect(el().textContent).toContain('No template to edit');
  });

  it('does not re-render when a property is assigned without a binding change (it is OnPush)', () => {
    fixture.detectChanges();
    expect(el().textContent).not.toContain('No template to edit');

    component.showInternalNote = true;
    fixture.detectChanges();

    expect(el().textContent).not.toContain('No template to edit');
  });
});

describe('MappingCodeEditorSectionComponent bound by a parent', () => {
  // Stand-in for c8y-editor, which applies a model value to Monaco straight from writeValue.
  @Component({
    selector: 'c8y-editor',
    standalone: true,
    template: '',
    providers: [{ provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => FakeEditorComponent), multi: true }]
  })
  class FakeEditorComponent implements ControlValueAccessor {
    static written: string[] = [];
    writeValue(value: string): void { FakeEditorComponent.written.push(value); }
    registerOnChange(): void { /* not needed */ }
    registerOnTouched(): void { /* not needed */ }
  }

  // Stand-in for c8y-select. Like the real one, it reports the selected item, not the item's value.
  @Component({
    selector: 'c8y-select',
    standalone: true,
    template: '',
    providers: [{ provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => FakeSelectComponent), multi: true }]
  })
  class FakeSelectComponent implements ControlValueAccessor {
    static report: ((item: unknown) => void) | undefined;
    writeValue(): void { /* not needed */ }
    registerOnChange(fn: (item: unknown) => void): void { FakeSelectComponent.report = fn; }
    registerOnTouched(): void { /* not needed */ }
  }

  @Component({
    selector: 'd11r-test-parent',
    standalone: true,
    imports: [MappingCodeEditorSectionComponent],
    template: `<d11r-mapping-code-editor-section [code]="code" [templateId]="picked" [showEditor]="true"
      (templateSelected)="picked = $event"></d11r-mapping-code-editor-section>`
  })
  class ParentComponent {
    code = 'first';
    picked?: string;
  }

  function overrideSectionImports(): void {
    TestBed.overrideComponent(MappingCodeEditorSectionComponent, {
      set: { imports: [TranslateStubPipe, FormsModule, FakeEditorComponent, FakeSelectComponent], schemas: [NO_ERRORS_SCHEMA] }
    });
  }

  it('hands a code change made by the parent to the editor', async () => {
    FakeEditorComponent.written = [];
    overrideSectionImports();
    const parent = TestBed.createComponent(ParentComponent);
    parent.detectChanges();
    await parent.whenStable();
    expect(FakeEditorComponent.written).toContain('first');

    parent.componentInstance.code = 'replaced by template';
    parent.detectChanges();
    await parent.whenStable();

    expect(FakeEditorComponent.written).toContain('replaced by template');
  });

  it('hands the parent the template key when the select reports an item (so the lookup by key works)', async () => {
    overrideSectionImports();
    const parent = TestBed.createComponent(ParentComponent);
    parent.detectChanges();
    await parent.whenStable();

    FakeSelectComponent.report?.({ label: 'Sample parsing payload as CSV (INBOUND_SMART_FUNCTION)', value: 'csv-key' });
    parent.detectChanges();

    expect(parent.componentInstance.picked).toBe('csv-key');
  });

  it('writeCode puts code into the editor directly, without a change to the code binding', async () => {
    FakeEditorComponent.written = [];
    overrideSectionImports();
    const parent = TestBed.createComponent(ParentComponent);
    parent.detectChanges();
    await parent.whenStable();
    const section = parent.debugElement.query(By.directive(MappingCodeEditorSectionComponent))
      .componentInstance as MappingCodeEditorSectionComponent;

    section.writeCode('written directly');

    // The parent's binding still says 'first', so only the direct write can have produced this.
    expect(FakeEditorComponent.written).toContain('written directly');
  });
});
