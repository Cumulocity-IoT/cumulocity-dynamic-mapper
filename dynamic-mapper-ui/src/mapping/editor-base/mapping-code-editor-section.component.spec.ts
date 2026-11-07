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
