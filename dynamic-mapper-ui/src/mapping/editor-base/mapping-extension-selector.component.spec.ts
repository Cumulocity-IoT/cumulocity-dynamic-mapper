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

import { CommonModule } from '@angular/common';
import { Component, forwardRef, NO_ERRORS_SCHEMA, Pipe, PipeTransform } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ControlValueAccessor, FormControl, FormGroup, NG_VALUE_ACCESSOR, ReactiveFormsModule, Validators } from '@angular/forms';
import { of } from 'rxjs';
import { Mapping } from '../../shared';
import { MappingExtensionSelectorComponent } from './mapping-extension-selector.component';

// CoreModule cannot be imported in a TestBed, so the c8y elements are left unknown, the translate pipe
// is stubbed, and c8y-select is replaced by a stand-in that only provides a value accessor.
@Pipe({ name: 'translate', standalone: true })
class TranslateStubPipe implements PipeTransform {
  transform(value: string): string {
    return value;
  }
}

@Component({
  selector: 'c8y-select',
  standalone: true,
  template: '',
  providers: [{ provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => FakeSelectComponent), multi: true }]
})
class FakeSelectComponent implements ControlValueAccessor {
  writeValue(): void { /* not needed */ }
  registerOnChange(): void { /* not needed */ }
  registerOnTouched(): void { /* not needed */ }
}

/**
 * The selector is OnPush. The host fills its form programmatically, which raises no event in the
 * selector's own view, so the selector has to notice the form changing itself.
 */
describe('MappingExtensionSelectorComponent', () => {
  let fixture: ComponentFixture<MappingExtensionSelectorComponent>;
  let form: FormGroup;
  const el = () => fixture.nativeElement as HTMLElement;

  beforeEach(() => {
    TestBed.overrideComponent(MappingExtensionSelectorComponent, {
      set: { imports: [CommonModule, ReactiveFormsModule, TranslateStubPipe, FakeSelectComponent], schemas: [NO_ERRORS_SCHEMA] }
    });
    form = new FormGroup({
      extensionName: new FormControl('', Validators.required),
      eventName: new FormControl('', Validators.required),
      extensionParameter: new FormControl('')
    });
    fixture = TestBed.createComponent(MappingExtensionSelectorComponent);
    fixture.componentRef.setInput('form', form);
    fixture.componentRef.setInput('mapping', {} as Mapping);
    fixture.componentRef.setInput('showSelectors', true);
    fixture.componentRef.setInput('extensionItems', ['ext']);
    fixture.componentRef.setInput(
      'extensionEvents',
      of([{ label: 'onEvent', value: 'onEvent', description: 'Converts the event' }])
    );
    // Twice: the async pipe emits synchronously during the first pass and leaves the view marked dirty.
    // The second pass clears that, so the tests below start from a clean OnPush view, as in the app.
    fixture.detectChanges();
    fixture.detectChanges();
  });

  it('shows the description of the event the host selects by patching the form', () => {
    expect(el().textContent).not.toContain('Converts the event');

    form.patchValue({ eventName: 'onEvent' });
    fixture.detectChanges();

    expect(el().textContent).toContain('Converts the event');
  });

  it('shows the required message once the host marks a touched, empty control invalid', () => {
    expect(el().textContent).not.toContain('Event name is required.');

    form.get('eventName')!.markAsTouched();
    form.get('eventName')!.updateValueAndValidity();
    fixture.detectChanges();

    expect(el().textContent).toContain('Event name is required.');
  });

  it('follows a form that replaces the earlier one', () => {
    const next = new FormGroup({
      extensionName: new FormControl(''),
      eventName: new FormControl(''),
      extensionParameter: new FormControl('')
    });
    fixture.componentRef.setInput('form', next);
    fixture.detectChanges();

    next.patchValue({ eventName: 'onEvent' });
    fixture.detectChanges();

    expect(el().textContent).toContain('Converts the event');
  });
});
