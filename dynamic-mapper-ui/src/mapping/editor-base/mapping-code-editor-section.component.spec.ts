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

import { NO_ERRORS_SCHEMA, Pipe, PipeTransform } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
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
    component.dataCyPrefix = 'dm-x';
    component.showEditor = false;
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

  it('disables the AI button and shows the label it is given', () => {
    component.aiGenerationDisabled = true;
    component.aiButtonLabel = 'Review / Refine with AI';
    fixture.detectChanges();

    const button = el().querySelector('[data-cy="dm-x-generate-ai"]') as HTMLButtonElement;
    expect(button.disabled).toBeTrue();
    expect(button.textContent).toContain('Review / Refine with AI');
  });

  it('shows the AI help button only while no agent is deployed', () => {
    component.aiAgentDeployed = false;
    fixture.detectChanges();
    expect(el().querySelectorAll('.btn-help--sm').length).toBe(1);

    component.aiAgentDeployed = true;
    fixture.componentRef.changeDetectorRef.detectChanges();
    expect(el().querySelectorAll('.btn-help--sm').length).toBe(0);
  });

  it('shows the Protobuf note only when asked', () => {
    fixture.detectChanges();
    expect(el().textContent).not.toContain('No template to edit');

    component.showInternalNote = true;
    fixture.componentRef.changeDetectorRef.detectChanges();
    expect(el().textContent).toContain('No template to edit');
  });
});
