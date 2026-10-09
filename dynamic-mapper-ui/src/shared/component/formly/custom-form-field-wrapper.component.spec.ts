import { CommonModule } from '@angular/common';
import { Component, NO_ERRORS_SCHEMA, Pipe, PipeTransform } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { FormGroup, ReactiveFormsModule } from '@angular/forms';
import { FieldType, FormlyFieldConfig, FormlyModule } from '@ngx-formly/core';
import { WrapperCustomFormField } from './custom-form-field-wrapper.component';

// The wrapper's template uses c8y pipes; CoreModule cannot be loaded in a plain test injector
// (NG0201, see the overrideComponent note in mapping-properties.component.spec.ts), so stand-ins.
@Pipe({ name: 'translate', standalone: true })
class TranslateStub implements PipeTransform {
  transform(value: string): string {
    return value;
  }
}
@Pipe({ name: 'formatStringAsWords', standalone: true })
class FormatStub implements PipeTransform {
  transform(value: string): string {
    return value;
  }
}

@Component({ selector: 'formly-test-input', standalone: true, template: '<input [formControl]="formControl" />', imports: [ReactiveFormsModule] })
class TestInput extends FieldType {}

@Component({
  selector: 'host-cmp',
  standalone: true,
  imports: [FormlyModule, ReactiveFormsModule],
  template: '<formly-form [form]="form" [fields]="fields" [model]="model"></formly-form>'
})
class HostComponent {
  form = new FormGroup({});
  model = {};
  fields: FormlyFieldConfig[] = [
    {
      key: 'filterInventory',
      type: 'test-input',
      wrappers: ['d11r-wrapper-form-field'],
      props: { label: 'Filter Inventory', description: 'x'.repeat(100) }
    }
  ];
}

describe('WrapperCustomFormField', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [
        HostComponent,
        FormlyModule.forRoot({
          types: [{ name: 'test-input', component: TestInput }],
          wrappers: [{ name: 'd11r-wrapper-form-field', component: WrapperCustomFormField }]
        })
      ]
    });
    TestBed.overrideComponent(WrapperCustomFormField, {
      set: { imports: [CommonModule, FormlyModule, TranslateStub, FormatStub], providers: [], schemas: [NO_ERRORS_SCHEMA] }
    });
  });

  function warningElement(root: HTMLElement): HTMLElement | null {
    return root.querySelector('[data-cy="dm-formly-field-warning"]');
  }

  it('shows props.warning as an inline line under the input, even with a long (popover) description', () => {
    const fixture = TestBed.createComponent(HostComponent);
    fixture.componentInstance.fields[0].props!['warning'] = 'Not in the inventory cache: ty.';
    fixture.detectChanges();

    const warning = warningElement(fixture.nativeElement);
    expect(warning).withContext('warning element').not.toBeNull();
    expect(warning!.textContent).toContain('Not in the inventory cache: ty.');
    expect(warning!.classList).toContain('text-warning');
  });

  it('shows nothing when there is no warning', () => {
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();

    expect(warningElement(fixture.nativeElement)).toBeNull();
  });

  it('shows the warning when it is set after the field was rendered, and removes it again', () => {
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    expect(warningElement(fixture.nativeElement)).toBeNull();
    const field = fixture.componentInstance.fields[0];

    // formly-form is OnPush: a changed props value is picked up through options.detectChanges,
    // which is exactly what MappingStepPropertiesComponent.refreshFilterInventoryWarning calls.
    field.props!['warning'] = 'late warning';
    field.options!.detectChanges!(field);
    fixture.detectChanges();
    expect(warningElement(fixture.nativeElement)?.textContent).toContain('late warning');

    field.props!['warning'] = undefined;
    field.options!.detectChanges!(field);
    fixture.detectChanges();
    expect(warningElement(fixture.nativeElement)).toBeNull();
  });
});
